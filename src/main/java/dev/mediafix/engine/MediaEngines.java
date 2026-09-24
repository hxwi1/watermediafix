package dev.mediafix.engine;

import dev.mediafix.MediaFix;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "播放器实例 → 自研引擎"的注册表，以及引擎输入的推导规则。
 *
 * <p>用弱引用表：watermedia 的播放器由 waterframes 的方块实体掌管，方块被拆/区块卸载时
 * 播放器对象会被丢弃，我们不该持有它。
 */
public final class MediaEngines {

    private static final Map<Object, MediaEngine> ENGINES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /*
     * 实例残留清理。
     *
     * 光用 WeakHashMap 是不够的：键（播放器）被 GC 掉时表项会静默消失，
     * 我们连对应的引擎对象都拿不到，于是没人去关它 —— 它的线程会继续下载、继续解码，
     * 还会占着声卡，一直跑到文件结尾。这在"拆掉方块/卸载区块"时就会发生。
     * 这里挂一个 ReferenceQueue：键被回收时能拿到引用对象，从而关掉对应引擎。
     */
    /** 最近创建的引擎（供 /mediafix audio 等只读诊断查看当前音频链）。 */
    private static volatile MediaEngine lastCreated;

    /*
     * 孤儿引擎暂存区。
     *
     * waterframes 会频繁重建播放器对象（PICTURE/VIDEO 模式切换、显示方块重初始化），
     * 旧对象被 GC 后如果我们立刻关引擎，几秒后新播放器又得从头缓冲一遍、位置还回到 0 ——
     * 这就是"视频流不稳定"里最显眼的一条。
     * 所以播放器被回收后先让引擎当"孤儿"活一段时间：同一个视频的新播放器来了就直接接管；
     * 到点还没人来才真正关掉（线程与原生上下文终究不能常驻）。
     */
    private static final long ORPHAN_GRACE_MS = 20_000L;

    private static final java.util.List<Orphan> ORPHANS = new java.util.ArrayList<>();

    private record Orphan(MediaEngine engine, long deadline) {
    }

    /** 把引擎放进暂存区等接管。 */
    private static void orphan(MediaEngine engine) {
        synchronized (ORPHANS) {
            ORPHANS.add(new Orphan(engine, System.currentTimeMillis() + ORPHAN_GRACE_MS));
        }
        MediaFix.LOGGER.info("[mediafix] 播放器被回收，引擎保留 {} 秒等待接管（同一视频的新播放器可无缝续用）",
                ORPHAN_GRACE_MS / 1000L);
    }

    /** 关掉超时没被接管的孤儿引擎。 */
    private static void sweepOrphans() {
        java.util.List<MediaEngine> expired = null;
        synchronized (ORPHANS) {
            long now = System.currentTimeMillis();
            for (java.util.Iterator<Orphan> it = ORPHANS.iterator(); it.hasNext(); ) {
                Orphan o = it.next();
                if (o.deadline() <= now) {
                    it.remove();
                    if (expired == null) expired = new java.util.ArrayList<>();
                    expired.add(o.engine());
                }
            }
        }
        if (expired == null) return;
        for (MediaEngine e : expired) {
            MediaFix.LOGGER.info("[mediafix] 无人接管，关闭孤儿引擎（释放线程与原生内存）");
            try {
                e.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static MediaEngine lastCreated() {
        return lastCreated;
    }

    private static final java.lang.ref.ReferenceQueue<Object> REAPED = new java.lang.ref.ReferenceQueue<>();
    private static final java.util.Queue<PlayerRef> LIVE_REFS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static final class PlayerRef extends java.lang.ref.WeakReference<Object> {
        final MediaEngine engine;

        PlayerRef(Object player, MediaEngine engine) {
            super(player, REAPED);
            this.engine = engine;
        }
    }

    private static void startReaper() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    sweepOrphans();
                    java.lang.ref.Reference<?> r = REAPED.remove(5000);
                    if (r instanceof PlayerRef pr) {
                        LIVE_REFS.remove(pr);
                        // 还有别的播放器持有同一个引擎（同源转交）时不能关，否则那一路会突然断掉
                        if (pr.engine.release() > 0) {
                            MediaFix.LOGGER.info("[mediafix] 播放器被回收，但引擎仍被其它播放器持有，保持播放");
                            continue;
                        }
                        // 不立刻关：留一段时间给"同一个视频的新播放器"接管（见 ORPHAN_GRACE_MS）
                        pr.engine.release();
                        orphan(pr.engine);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable ignored) {
                }
            }
        }, "mediafix-engine-reaper");
        t.setDaemon(true);
        t.start();
    }

    private MediaEngines() {
    }

    /**
     * MC 主音量系数。
     *
     * <p>注意：waterframes 自己在 {@code Display.rangedVol()} 里也有一处主音量乘算，由它的配置项
     * {@code masterVolume}（默认 false）控制。若该项为 true，传进 {@code setVolume} 的值已经乘过，
     * 这里就返回 1 避免重复衰减。
     */
    public static float masterGain() {
        try {
            if (waterframesUsesMasterVolume()) return 1.0f;
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.options == null) return 1.0f;
            return mc.options.getSoundSourceVolume(net.minecraft.sounds.SoundSource.MASTER);
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    /** 反射读 waterframes 的 masterVolume 配置（缺失即视为 false），避免编译期硬依赖。 */
    private static boolean waterframesUsesMasterVolume() {
        try {
            Class<?> c = Class.forName("me.srrapero720.waterframes.DisplaysConfig");
            Object v = c.getMethod("useMasterVolume").invoke(null);
            return Boolean.TRUE.equals(v);
        } catch (Throwable t) {
            return false;
        }
    }

    public static MediaEngine of(Object player) {
        return player == null ? null : ENGINES.get(player);
    }

    /** 找出同一个视频、仍然存活的引擎（用于播放器对象被重建时转交）。 */
    private static MediaEngine findReusable(URI videoUri, URI audioUri) {
        java.util.List<MediaEngine> vals = new java.util.ArrayList<>();
        synchronized (ENGINES) {
            vals.addAll(ENGINES.values());
        }
        synchronized (ORPHANS) {
            for (Orphan o : ORPHANS) vals.add(o.engine());
        }
        for (MediaEngine e : vals) {
            if (e != null && !e.isClosed() && e.matchesSource(videoUri, audioUri)) {
                synchronized (ORPHANS) {
                    ORPHANS.removeIf(o -> o.engine() == e);
                }
                return e;
            }
        }
        return null;
    }

    /**
     * 引擎当前播放位置（毫秒）；未被引擎接管返回 -1。
     *
     * <p>给进度守卫用：引擎接管后，waterframes 那个 VLC 播放器只是个壳，
     * 它的 {@code getTime()} 在暂停/缓冲时可能是 0 —— 用它判断"是否陈旧同步"会失灵
     * （实测就是因此漏放了 seekTo(0)，视频跳回开头）。
     */
    public static long enginePositionOf(Object player) {
        MediaEngine e = of(player);
        return e == null ? -1L : e.timeMs();
    }

    /** 引擎已知的时长（毫秒）；未被引擎接管或时长未知返回 -1。 */
    public static long engineDurationOf(Object player) {
        MediaEngine e = of(player);
        return e == null ? -1L : e.durationMs();
    }

    /** 为播放器创建并启动引擎（已存在则先关掉旧的）。 */
    public static MediaEngine create(Object player, URI videoUri, URI audioUri, String headers) {
        return create(player, videoUri, audioUri, headers, false);
    }

    /**
     * @param live 本次是否是 B 站直播（由解析阶段经 {@code DashHandoff} 传进来）。
     *             直播模式下引擎会忽略一切 seek、不参与 ABR、断流自动重连，
     *             并用系统时间锚定时间轴（可读位置 + 实时延迟）。
     */
    public static MediaEngine create(Object player, URI videoUri, URI audioUri, String headers, boolean live) {
        if (player == null || videoUri == null) return null;

        /*
         * 同一个视频的引擎直接转交，不要重建。
         *
         * waterframes 在 PICTURE/VIDEO 模式切换、显示方块重初始化时会新建播放器对象，
         * 旧对象随即被 GC。以前这里只会关掉旧引擎再建新的 —— 每次代价是 2~4 秒重新缓冲
         * 加进度回到 0（实测日志里约每 30 秒一次，就是"视频流不稳定"的一大来源）。
         * 两条链一致时把现有引擎挂到新播放器上即可，缓冲、时钟、声卡全部续用。
         */
        MediaEngine reusable = findReusable(videoUri, audioUri);
        if (reusable != null) {
            ENGINES.put(player, reusable);
            LIVE_REFS.add(new PlayerRef(player, reusable));
            reusable.retain();
            lastCreated = reusable;
            MediaFix.LOGGER.info("[mediafix] 同一个视频，引擎转交给新播放器（省去重新缓冲）: status={} pos={}ms",
                    reusable.state(), reusable.timeMs());
            return reusable;
        }

        // 确认要建新引擎 = 换到别的视频了，旧孤儿不可能再被接管，立刻释放（别占带宽）
        dropOrphans("换到其它视频");
        close(player);
        try {
            MediaEngine engine = new MediaEngine(videoUri, audioUri, headers, live);
            lastCreated = engine;
            ENGINES.put(player, engine);
            if (LIVE_REFS.isEmpty()) startReaper();
            LIVE_REFS.add(new PlayerRef(player, engine));
            engine.start();
            MediaFix.LOGGER.info("[mediafix] 引擎接管播放器: video={} audio={}",
                    MediaFix.LOGGER.url(videoUri), MediaFix.LOGGER.url(audioUri));
            return engine;
        } catch (Throwable t) {
            MediaFix.LOGGER.error("[mediafix] 创建引擎失败", t);
            return null;
        }
    }

    /**
     * 关闭所有引擎（退出世界时调用）。
     * 为什么必须主动关：引擎线程与原生上下文如果一直留到 JVM 关闭阶段，
     * MC 那边在拆 GL/JIT/类加载器，我们这边还在跑 FFmpeg，崩溃就落在这里了。
     */
    public static void closeAll() {
        java.util.List<Object> keys;
        synchronized (ENGINES) {
            keys = new java.util.ArrayList<>(ENGINES.keySet());
        }
        for (Object k : keys) forceClose(k);
        synchronized (ORPHANS) {
            for (Orphan o : ORPHANS) {
                try {
                    o.engine().close();
                } catch (Throwable ignored) {
                }
            }
            ORPHANS.clear();
        }
        if (!keys.isEmpty()) MediaFix.LOGGER.info("[mediafix] 已关闭 {} 个播放引擎（退出世界）", keys.size());
    }

    public static void close(Object player) {
        MediaEngine old = ENGINES.remove(player);
        if (old == null) return;
        // 还有别的播放器持有它（同源转交）就只减引用，别把还在播的那路一起关掉
        if (old.release() > 0) {
            MediaFix.LOGGER.info("[mediafix] 播放器释放引擎，但仍有其它播放器在用它，保持播放");
            return;
        }
        /*
         * 不立刻关：走与 GC 回收同一条路 —— 先当孤儿留 20 秒。
         * VideoPlayer.release() 也会走到这里（waterframes 的 switchVideoMode 每次都新建播放器），
         * 立刻关掉的话，几秒后同一视频的新播放器又得从头缓冲、位置回 0。
         */
        orphan(old);
    }

    /**
     * 强制重建"最近这个播放器"的引擎：整个重开（换链都救不回来时的最后手段）。
     *
     * <p>会丢掉解码缓冲，位置尽量保留。返回 null 表示找不到对应播放器。
     */
    public static MediaEngine rebuildLast() {
        MediaEngine e = lastCreated;
        if (e == null) return null;
        Object player = null;
        synchronized (ENGINES) {
            for (var en : ENGINES.entrySet()) {
                if (en.getValue() == e) {
                    player = en.getKey();
                    break;
                }
            }
        }
        if (player == null) return null;
        URI v = e.originVideoUri();
        URI a = e.originAudioUri();
        String headers = e.headers();
        long pos = Math.max(0L, e.timeMs());
        // 直接丢掉旧引擎（不走进孤儿宽限：重建的目标就是彻底换一个新的）
        ENGINES.remove(player);
        e.release();
        try {
            e.close();
        } catch (Throwable ignored) {
        }
        MediaEngine fresh = create(player, v, a, headers, e.liveSession());
        // 直播没有可跳位置（seek 会被忽略），也就不需要恢复位置了
        if (fresh != null && pos > 0 && !e.liveSession()) {
            fresh.seek(pos);   // 引擎还没 boot 也不要紧：它会记成挂起位置，就绪后自己落
        }
        return fresh;
    }

    /** 关掉所有孤儿（换到别的视频时调用：旧下载已经没用了，别占带宽）。 */
    private static void dropOrphans(String why) {
        java.util.List<MediaEngine> victims;
        synchronized (ORPHANS) {
            if (ORPHANS.isEmpty()) return;
            victims = new java.util.ArrayList<>();
            for (Orphan o : ORPHANS) victims.add(o.engine());
            ORPHANS.clear();
        }
        MediaFix.LOGGER.info("[mediafix] {}：关闭 {} 个无人接管的旧引擎", why, victims.size());
        for (MediaEngine e : victims) {
            try {
                e.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 无条件关闭（退出世界用，不看引用计数）。 */
    private static void forceClose(Object player) {
        MediaEngine old = ENGINES.remove(player);
        if (old == null) return;
        try {
            old.close();
        } catch (Throwable t) {
            MediaFix.LOGGER.warn("[mediafix] 关闭引擎异常", t);
        }
    }

    /**
     * 推导音频链：
     * <ol>
     *   <li>调用方给的（流式 DASH 的音频直链 / 下载模式 Result.audioUrl）；</li>
     *   <li>本地文件：同目录的 {@code xxx_v.mp4} → {@code xxx_a.m4a}（存在才用）；</li>
     *   <li>都没有 → 用同一个 URI（音视频在同一文件里时，音频源自己挑音轨）。</li>
     * </ol>
     */
    public static URI resolveAudioUri(URI videoUri, URI provided) {
        if (provided != null) return provided;
        try {
            Path sibling = siblingAudio(videoUri);
            if (sibling != null && Files.exists(sibling)) {
                return sibling.toUri();
            }
        } catch (Throwable ignored) {
        }
        return videoUri;
    }

    private static Path siblingAudio(URI uri) {
        String path = "file".equalsIgnoreCase(uri.getScheme()) ? uri.getPath() : uri.getPath();
        if (path == null) return null;
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith("_v.mp4")) return null;
        return Path.of(path.substring(0, path.length() - "_v.mp4".length()) + "_a.m4a");
    }
}
