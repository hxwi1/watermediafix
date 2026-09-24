package dev.mediafix.engine;

import dev.mediafix.MediaFix;
import dev.mediafix.abr.AbrController;
import dev.mediafix.abr.AbrLadder;
import dev.mediafix.config.FfmpegConfig;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.ffmpeg.FfmpegVideoSource;

import java.net.URI;
import java.nio.ByteBuffer;

/**
 * 自研播放引擎：把"视频解码 / 音频解码 + 输出 / 主时钟"三块粘成一个可独立使用的播放器。
 *
 * <p><b>对外只暴露状态与时间</b>，不碰任何 watermedia/waterframes 类型——桥接由 Mixin 层完成。
 * 这样做的目的就是将来能整包搬进独立模组。
 *
 * <p><b>时间轴</b>：有音频时以"音频可听位置"为主时钟（帧 PTS - sink 里还没播出的量），
 * 没有音频时由视频帧的 PTS 推动。视频呈现由外部（渲染线程）每次调用
 * {@link #acquireVideoFrame()} 拉取，引擎只负责判断"这一刻该显示哪一帧"。
 *
 * <p><b>线程</b>：一条启动线程（负责打开 + 协商音频格式）、一条音频投喂线程（写 PCM、更新时钟）、
 * 以及解码源自己的线程。视频帧的上传始终发生在调用 acquire 的那个线程（也就是渲染线程）。
 */
public final class MediaEngine implements AutoCloseable {

    public enum State {
        LOADING, PLAYING, PAUSED, BUFFERING, ENDED, ERROR
    }

    /** 当前的视频链（诊断/续期用）。 */
    public URI currentVideoUri() {
        return this.currentVideoUri;
    }

    /** 视频判定饥饿（无帧可显示）超过该毫秒数就进入 BUFFERING。 */
    private static final long STARVE_MS = 700L;

    /*
     * 压缩预读的低/高水位线（流媒体播放器的标准做法）。
     *
     * 为什么必须有：实测网络抖动时预读会以 800KB/s 流失（CDN 只给 5.6Mbps，而 4K 要 12Mbps）。
     * 预读见底后解码器拿不到包、帧到得晚，于是"丢过期帧"把它们全丢掉 —— 表现是画面掉到 3fps，
     * 比停下来缓冲还难受。正确做法是缓冲见底时**主动停下等**（音视频一起停），回到高水位再继续。
     */
    private static final long VIDEO_LOW_BYTES = 1_500_000L;    // ≈1 秒（12Mbps）
    private static final long VIDEO_HIGH_BYTES = 6_000_000L;   // ≈4 秒

    /** 视频缓冲见底导致的停机：期间不喂音频、时钟冻结（音视频一起停，绝不各跑各的）。 */
    private volatile boolean rebufferHold;
    /** 当前视频链是否已经攒够过缓冲（换链后置 false，攒到高水位才置 true）。 */
    private volatile boolean videoBufferHealthy;
    /** 最近一次换链的时间（用于换链后的回填宽限期）。 */
    private volatile long lastSourceSwapAt = System.currentTimeMillis();
    /** 诊断计数器基线需要重取（换了源对象）。 */
    private volatile boolean diagResetBaselines;

    private final URI videoUri;
    private final URI audioUri;
    private final String headers;

    private FfmpegVideoSource video;
    private FfmpegAudioSource audio;
    /** 当前实际在用的链（ABR 换档 / 直链续期后会变）。 */
    private volatile URI currentVideoUri;
    private volatile URI currentAudioUri;
    private volatile boolean videoSwapInProgress;
    private volatile boolean audioSwapInProgress;
    private final AudioSink sink = new AudioSink();
    private final MediaClock clock = new MediaClock();

    private volatile State state = State.LOADING;
    private volatile boolean closed;
    private volatile boolean wantPause;
    private volatile boolean repeat;
    private volatile int volumePercent = 100;
    private volatile long durationMs = -1L;
    private volatile long lastFrameWallMs = System.currentTimeMillis();
    private volatile String lastError = "";
    /** 最近一次"忽略原地 seek"的日志时间（限流，避免刷日志）。 */
    private volatile long lastNoopSeekLogAt;
    /** 真正执行的 seek 次数（诊断用）。 */
    private volatile int seekCount;
    /** 被忽略的原地 seek 次数（诊断用）—— 每一次都曾经意味着一次 2.5 秒音频缓冲被冲掉。 */
    private volatile int ignoredSeekCount;
    /** 就地快进：早于该时间戳的音频块直接丢弃（-1 = 不丢弃）。 */
    private volatile long audioSkipUntilMs = -1L;
    /** 就地快进的次数（诊断用）。 */
    private volatile int catchUpCount;

    private Thread bootThread;
    private Thread feeder;

    /**
     * @param videoUri 视频链（可为 null：纯音频）
     * @param audioUri 音频链（可为 null：纯视频）
     * @param headers  给 FFmpeg 的请求头（Referer/Cookie 等）
     */
    public MediaEngine(URI videoUri, URI audioUri, String headers) {
        this.videoUri = videoUri;
        this.audioUri = audioUri;
        this.headers = headers;
        // 原始两条链：ABR 换档会改 currentUri，所以"同源判定"必须用这两个
        this.originVideoUri = videoUri;
        this.originAudioUri = audioUri;
    }

    /** 最初请求的两条链（ABR 换档不改动，用于识别"同一个视频的引擎"）。 */
    private final URI originVideoUri;
    private final URI originAudioUri;

    /** 当前持有该引擎的播放器数（同一视频被多个播放器对象接管时会 >1）。 */
    private final java.util.concurrent.atomic.AtomicInteger holders =
            new java.util.concurrent.atomic.AtomicInteger(1);

    // =====================================================================
    // 生命周期
    // =====================================================================

    /** 异步启动：立刻返回，打开与协商在后台线程完成。 */
    public void start() {
        Thread t = new Thread(this::boot, "mediafix-engine-boot");
        t.setDaemon(true);
        this.bootThread = t;
        t.start();
    }

    private void boot() {
        try {
            if (this.videoUri != null) {
                this.video = new FfmpegVideoSource(this.videoUri, this.headers);
                this.currentVideoUri = this.videoUri;
                this.video.start();
            }
            if (this.audioUri != null) {
                openAudio();
            }
            this.durationMs = this.video != null && this.video.durationMs() > 0
                    ? this.video.durationMs()
                    : (this.audio != null ? this.audio.durationMs() : -1L);
            MediaFix.LOGGER.info("[mediafix] 引擎就绪: video={} audio={} duration={}ms",
                    MediaFix.LOGGER.url(this.videoUri), MediaFix.LOGGER.url(this.audioUri), this.durationMs);
            if (this.pendingSeekMs > 0) {
                long pos = this.pendingSeekMs;
                this.pendingSeekMs = 0;
                MediaFix.LOGGER.info("[mediafix] 应用挂起的跳转: {}ms（ABR 换档后接续位置）", pos);
                seek(pos);
            }
        } catch (Throwable t) {
            this.lastError = String.valueOf(t);
            setState(State.ERROR);
            MediaFix.LOGGER.error("[mediafix] 引擎启动失败", t);
        }
    }

    /** 打开音频并协商输出格式：输出声道 = min(源声道, 设备支持)，采样率沿用源，格式统一 S16。 */
    private void openAudio() {
        FfmpegAudioSource src = new FfmpegAudioSource(this.audioUri, this.headers);
        if (!src.prepare()) {
            this.lastError = src.lastError();
            MediaFix.LOGGER.warn("[mediafix] 音频打开失败，改为纯视频播放: {}", this.lastError);
            src.close();
            return;
        }

        int srcCh = src.sourceChannels();
        int srcRate = src.sourceSampleRate();
        int devMax = AudioSink.maxSupportedChannels(srcRate);
        int channels = Math.max(1, Math.min(srcCh, devMax));

        boolean opened = false;
        // 逐级降级：源声道 → 立体声 → 单声道；采样率降级到 48k
        for (int ch : new int[]{channels, 2, 1}) {
            for (int rate : new int[]{srcRate, 48000}) {
                if (this.sink.open(ch, rate)) {
                    opened = true;
                    channels = ch;
                    break;
                }
            }
            if (opened) break;
        }
        if (!opened) {
            MediaFix.LOGGER.error("[mediafix] 音频设备不可用，改为纯视频播放");
            src.close();
            return;
        }

        MediaFix.LOGGER.info("[mediafix] 音频输出协商: 源 {} {}ch {}Hz -> 设备 {}ch {}Hz (设备最多 {}ch)",
                src.sourceCodecName(), srcCh, srcRate, channels, this.sink.sampleRate(), devMax);
        if (srcCh > channels) {
            /*
             * 多声道源（杜比全景声是 6ch E-AC-3）遇到 2ch 设备时，走 libswresample 的标准下混矩阵
             * （中央声道/环绕按 -3dB 折进左右），不是简单丢声道，所以人声不会消失、只是没有环绕定位。
             *
             * 想要真环绕：系统里把输出设备配成 5.1/7.1（或用支持多声道的 HDMI 功放），
             * Java Sound 报出 6/8 声道后这里的协商会自动按 6ch 输出，无需改代码。
             * 至于"杜比全景声"本身的对象层：FFmpeg 只能解出 5.1 底混（bed），对象音频(Atmos JOC)
             * 没有开源渲染器，Java Sound 也没有 HDMI 位流直通(bitstream passthrough)能力，
             * 所以游戏里能拿到的最好结果就是"5.1 底混 + 按设备能力输出"。
             */
            MediaFix.LOGGER.info("[mediafix] 多声道下混: {} {}ch -> {}ch（设备未暴露环绕声道，已按标准矩阵下混）",
                    src.sourceCodecName(), srcCh, channels);
        }
        src.start(channels, this.sink.sampleRate());
        this.audio = src;
        this.currentAudioUri = this.audioUri;
        this.sink.setGain(this.volumePercent / 100f);

        Thread t = new Thread(this::feedLoop, "mediafix-engine-audio");
        t.setDaemon(true);
        this.feeder = t;
        t.start();
    }

    // =====================================================================
    // 音频投喂 + 时钟推进
    // =====================================================================

    private void feedLoop() {
        while (!this.closed) {
            try {
                checkVideoWatermark();
                if (this.wantPause || this.audio == null || this.rebufferHold) {
                    watchdog();
                    Thread.sleep(20);
                    continue;
                }
                FfmpegAudioSource.Chunk chunk = this.pendingChunk;
                this.pendingChunk = null;
                if (chunk == null) chunk = this.audio.poll();
                if (chunk == null) {
                    checkEnded();
                    // 欠载：缓冲见底 = 网络没跟上。转 BUFFERING（画面不会继续跑），并留一条诊断
                    // 真欠载 = 设备缓冲和环形缓冲都空了；只差一次 poll 不算
                    if (this.state == State.PLAYING && audioLeadMs() <= 0 && !this.audio.isEnded()) {
                        long now = System.currentTimeMillis();
                        if (now - this.lastUnderrunAt > 2000L) {
                            this.lastUnderrunAt = now;
                            MediaFix.LOGGER.warn("[mediafix] 音频欠载：缓冲耗尽，等待网络（可能是带宽或 CDN 抖动）");
                        }
                        this.clock.pause();
                        setState(State.BUFFERING, "音频欠载");
                    }
                    Thread.sleep(this.audio.isEnded() ? 30 : 2);
                    continue;
                }
                // 就地快进：早于目标的音频块直接丢（不写声卡），第一块越过目标后恢复正常
                long skipUntil = this.audioSkipUntilMs;
                if (skipUntil > 0) {
                    if (chunk.ptsMs < skipUntil) {
                        this.audio.release(chunk);
                        continue;
                    }
                    this.audioSkipUntilMs = -1L;
                    this.sink.expectDiscontinuity();
                    MediaFix.LOGGER.info("[mediafix] 音频快进完成：落到 {}ms（目标 {}ms）", chunk.ptsMs, skipUntil);
                }

                // 出队顺序必须单调递增：环形缓冲若把顺序搞乱，耳朵听到的就是"拼接没接好"
                if (this.lastFedPts >= 0 && chunk.ptsMs < this.lastFedPts - 2L
                        && System.currentTimeMillis() - this.lastSeekAt > 3000L) {
                    long now2 = System.currentTimeMillis();
                    if (now2 - this.lastOrderLogAt > 1000L) {
                        this.lastOrderLogAt = now2;
                        MediaFix.LOGGER.warn("[mediafix] 音频出队顺序倒退：上一块 {}ms(槽{})，本块 {}ms(槽{})（差 {}ms）",
                                this.lastFedPts, this.lastFedSlot, chunk.ptsMs, chunk.slotIndex,
                                chunk.ptsMs - this.lastFedPts);
                    }
                }
                this.lastFedPts = chunk.ptsMs;
                this.lastFedSlot = chunk.slotIndex;

                /*
                 * 攒批（"缓冲区拼接策略"）：把时间上连续的若干音块先拼成一块，再一次性写进设备。
                 *
                 * 以前是 21ms 一块、每秒写 47 次 —— 每次写入都是一个潜在的不连续点。
                 * 攒到 ~200ms 再写，边界数量直接降到原来的 1/9，而且每次交给设备的都是一段连续内存。
                 * 只拼【时间戳严格相接】的块；一旦不连续就停手，把它留到下一轮（绝不为凑批而乱序）。
                 */
                int total = 0;
                int count = 0;
                long lastEnd = chunk.ptsMs + chunkMs(chunk);
                total = appendChunk(total, chunk);
                count++;
                this.audio.release(chunk);
                while (count < MAX_BATCH_CHUNKS && total < BATCH_TARGET_BYTES) {
                    FfmpegAudioSource.Chunk next = this.audio.poll();
                    if (next == null) break;
                    if (next.ptsMs != lastEnd) {
                        this.pendingChunk = next;   // 不连续：留到下一轮处理，不为凑批打乱顺序
                        break;
                    }
                    this.lastFedPts = next.ptsMs;
                    this.lastFedSlot = next.slotIndex;
                    lastEnd = next.ptsMs + chunkMs(next);
                    total = appendChunk(total, next);
                    count++;
                    this.audio.release(next);
                }
                long chunkMs = lastEnd - chunk.ptsMs;
                // 主音量每 0.5 秒取一次即可：以前每块都取，而取的时候要反射读 waterframes 的配置，
                // 每秒 47 次反射纯属浪费，还会给音频线程添不必要的停顿
                long nowGain = System.currentTimeMillis();
                if (nowGain - this.lastGainAt > 500L) {
                    this.lastGainAt = nowGain;
                    this.masterGainCache = MediaEngines.masterGain();
                }
                this.sink.setMasterGain(this.masterGainCache);
                int written = this.sink.writeBatch(this.batchBuf, total);
                if (written <= 0) {
                    /*
                     * 一个字节都没写进去（设备被关/被 flush）。这里必须归还槽位再重试 ——
                     * 以前是直接 continue，槽位永远停在 SHOWING 状态：
                     * 那不只丢掉一块音频，还会让环形缓冲的空槽越来越少，最终解码线程彻底卡死。
                     */
                    if (this.closed) return;
                    Thread.sleep(5);
                    continue;
                }
                // 按【实际写入】记账，不能按块长记（否则时钟会超前于真实出声位置）
                this.sink.noteWritten(written);

                /*
                 * 这里【刻意什么都不做】。
                 *
                 * 曾经加过"核对 seek 落点，落点差得多就把画面/时钟拉过去"。但那个"落点"是错的：
                 * 它取的是环形缓冲最前端（最新解出来的块），领先实际播放位置整整一个环形缓冲
                 * （8 秒），所以报出来的偏差（4~13 秒）全是假的 —— 日志里那行警告甚至打在对应 seek
                 * 之前。拿假数据去 seekTo + 拽画面，就成了"一直在抢时间"，还把 waterframes 的
                 * 纠偏逻辑反复点着，音频被一遍遍 flush/重建，听感就是"拼接没接好"。
                 *
                 * 离线实测：音频 m4s 的 seek 落点误差只有 -22ms ~ 0ms，本来就准得很。
                 * 音频就是主时钟，视频跟着时钟走 —— 这就够了，不需要任何额外对齐。
                 */

                // 主时钟 = 这批音频播完时的"可听位置"（批末尾 PTS 减去还在设备缓冲里的量）
                long audibleMs = lastEnd - this.sink.pendingMs();
                this.clock.update(audibleMs / 1000.0, false);

                /*
                 * 缓冲回填：把冻结的时钟重新开起来。
                 *
                 * ★ 判据【不能】是"状态等于 BUFFERING"，否则会漏掉"状态已翻成 PLAYING、时钟还冻着"。
                 *
                 * 渲染线程拿到帧时会把状态直接翻成 PLAYING（acquireVideoFrame 只在 audio == null 时
                 * 才顺带启动时钟），于是这条路径必然把时钟永久留在冻结状态：
                 *   seek / 视频饥饿 → clock.pause() + BUFFERING → 帧一到 → PLAYING（时钟仍停）。
                 * 再也没有别的代码会把它拉起来：watchdog 只管 BUFFERING，pause(false) 要等玩家动手。
                 *
                 * 此时 update() 走的是 !running 分支，只把 frozenPts 设成音频可听位置 ——
                 * 时钟因此不再连续推进，而是【每写一批音频（~200ms）才跳一次】；
                 * 视频帧成批变成"已到点"，acquire 每次只显示最新的一帧、其余全按过期帧丢掉。
                 * 实测（4K 25fps，客户端日志 08:00:09~08:00:19）：显示 4fps、每秒丢 21 帧、
                 * 时钟 速率=1.000 残差=0ms（正是"从未被 update 正常校准过"的特征），
                 * 而玩家暂停/恢复（pause(false) 会 start 时钟）的瞬间就回到 25fps、丢帧计数不再增长。
                 *
                 * 判据改成"该在播、时钟却没在走"后就与"状态由谁先翻转"无关了；
                 * 保留 audioLead 门槛是为了不和上面的音频欠载分支互相打架（那边要求提前量 <= 0）。
                 *
                 * 位置也很关键：必须放在 clock.update() 之后 —— 此时 frozenPts 就是【此刻】的可听位置，
                 * start() 从它起步，画面与音频正好对齐；放在前面会慢半批（~200ms），
                 * 要靠时钟自己以 3% 速率慢慢追回来。
                 */
                if (!this.clock.isRunning() && !this.rebufferHold && !this.wantPause
                        && (this.state == State.PLAYING || this.state == State.BUFFERING)
                        && audioLeadMs() >= Math.min(audioGateMs() / 2, 400L)) {
                    this.clock.start();
                    if (this.state == State.BUFFERING) setState(State.PLAYING, "缓冲回填");
                    else MediaFix.LOGGER.info("[mediafix] 状态={} 但时钟没在走（seek/饥饿后未复位）：重新启动时钟（时钟={}ms）",
                            this.state, timeMs());
                }
                promoteIfReady();
                checkEnded();
                watchdog();
                mediafix$diag();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                MediaFix.LOGGER.error("[mediafix] 音频投喂线程异常", t);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /**
     * 压缩预读的水位线控制：见底就停、回满再走（带滞回，避免在阈值上反复抖）。
     * 停机时音频也一起停 —— 否则音频继续走，时钟跟着走，画面只会越落越远。
     */
    private void checkVideoWatermark() {
        FfmpegVideoSource v = this.video;
        if (v == null || this.closed || this.wantPause) return;
        long vbuf = v.bufferedBytes();

        /*
         * 换链后新链的预读从 0 开始，如果照旧判定，就会立刻"缓冲见底 → 暂停"，
         * 而 ABR 又把这记成"刚缓冲见底"，于是继续降档 —— 降档又换链、换链又是 0KB，
         * 形成一路往下掉的死循环（日志里连续几次降档就是这么来的）。
         * 所以：换链后先等它把预读攒到高水位，这期间不做"见底"判定。
         */
        if (!this.videoBufferHealthy) {
            if (vbuf >= VIDEO_HIGH_BYTES) {
                this.videoBufferHealthy = true;
            } else if (System.currentTimeMillis() - this.lastSourceSwapAt < 15_000L) {
                return;
            }
        }

        if (!this.rebufferHold) {
            if (this.state == State.PLAYING && !isEnded() && !v.isEnded()
                    && vbuf < VIDEO_LOW_BYTES && durationMs() > 0) {
                this.rebufferHold = true;
                this.sink.setPaused(true);
                this.clock.pause();
                long now = System.currentTimeMillis();
                if (now - this.lastRebufferLogAt > 2000L) {
                    this.lastRebufferLogAt = now;
                    /*
                     * 措辞要诚实：缓冲见底有两种完全不同的原因，别一律归咎于网速。
                     *  - 刚执行过 seek（原地/同步 seek 会冲掉约 2.5 秒音频缓冲，视频预读也被清）：
                     *    这是预期内的重填，日志里紧邻的 "seek -> ..." 就是证据；
                     *  - 没有任何 seek 却长时间回填不上、且预读一直上不来：那才是清晰度超出网络能力。
                     */
                    boolean justSeeked = now - this.lastSeekAt < 5000L;
                    MediaFix.LOGGER.warn("[mediafix] 视频缓冲见底（{}KB < {}KB）：暂停等待网络回填"
                                    + " —— {}（预读 {}KB，seek 执行 {} 次 / 忽略原地 seek {} 次）",
                            vbuf / 1024, VIDEO_LOW_BYTES / 1024,
                            justSeeked ? "刚 seek 过，属预期重填"
                                       : "长时间回填不上才说明清晰度超出网络能力，可 /mediafix-stream quality 降档",
                            vbuf / 1024, this.seekCount, this.ignoredSeekCount);
                }
                setState(State.BUFFERING, "视频缓冲见底");
            }
        } else if (vbuf >= VIDEO_HIGH_BYTES) {
            this.rebufferHold = false;
            this.sink.setPaused(false);
            MediaFix.LOGGER.info("[mediafix] 视频缓冲已回填到 {}KB，恢复播放", vbuf / 1024);
        }
    }

    private long lastRebufferLogAt;

    private long chunkMs(FfmpegAudioSource.Chunk chunk) {
        int channels = this.sink.channels();
        int rate = this.sink.sampleRate();
        if (channels <= 0 || rate <= 0) return 0L;
        long frames = chunk.length / (2L * channels);
        return frames * 1000L / rate;
    }

    // =====================================================================
    // 视频帧获取（渲染线程）
    // =====================================================================

    /**
     * 渲染线程每帧调用：返回此刻该显示的帧（可能为 null），上传完成后必须
     * 调用 {@link #releaseVideoFrame(FfmpegVideoSource.Frame)}。
     */
    public FfmpegVideoSource.Frame acquireVideoFrame() {
        promoteIfReady();
        FfmpegVideoSource v = this.video;
        if (v == null || !v.isReady() || this.wantPause) return null;

        // 偏移每帧读配置：改 mediafix-ffmpeg.json 后立即生效，便于微调对齐
        FfmpegVideoSource.Frame frame = v.acquire(this.clock.timeMs() + FfmpegConfig.avOffsetMs);
        if (frame != null) {
            this.lastFrameWallMs = System.currentTimeMillis();
            if (this.state == State.BUFFERING) {
                if (this.audio == null) this.clock.start();   // 没有音频源时由视频恢复时钟
                setState(State.PLAYING, "取到帧");
            }
            // 没有音频时由视频驱动时钟
            if (this.audio == null) this.clock.update(frame.ptsMs / 1000.0, false);
            return frame;
        }

        // "这一帧还没到显示时间"和"真的没帧可用"是两回事：只有帧环彻底空掉才叫饥饿。
        // 之前不区分，导致时钟和帧边界错开时就反复进 BUFFERING（日志里一秒刷十几条恢复记录）。
        if (this.state == State.PLAYING && !isEnded() && v.queuedFrames() == 0
                && System.currentTimeMillis() - this.lastFrameWallMs > STARVE_MS) {
            this.clock.pause();
            setState(State.BUFFERING, "视频饥饿");
            long now = System.currentTimeMillis();
            if (this.starveCount == 0) this.starveFirstAt = now;
            if (++this.starveCount == 3 && now - this.starveFirstAt < 30000L) {
                MediaFix.LOGGER.warn("[mediafix] 解码持续跟不上（30 秒内 3 次帧环空）："
                                + "建议 /mediafix-stream quality 1080p 降一档，或把 videoFrameBufferMb 调大"
                                + "（当前解码帧缓冲 {} 帧）", v.ringSlotCount());
            }
            if (now - this.starveFirstAt > 30000L) {
                this.starveCount = 0;
            }
        }
        return null;
    }

    /** 渲染线程调用归还的次数（诊断：应当与"取走帧的次数"逐步持平）。 */
    private volatile long releasesIn;

    public long releasesIn() {
        return this.releasesIn;
    }

    public void releaseVideoFrame(FfmpegVideoSource.Frame frame) {
        if (frame == null) return;
        this.releasesIn++;
        /*
         * ★ 归还给【产出这一帧的那条源】，而不是当前的 this.video。
         *
         * 用 this.video 归还时，只要视频源在"取帧 → 归还"之间被换掉过（无缝换链、直链续期、
         * 引擎关闭时置空），归还就会落空或落到另一条源上，原源的槽位永远停在 SHOWING ——
         * 实测"取走=550 归还=90"，帧环被占满、画面反复卡住并倍速追赶。
         * 帧自带源引用之后，这条路径与引擎的字段状态彻底解耦。
         */
        try {
            frame.releaseToSource();
        } catch (Throwable t) {
            // 源可能已彻底关闭：归还失败也不能让渲染线程跟着炸
            MediaFix.LOGGER.warn("[mediafix] 归还视频帧异常（已忽略）", t);
        }
    }

    // =====================================================================
    // 无缝换链（ABR 换档 / 超长会话直链续期）
    // =====================================================================

    /**
     * 无缝换一路视频链。
     *
     * <p>做法与真实播放器"在分片边界切表示"等价，但更彻底：**音频链、声卡、时钟、状态机全程不动**。
     * 新源在后台打开、定位到当前播放位置、并至少解出一帧之后，才原子替换引用，旧的这才关闭。
     * 所以换档期间：声音不断、时钟不退、画面最多定格一瞬（等到新源出帧就切过去）。
     *
     * <p>（早先的做法是重建整个引擎，音频也要重开一次 —— 那就是"短暂重开"的来源。）
     */
    public void reloadVideoSource(URI newUri) {
        if (newUri == null || this.closed || this.videoSwapInProgress) return;
        URI cur = this.currentVideoUri;
        if (cur != null && cur.toString().equals(newUri.toString())) return;
        this.videoSwapInProgress = true;
        long pos = Math.max(0L, timeMs());
        Thread t = new Thread(() -> {
            FfmpegVideoSource fresh = null;
            try {
                MediaFix.LOGGER.info("[mediafix] 无缝换链（视频）开始 -> {} @ {}ms",
                        MediaFix.LOGGER.url(newUri), pos);
                fresh = new FfmpegVideoSource(newUri, this.headers);
                final FfmpegVideoSource probe = fresh;
                fresh.start();
                if (!awaitReady(() -> probe.isReady() || probe.isBroken(), 15000L)) {
                    MediaFix.LOGGER.warn("[mediafix] 换链放弃：新视频链 15 秒内未就绪");
                    return;
                }
                // 定位到"此刻正在播的位置"再等它出帧：这样切过去是连续的
                fresh.requestSeek(Math.max(0L, timeMs()));
                if (!awaitReady(() -> probe.framesProduced() > 0 || probe.isBroken(), 8000L)) {
                    MediaFix.LOGGER.warn("[mediafix] 换链放弃：新视频链 8 秒内未解出帧");
                    return;
                }
                if (this.closed) return;
                FfmpegVideoSource old = this.video;
                this.video = fresh;
                this.currentVideoUri = newUri;
                this.lastFrameWallMs = System.currentTimeMillis();
                // 新链要从头攒缓冲：给它一个回填期，别把"0KB"当成网络不行
                this.videoBufferHealthy = false;
                this.lastSourceSwapAt = System.currentTimeMillis();
                this.lastRebufferLogAt = 0;          // 不再让 ABR 记这一次"刚缓冲见底"
                this.diagResetBaselines = true;      // 计数器换了对象，基线要重取（否则帧率会打成负数）
                mediafix$diagForce();     // 立刻打一条状态，便于核对
                MediaFix.LOGGER.info("[mediafix] 无缝换链（视频）完成，旧链关闭（音频未受影响）");
                if (old != null) {
                    try {
                        old.close();
                    } catch (Throwable ignored) {
                    }
                }
                fresh = null;             // 已交接，别在 finally 里关掉
            } catch (Throwable t2) {
                MediaFix.LOGGER.warn("[mediafix] 视频换链异常", t2);
            } finally {
                if (fresh != null) {
                    try {
                        fresh.close();
                    } catch (Throwable ignored) {
                    }
                }
                this.videoSwapInProgress = false;
            }
        }, "mediafix-reload-video");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 无缝换一路音频链（直链过期续期时用）。
     * 旧源的环形缓冲里有 8 秒音频，新源只要在这段时间内接上，声音就完全不断。
     */
    public void reloadAudioSource(URI newUri) {
        if (newUri == null || this.closed || this.audio == null || this.audioSwapInProgress) return;
        URI cur = this.currentAudioUri;
        if (cur != null && cur.toString().equals(newUri.toString())) return;
        this.audioSwapInProgress = true;
        Thread t = new Thread(() -> {
            FfmpegAudioSource fresh = null;
            try {
                MediaFix.LOGGER.info("[mediafix] 无缝换链（音频）开始 -> {} @ {}ms",
                        MediaFix.LOGGER.url(newUri), Math.max(0L, timeMs()));
                int ch = this.sink.channels();
                int rate = this.sink.sampleRate();
                fresh = new FfmpegAudioSource(newUri, this.headers);
                final FfmpegAudioSource probe = fresh;
                if (!fresh.prepare()) {
                    MediaFix.LOGGER.warn("[mediafix] 换链放弃：新音频链打开失败");
                    return;
                }
                fresh.start(Math.max(1, ch), Math.max(8000, rate));
                fresh.requestSeek(Math.max(0L, timeMs()));
                // 等它攒出一点音频再换，避免中间出现空档
                if (!awaitReady(() -> probe.bufferedMs() > 400L || probe.isBroken(), 10000L)) {
                    MediaFix.LOGGER.warn("[mediafix] 换链放弃：新音频链 10 秒内未攒够缓冲");
                    return;
                }
                if (this.closed) return;
                FfmpegAudioSource old = this.audio;
                this.audio = fresh;
                this.currentAudioUri = newUri;
                MediaFix.LOGGER.info("[mediafix] 无缝换链（音频）完成，旧链关闭");
                if (old != null) {
                    try {
                        old.close();
                    } catch (Throwable ignored) {
                    }
                }
                fresh = null;
            } catch (Throwable t2) {
                MediaFix.LOGGER.warn("[mediafix] 音频换链异常", t2);
            } finally {
                if (fresh != null) {
                    try {
                        fresh.close();
                    } catch (Throwable ignored) {
                    }
                }
                this.audioSwapInProgress = false;
            }
        }, "mediafix-reload-audio");
        t.setDaemon(true);
        t.start();
    }

    /** 轮询等待条件成立（50ms 一次），超时返回 false。 */
    private boolean awaitReady(java.util.function.BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!this.closed && System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void mediafix$diagForce() {
        this.diagAt = 0;
    }

    // =====================================================================
    // 控制
    // =====================================================================

    public void pause(boolean paused) {
        if (this.wantPause == paused) return;
        this.wantPause = paused;
        if (this.audio != null) this.audio.setPaused(paused);
        if (this.video != null) this.video.setPaused(paused);
        this.sink.setPaused(paused);
        if (paused) {
            this.clock.pause();
            this.sink.flush();
            setState(State.PAUSED);
        } else {
            this.clock.start();
            setState(State.PLAYING);
        }
    }

    public void seek(long ms) {
        long target = Math.max(0L, ms);
        // 引擎还没 boot（比如 ABR 刚换完档就 seek）：先记下，等两个源就绪再落
        if (this.video == null) this.pendingSeekMs = target;

        /*
         * 原地 seek 直接忽略。
         *
         * 目标与当前进度差在容差内（默认 1.5 秒）= "回到当前位置"，用户完全感知不到，
         * 但代价是：重开两条 CDN 连接、flush 掉 2.5 秒音频缓冲、必然进一次 BUFFERING。
         * waterframes 的进度同步会频繁发这种 seek —— 实测日志里 时钟=53440ms 时 seek 到
         * 53450ms（差 10ms），把缓冲整个砸掉，紧接着就是"缓冲见底"和状态跳变。
         * 所以这里必须先于任何缓冲操作挡掉。
         */
        long cur = timeMs();
        if (this.video != null && cur > 2000L
                && Math.abs(target - cur) < dev.mediafix.config.SeekGuardConfig.noopToleranceMs) {
            long nowMs = System.currentTimeMillis();
            this.ignoredSeekCount++;
            if (nowMs - this.lastNoopSeekLogAt > 3000L) {
                this.lastNoopSeekLogAt = nowMs;
                MediaFix.LOGGER.info("[mediafix] 忽略一次原地 seek：目标 {}ms，当前 {}ms（差 {}ms，容差 {}ms）",
                        target, cur, target - cur, dev.mediafix.config.SeekGuardConfig.noopToleranceMs);
            }
            return;
        }
        // waterframes 的同步逻辑会在极短时间内重复发同一个目标（日志里 47500/48550/50700 都各出现两次）。
        // 每次 seek 都要 flush 两秒多的音频缓冲，重复执行会把缓冲反复砸掉，看起来就是一直卡。
        long now = System.currentTimeMillis();
        if (Math.abs(target - this.lastSeekTarget) < 250L && now - this.lastSeekAt < 800L) return;
        this.lastSeekTarget = target;
        this.lastSeekAt = now;

        // seek 风暴检测：短时间被反复 seek = 有人（通常是 waterframes 的进度纠偏）在跟我们打架，
        // 每次 seek 都会 flush 音频、重建解码/重采样，听感就是被切成一截一截的。
        if (now - this.seekBurstSince > 2000L) {
            this.seekBurstSince = now;
            this.seekBurstCount = 1;
        } else if (++this.seekBurstCount == 4) {
            MediaFix.LOGGER.warn("[mediafix] seek 风暴：2 秒内被反复 seek {} 次（目标 {}ms，时钟 {}ms，状态 {}）"
                            + " —— 说明进度纠偏在和我们互相追",
                    this.seekBurstCount, target, timeMs(), this.state);
        }
        /*
         * 小幅向前跳：不重开连接，直接在已预读的码流里"跳过"。
         *
         * waterframes 每 tick 拿服务端 tick 进度纠偏（阈值 2000ms）：我们因缓冲/暂停落后一点，
         * 它就来一次 seek。若按真 seek 处理 → 重开两条 CDN 连接 + 清空约 2.5 秒音频缓冲
         * → 又落后 → 它又纠偏，"seek → 卡顿 → 更落后 → 再 seek" 的死循环就是这么来的
         * （实测 6 分钟 75 次 seek）。向前跳本来就可以在已读进来的数据上丢一段解决：
         * 音频丢块、视频丢帧、时钟直接前移，连接与缓冲原封不动。
         */
        if (this.video != null && target > cur
                && (target - cur) <= Math.max(1000L, StreamConfig.catchUpMaxMs)) {
            this.clock.seekTo(target / 1000.0);
            this.sink.expectDiscontinuity();
            this.sink.flush();
            this.audioSkipUntilMs = target;
            this.lastFedPts = -1;
            this.video.requestSkipTo(target);
            this.catchUpCount++;
            MediaFix.LOGGER.info("[mediafix] 快速追帧 -> {}ms（向前 {}ms：在预读里丢弃，不重开连接）",
                    target, target - cur);
            return;
        }

        this.seekCount++;
        if (this.durationMs > 0) target = Math.min(target, Math.max(0L, this.durationMs - 50L));
        this.clock.seekTo(target / 1000.0);
        // 关键：seek 会 flush 掉约 2.5 秒缓冲，重填期间若时钟继续走，画面会窜到前面、
        // 等音频追上时再往回跳。所以先冻结时钟，缓冲回来再恢复（见 feedLoop）。
        this.clock.pause();
        this.sink.flush();
        if (this.video != null) this.video.requestSeek(target);
        if (this.audio != null) this.audio.requestSeek(target);
        this.lastFrameWallMs = System.currentTimeMillis();
        MediaFix.LOGGER.info("[mediafix] seek -> {}ms (冻结时钟，等待缓冲重填)", target);
        setState(this.wantPause ? State.PAUSED : State.BUFFERING);
    }

    /**
     * 是否是同一条视频链。
     *
     * <p>用于"播放器对象被重建时把现有引擎转交过去"：waterframes 在 PICTURE/VIDEO 模式切换
     * 或显示方块重初始化时会新建播放器对象，旧对象被 GC —— 以前这会导致我们把引擎关掉重建，
     * 代价是 2~4 秒重新缓冲 + 进度回到 0（日志里每 30 秒左右就来一次）。
     * 这里用**最初请求的两条链**比对（不能用 currentUri：ABR 换档会改它）。
     */
    public boolean matchesSource(URI videoUri, URI audioUri) {
        if (this.closed || videoUri == null || this.originVideoUri == null) return false;
        if (!sameStream(this.originVideoUri, videoUri)) return false;
        if (this.originAudioUri == null || audioUri == null) return true;
        return sameStream(this.originAudioUri, audioUri);
    }

    /**
     * 是否是同一条流。
     *
     * <p>不能用整串比较：B 站直链每次解析都带新的 {@code trid}/{@code deadline} 签名，
     * 同一个视频两次解析出来的 URL 字符串必然不同。真正标识一条流的是它的路径
     * （{@code /upgcxcode/53/77/34959917753/34959917753-1-30120.m4s}，含 cid 与流号）。
     */
    private static boolean sameStream(URI a, URI b) {
        if (a == null || b == null) return false;
        String pa = a.getPath();
        String pb = b.getPath();
        if (pa != null && pb != null && !pa.isEmpty() && pa.equals(pb)) {
            return true;
        }
        return a.toString().equals(b.toString());
    }

    public boolean isClosed() {
        return this.closed;
    }

    /** 最初请求的视频链（ABR 换档不改动）。 */
    public URI originVideoUri() {
        return this.originVideoUri;
    }

    /** 最初请求的音频链。 */
    public URI originAudioUri() {
        return this.originAudioUri;
    }

    /** 给 FFmpeg 的请求头（Referer/Cookie）。 */
    public String headers() {
        return this.headers;
    }

    /**
     * 手动刷新：重新解析直链，然后**无缝**换上两条新链（保持当前播放位置、声音不断）。
     *
     * <p>与自动续期走的是同一条路（{@link dev.mediafix.proxy.DashResolver#reResolveUrls} +
     * {@link #reloadVideoSource}/{@link #reloadAudioSource}），只是由玩家主动触发，
     * 用来救"画面卡住 / 黑屏 / 直链失效"这类只能靠换链恢复的情况。
     *
     * <p>会发起网络请求，**必须在后台线程调用**。
     *
     * @return false = 没有可用解析记录或重新解析失败（调用方给玩家提示即可）
     */
    public boolean refreshManually() {
        if (this.closed) return false;
        String[] fresh = dev.mediafix.proxy.DashResolver.reResolveUrls();
        if (fresh == null || fresh[0] == null || fresh[1] == null) {
            return false;
        }
        MediaFix.LOGGER.info("[mediafix] 手动刷新：拿到新鲜直链，开始无缝换链（视频+音频）");
        reloadVideoSource(URI.create(fresh[0]));
        reloadAudioSource(URI.create(fresh[1]));
        return true;
    }

    /** 真正执行的 seek 次数。 */
    public int seekCount() {
        return this.seekCount;
    }

    /** 被忽略的原地 seek 次数（每次省下一次"重开连接 + 清空音频缓冲"）。 */
    public int ignoredSeekCount() {
        return this.ignoredSeekCount;
    }

    /** 持有人数：同一个视频被多个播放器对象接管时会 >1。 */
    public void retain() {
        this.holders.incrementAndGet();
    }

    /** @return 递减后的持有人数，0 表示可以安全关闭。 */
    public int release() {
        return Math.max(0, this.holders.decrementAndGet());
    }

    public void setVolumePercent(int percent) {
        this.volumePercent = Math.max(0, Math.min(100, percent));
        this.sink.setGain(this.volumePercent / 100f);
    }

    public void setMuted(boolean muted) {
        this.sink.setMuted(muted);
    }

    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
    }

    public boolean isRepeat() {
        return this.repeat;
    }

    // =====================================================================
    // 状态查询（桥接层直接用这些回答 waterframes）
    // =====================================================================

    public State state() {
        return this.state;
    }

    private void setState(State next) {
        setState(next, null);
    }

    /**
     * 状态转换。带原因的日志是排查"卡住"的唯一可靠依据（之前的状态机在这上面吃过亏：
     * 门限设得比设备真实缓冲还大，于是永远卡在 BUFFERING 却看不出为什么）。
     */
    private void setState(State next, String reason) {
        State prev = this.state;
        if (prev == next) return;
        this.state = next;
        long now = System.currentTimeMillis();
        if (next == State.BUFFERING) this.bufferingSince = now;
        if (next == State.LOADING) this.loadingSince = now;
        if (now - this.lastStateLogAt < 400L) return;   // 抖动时别刷屏
        this.lastStateLogAt = now;
        MediaFix.LOGGER.info("[mediafix][状态] {} -> {}{} | 时钟={}ms 待播={}ms 音频环={}ms 提前量={}ms 门限={}ms 帧环={}",
                prev, next, reason == null ? "" : " (" + reason + ")",
                timeMs(), this.sink.pendingMs(),
                this.audio == null ? -1L : this.audio.bufferedMs(), audioLeadMs(), audioGateMs(),
                this.video == null ? -1 : this.video.queuedFrames());
    }

    private long lastStateLogAt;
    private long bufferingSince = System.currentTimeMillis();
    private long loadingSince = System.currentTimeMillis();

    /** 音频当前总提前量 = 设备行缓冲 + 解码环形缓冲。判断"够不够"必须看这个总量。 */
    private long audioLeadMs() {
        long lead = this.sink.pendingMs();
        if (this.audio != null) lead += this.audio.bufferedMs();
        return lead;
    }

    /**
     * 预缓冲门限。上限被"缓冲总容量"钳住：设备行缓冲实测只有约 800ms，
     * 若门限固定 1500ms 就永远满足不了 → 永远 LOADING/BUFFERING → 渲染被掐断。
     */
    private long audioGateMs() {
        long cap = this.sink.capacityMs() + (this.audio != null ? this.audio.capacityMs() : 0L);
        long target = FfmpegConfig.prebufferMs;
        if (cap > 0) target = Math.min(target, Math.max(250L, cap * 3L / 4L));
        return Math.max(200L, target);
    }

    /** 兜底：任何情况下都不许永久停在 BUFFERING / LOADING。 */
    private void watchdog() {
        /*
         * ★ 用户要求暂停时，兜底必须闭嘴。
         *
         * 以前这里无条件把 BUFFERING 拽回 PLAYING —— 而 feedLoop 在 wantPause 时
         * 每 20ms 照样调一次 watchdog，于是"按下停止按钮 → 引擎暂停 → 4 秒后兜底把它拉回播放"，
         * 表现就是【点了停止没效果】。暂停是用户的明确意图，任何自动恢复都不许覆盖它。
         */
        if (this.wantPause) return;
        long now = System.currentTimeMillis();
        if (this.state == State.BUFFERING && now - this.bufferingSince > 4000L) {
            this.clock.start();
            setState(State.PLAYING, "缓冲超时兜底");
        } else if (this.state == State.LOADING && now - this.loadingSince > 10000L
                && (this.video == null || this.video.isReady()) && durationMs() > 0) {
            this.clock.start();
            setState(State.PLAYING, "启动超时兜底");
        }
    }

    public boolean isPaused() {
        return this.state == State.PAUSED;
    }

    public boolean isPlaying() {
        return this.state == State.PLAYING || this.state == State.BUFFERING;
    }

    public boolean isBuffering() {
        return this.state == State.BUFFERING || this.state == State.LOADING;
    }

    public boolean isLoading() {
        return this.state == State.LOADING;
    }

    public boolean isEnded() {
        if (this.audio != null && !this.audio.isEnded()) return false;
        if (this.video != null && !this.video.isEnded()) return false;
        return this.audio != null || this.video != null;
    }

    public boolean isBroken() {
        if (this.state == State.ERROR) return true;
        if (this.video != null && this.video.isBroken()) return true;
        return this.video == null && this.audio == null;
    }

    public boolean isLive() {
        return this.durationMs <= 0L;
    }

    public long timeMs() {
        long t = this.clock.timeMs();
        if (t < 0) t = 0;
        if (this.durationMs > 0 && t > this.durationMs) t = this.durationMs;
        return t;
    }

    /**
     * 时长：实时读取两个源（谁先探测出来用谁）。
     * 不能用构造时缓存的值——视频源是异步打开的，缓存会让 waterframes 拿到 0 并永久锁死进度条。
     */
    public long durationMs() {
        long v = this.video != null ? this.video.durationMs() : -1L;
        if (v > 0) return v;
        long a = this.audio != null ? this.audio.durationMs() : -1L;
        if (a > 0) return a;
        return this.durationMs;
    }

    /**
     * 是否"可以开始渲染/上报时长"。
     * waterframes 一旦认为播放器就绪，就会固定一次 tickMax（进度条总长）且不再重算，
     * 所以这里必须等到视频源真的打开、时长已知才说就绪，否则进度条会永久停在 0。
     */
    public boolean readyForDisplay() {
        if (isBroken()) return false;
        if (this.video != null && !this.video.isReady()) return false;
        if (this.audio != null && !this.audio.isReady() && !this.audio.isBroken()) return false;
        if (durationMs() <= 0) return false;
        // 刻意不看 audioPrebuffered()：预缓冲只决定"状态"，绝不能决定"能不能渲染"。
        // 曾经这里返回 false 导致 waterframes 不再调 preRender → 渲染线程不再取帧 →
        // 帧环被 4 帧占满 → 解码线程阻塞 → 画面永久冻结。
        return true;
    }

    public int videoWidth() {
        return this.video != null ? this.video.width() : 1;
    }

    public int videoHeight() {
        return this.video != null ? this.video.height() : 1;
    }

    public boolean hasVideo() {
        return this.video != null;
    }

    public boolean hasAudio() {
        return this.audio != null;
    }

    public int channels() {
        return this.sink.channels();
    }

    /** 音频链摘要（给 /mediafix audio 用）：源编码/声道/采样率 → 输出声道/采样率。 */
    public String audioSummary() {
        FfmpegAudioSource a = this.audio;
        if (a == null) {
            return "无音频链";
        }
        return a.sourceCodecName() + " " + a.sourceChannels() + "ch " + a.sourceSampleRate()
                + "Hz -> 输出 " + this.sink.channels() + "ch " + this.sink.sampleRate() + "Hz";
    }

    public int sampleRate() {
        return this.sink.sampleRate();
    }

    public int volumePercent() {
        return this.volumePercent;
    }

    public boolean isMuted() {
        return this.sink.isMuted();
    }

    public String lastError() {
        if (this.video != null && this.video.isBroken()) return this.video.lastError();
        return this.lastError;
    }

    // =====================================================================
    // 内部
    // =====================================================================

    /** 每 2 秒打一条状态，黑屏时用来定位卡在哪一步。 */
    private void mediafix$diag() {
        long now = System.currentTimeMillis();
        if (now - this.diagAt < 2000L) return;
        this.diagAt = now;
        abrTick();
        refreshLinkIfExpiring();
        if (!FfmpegConfig.verbose && this.diagCount++ > 400) return;   // 非 verbose 打约 13 分钟
        MediaFix.LOGGER.diag("[mediafix][引擎] 状态={} 时钟={}ms 时长={}ms 暂停意图={} 视频:就绪={} 坏={} 解出={}帧 已显示={}帧 归还入={} 已归还={} 丢帧={} 帧队列={} 预读={}KB 末帧PTS={} 音频:就绪={} 待播={}ms 音频环={}ms 门限={}ms 提前量={}ms",
                this.state, timeMs(), durationMs(), this.wantPause,
                this.video != null && this.video.isReady(), this.video != null && this.video.isBroken(),
                this.video != null ? this.video.framesProduced() : -1,
                this.video != null ? this.video.framesPresented() : -1,
                this.releasesIn,
                this.video != null ? this.video.framesReleased() : -1,
                this.video != null ? this.video.framesDropped() : -1,
                this.video != null ? this.video.queuedFrames() : -1,
                this.video != null ? this.video.bufferedBytes() / 1024 : -1,
                this.video != null ? this.video.lastPresentedPtsMs() : -1L,
                this.audio != null && this.audio.isReady(),
                this.sink.pendingMs(),
                this.audio == null ? -1L : this.audio.bufferedMs(),
                audioGateMs(),
                audioLeadMs());
        long[] st = this.video == null ? null : this.video.takeStageTimings();
        long frames = st == null || st[3] <= 0 ? 1 : st[3];
        long calls = this.video == null ? 0L : this.video.acquireCalls();
        long shown = this.video == null ? 0L : this.video.framesPresented();
        if (this.diagResetBaselines) {
            // 换链换了对象，旧基线没意义：本周期只重置，不出数
            this.diagResetBaselines = false;
            this.diagLastCalls = calls;
            this.diagLastShown = shown;
            this.diagLastAt = now;
            return;
        }
        long windowMs = Math.max(1L, now - this.diagLastAt);
        long callsPerSec = (calls - this.diagLastCalls) * 1000L / windowMs;
        long shownPerSec = (shown - this.diagLastShown) * 1000L / windowMs;
        this.diagLastCalls = calls;
        this.diagLastShown = shown;
        this.diagLastAt = now;
        MediaFix.LOGGER.diag("[mediafix][帧率] 取帧调用={}/秒 实际显示={}/秒 {}",
                callsPerSec, shownPerSec,
                callsPerSec < 40 ? "← 渲染线程根本没在频繁调用（画面没在渲染？）"
                        : shownPerSec < callsPerSec / 2 ? "← 调用了但我没给帧（看[取帧]原因）" : "← 正常");
        MediaFix.LOGGER.diag("[mediafix][内存] 解码帧缓冲={}MB({}帧) 压缩预读={}MB 音频环={}MB | 本机原生内存合计≈{}MB",
                mb(this.video == null ? 0L : this.video.ringBytesTotal()),
                this.video == null ? 0 : this.video.ringSlotCount(),
                mb(this.video == null ? 0L : this.video.bufferedBytes()),
                mb(this.audio == null ? 0L : this.audio.ringBytesTotal()),
                mb((this.video == null ? 0L : this.video.ringBytesTotal() + this.video.bufferedBytes())
                        + (this.audio == null ? 0L : this.audio.ringBytesTotal())));
        MediaFix.LOGGER.diag("[mediafix][时钟] 速率={} 残差={}ms | 视频与时钟差={}ms",
                String.format("%.3f", this.clock.speed()), this.clock.residualMs(),
                this.video == null ? 0L : this.video.lastPresentedPtsMs() - timeMs());
        MediaFix.LOGGER.diag("[mediafix][耗时] 解码={}ms 搬运={}ms 转换={}ms 帧数={} → 每帧 {}+{}+{}={}ms | 声卡欠载={} 截断={} 爆音={} 去咔={} 重采样不一致={}",
                st == null ? -1 : st[0] / Math.max(1, st[3]), st == null ? -1 : st[1] / Math.max(1, st[3]),
                st == null ? -1 : st[2] / Math.max(1, st[3]), frames,
                st == null ? -1 : st[0] / frames, st == null ? -1 : st[1] / frames,
                st == null ? -1 : st[2] / frames, st == null ? -1 : (st[0] + st[1] + st[2]) / frames,
                this.sink.underruns(), this.sink.shortWrites(), this.sink.seams(), this.sink.declicks(),
                this.audio == null ? -1L : this.audio.resampleMismatch());
    }

    private static long mb(long bytes) {
        return bytes / 1048576L;
    }

    /** 攒批目标：约 200ms 一块（21ms 的音块 × 9 块）。 */
    private static final int BATCH_TARGET_BYTES = 48000 * 2 * 2 / 5;
    private static final int MAX_BATCH_CHUNKS = 16;
    private byte[] batchBuf = new byte[BATCH_TARGET_BYTES + 65536];
    /** 攒批时多取出来、但时间戳不相接的那一块，留到下一轮写。 */
    private FfmpegAudioSource.Chunk pendingChunk;

    /** 把一块 PCM 追加到批缓冲，返回新的累计长度。 */
    private int appendChunk(int offset, FfmpegAudioSource.Chunk chunk) {
        int need = offset + chunk.length;
        if (need > this.batchBuf.length) {
            byte[] bigger = new byte[Math.max(need, this.batchBuf.length * 2)];
            System.arraycopy(this.batchBuf, 0, bigger, 0, offset);
            this.batchBuf = bigger;
        }
        ByteBuffer src = chunk.buf;
        src.clear();
        src.limit(chunk.length);
        src.get(this.batchBuf, offset, chunk.length);
        return need;
    }

    private float masterGainCache = 1.0f;
    private long lastGainAt;
    private long lastFedPts = -1L;
    private int lastFedSlot = -1;
    private long lastOrderLogAt;
    private long diagLastCalls;
    private long diagLastShown;
    private long diagLastAt = System.currentTimeMillis();

    /** 解码持续跟不上的次数（用于给出"降档"建议）。 */
    private int starveCount;
    private long starveFirstAt;

    /** ABR 换档前记录的目标位置，等源就绪后应用。 */
    private volatile long pendingSeekMs;

    /**
     * ABR 心跳（跟随两秒一次的诊断一起跑）：喂带宽采样 → 让控制器决策 → 需要就换档。
     * 只在 {@code maxQn == -1}（/mediafix-stream quality auto）时工作；手动指定档位时绝不干预。
     */
    private void abrTick() {
        if (StreamConfig.maxQn >= 0 || this.closed || this.rebufferHold) return;
        FfmpegVideoSource v = this.video;
        if (v == null) return;
        long rate = v.readBytesPerSec();
        if (rate > 0 && this.state == State.PLAYING) AbrController.noteThroughput(rate * 8);

        long highWater = (long) FfmpegConfig.videoReadAheadMb * 1024L * 1024L * 2 / 3;
        boolean justRebuffered = System.currentTimeMillis() - this.lastRebufferLogAt < 30_000L;
        int dir = AbrController.decide(v.bufferedBytes(), highWater, justRebuffered);
        if (dir == 0) return;

        AbrLadder.Candidate next = dir < 0 ? AbrLadder.lower() : AbrLadder.higher();
        if (next == null) return;
        AbrLadder.setCurrentIndex(AbrLadder.currentIndex() + dir);
        MediaFix.LOGGER.info("[mediafix] ABR 换档（仅视频）-> {}（{}kbps）", next.label(), next.bandwidthBps() / 1000);
        /*
         * 只换视频链。音频【不参与自适应】：它始终是解析时取得的最高音质那一条，
         * 换档期间音频链、声卡、时钟全程不动，所以不会有"短暂重开"，声音也不会跟着降级。
         */
        reloadVideoSource(URI.create(next.url()));
    }

    /** 续期检查限速：别在解析失败时反复重试。 */
    private volatile long lastRefreshAt;

    /**
     * 超长会话保活：B 站直链带 {@code deadline}（通常约两小时）。在到期前主动重新解析并换链，
     * 否则到期后拉流会 403，表现为画面/声音突然停住。
     *
     * <p>换链是无缝的（见 {@link #reloadVideoSource}）：新链就绪前旧链继续播，音频链基本不受影响。
     */
    private void refreshLinkIfExpiring() {
        if (this.closed || this.video == null) return;
        URI cur = this.currentVideoUri;
        if (cur == null) return;
        long deadline = dev.mediafix.proxy.DashResolver.urlDeadlineMs(cur.toString());
        if (deadline <= 0) return;
        long leftMs = deadline - System.currentTimeMillis();
        if (leftMs > 300_000L) return;                       // 还剩 5 分钟以上，先不动
        long now = System.currentTimeMillis();
        if (now - this.lastRefreshAt < 60_000L) return;      // 失败后至少隔一分钟再试
        this.lastRefreshAt = now;
        MediaFix.LOGGER.info("[mediafix] 直链将在 {} 秒后过期，主动续期换链", leftMs / 1000);
        String[] fresh = dev.mediafix.proxy.DashResolver.reResolveUrls();
        if (fresh == null) {
            MediaFix.LOGGER.warn("[mediafix] 直链续期没成功，播放可能在到期后中断");
            return;
        }
        reloadVideoSource(URI.create(fresh[0]));
        // 音频只在这时候被动过，而且换的仍是"最高音质"那一条（直链过期只能重新拿 URL）
        reloadAudioSource(URI.create(fresh[1]));
    }

    private long diagAt;
    private long seekBurstSince;
    private int seekBurstCount;
    private long lastSeekTarget = -1L;
    private long lastSeekAt;
    private long lastUnderrunAt;
    private int diagCount;

    /** 音频是否已缓冲够（短片、直播不苛求）。 */
    private boolean audioPrebuffered() {
        if (this.audio == null) return true;
        if (this.audio.isEnded()) return true;
        long dur = durationMs();
        if (dur > 0 && dur < FfmpegConfig.prebufferMs * 2L) return true;   // 内容本身比预缓冲还短
        return audioLeadMs() >= audioGateMs();
    }

    private void promoteIfReady() {
        if (this.state != State.LOADING) return;
        boolean videoReady = this.video == null || this.video.isReady();
        boolean audioReady = this.audio == null || this.audio.isReady();
        boolean videoDead = this.video != null && this.video.isBroken();
        boolean audioDead = this.audio != null && this.audio.isBroken();

        if (videoDead && (this.audio == null || audioDead)) {
            setState(State.ERROR);
            return;
        }
        if ((videoReady && audioReady) || (videoDead && audioReady) || (videoReady && audioDead)) {
            // 先缓冲够再开播：水帧那边也会因此多显示一会儿"加载中"，而不是一上来就断续
            if (!audioPrebuffered()) return;
            MediaFix.LOGGER.info("[mediafix] 预缓冲完成: 音频 {}ms (目标 {}ms)",
                    this.sink.pendingMs(), FfmpegConfig.prebufferMs);
            this.clock.start();
            setState(this.wantPause ? State.PAUSED : State.PLAYING);
            this.lastFrameWallMs = System.currentTimeMillis();
        }
    }

    private void checkEnded() {
        if (this.state == State.ERROR || this.closed) return;
        if (!isEnded()) return;
        // 保护：某个源可能提前报 EOF（例如 seek 落到片尾、或音频轨先读完）。
        // 时长还没走到头就不能认定为"播放结束"，否则循环播放会直接 seek(0) 重来 ——
        // 表现就是"每次播放都从头开始"。
        long dur = durationMs();
        if (dur > 0 && timeMs() < dur - 2000L) return;

        if (this.repeat) {
            MediaFix.LOGGER.info("[mediafix] 播放结束，循环回起点");
            seek(0L);
            pause(false);
            return;
        }
        if (this.state != State.ENDED) {
            setState(State.ENDED);
            MediaFix.LOGGER.info("[mediafix] 播放结束");
        }
    }

    @Override
    public synchronized void close() {
        if (this.closed) return;
        this.closed = true;

        // 启动线程可能仍在 avformat_open_input 里打开音频（那条路径在 boot 线程上）。
        // 它没退出前释放原生上下文就是 use-after-free —— 宁可泄漏也不能崩。
        Thread boot = this.bootThread;
        if (boot != null && boot != Thread.currentThread() && boot.isAlive()) {
            try {
                boot.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (boot.isAlive()) {
                MediaFix.LOGGER.warn("[mediafix] 引擎启动线程未在 5 秒内退出，跳过原生资源释放以避免崩溃");
                this.sink.close();   // 纯 Java 的输出可以安全关闭
                return;
            }
        }

        FfmpegAudioSource a = this.audio;
        this.audio = null;
        if (a != null) {
            try {
                a.close();
            } catch (Throwable ignored) {
            }
        }
        FfmpegVideoSource v = this.video;
        this.video = null;
        if (v != null) {
            try {
                v.close();
            } catch (Throwable ignored) {
            }
        }
        this.sink.close();
        for (Thread t : new Thread[]{this.feeder, this.bootThread}) {
            if (t != null && t != Thread.currentThread()) {
                try {
                    t.join(800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        MediaFix.LOGGER.info("[mediafix] 引擎已关闭");
    }
}
