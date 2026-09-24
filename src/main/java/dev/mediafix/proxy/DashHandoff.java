package dev.mediafix.proxy;

/**
 * DASH 流式直连的"交接棒"：解析阶段（DashResolver，跑在某个播放器的启动线程里）
 * 把选出的视频/音频直链放下，播放阶段（BasePlayerFfmpegMixin，同一线程的后续调用）
 * 取走视频直链交给 FFmpeg。
 *
 * <p>为什么用 ThreadLocal：每个播放器的 start() 都在自己的线程里跑 patch → play，
 * 多个屏幕同时加载时用静态变量会互相覆盖（这正是上一版 DashAudioBridge 的隐患）。
 * 线程随播放器启动而创建、结束即回收，不会有残留。
 */
public final class DashHandoff {

    private static final ThreadLocal<String> VIDEO = new ThreadLocal<>();
    private static final ThreadLocal<String> AUDIO = new ThreadLocal<>();
    /** NetworkAPI.patch 返回的 Result.audioUrl（下载模式下是本地 m4a，独立于流式交接棒）。 */
    private static final ThreadLocal<String> RESULT_AUDIO = new ThreadLocal<>();

    /**
     * 一个流式媒体的两条链。
     *
     * <p>{@code live} 必须一起存：解析跑在 ImageFetch 线程、引擎却在播放器线程创建，
     * 线程内的交接棒传不过去，直播标记只能靠这张按主 URI 索引的全局表带到播放阶段。
     */
    public record Pair(String video, String audio, long at, boolean live) {
    }

    /**
     * 主 URI → (视频链, 音频链)。
     *
     * <p>为什么不能只用 ThreadLocal：流式解析发生在 ImageFetch 的工作线程，而真正接管播放发生在
     * 播放器自己的线程；而且 watermedia 会缓存 Result（10 秒），接管时可能命中缓存、根本不再调用
     * 我们的 patch —— 那样 ThreadLocal 就是空的，会把"音频链"当成视频链去解（表现为"没有视频轨"→
     * 界面显示"出现错误"）。所以这里再按主 URI 存一份，任何线程、任何时刻都能查到。
     */
    private static final java.util.Map<String, Pair> BY_MAIN = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TTL_MS = 30 * 60 * 1000L;

    private DashHandoff() {
    }

    /** 最近一次解析出的视频链（不消费，供续期检查使用）。 */
    private static volatile String lastVideo;

    /** 最近一次解析出的视频链；没解析过返回 null。 */
    public static String lastVideoUrl() {
        return lastVideo;
    }

    /** 记录本次解析得到的直链（线程内 + 按主 URI 全局各存一份）。 */
    public static void set(String videoUrl, String audioUrl) {
        set(videoUrl, audioUrl, false);
    }

    /** 记录本次解析得到的直链（线程内 + 按主 URI 全局各存一份），并带上直播标记。 */
    public static void set(String videoUrl, String audioUrl, boolean live) {
        if (videoUrl != null && !videoUrl.isBlank()) lastVideo = videoUrl;
        VIDEO.set(videoUrl);
        AUDIO.set(audioUrl);
        if (live) LIVE.set(true);
        long now = System.currentTimeMillis();
        // 主 URI 就是播放器实际会拿到的那个：流式模式下是音频链，下载模式下是本地视频文件
        if (audioUrl != null && !audioUrl.isBlank()) {
            BY_MAIN.put(audioUrl, new Pair(videoUrl, audioUrl, now, live));
        }
        if (videoUrl != null && !videoUrl.isBlank()) {
            BY_MAIN.put(videoUrl, new Pair(videoUrl, audioUrl, now, live));
        }
        purgeExpired();
    }

    /** 按播放器拿到的 URI 反查两条链；查不到返回 null。 */
    public static Pair lookup(String mainUri) {
        if (mainUri == null) return null;
        Pair p = BY_MAIN.get(mainUri);
        if (p == null) return null;
        if (System.currentTimeMillis() - p.at() > TTL_MS) {
            BY_MAIN.remove(mainUri);
            return null;
        }
        return p;
    }

    private static void purgeExpired() {
        long now = System.currentTimeMillis();
        BY_MAIN.entrySet().removeIf(e -> now - e.getValue().at() > TTL_MS);
    }

    /** 取走视频直链（取完即清）。 */
    public static String takeVideo() {
        String v = VIDEO.get();
        VIDEO.remove();
        return v;
    }

    /** 取走音频直链（取完即清）。 */
    public static String takeAudio() {
        String a = AUDIO.get();
        AUDIO.remove();
        return a;
    }

    /*
     * 本次解析是不是"B 站直播"。直播和点播在播放阶段的行为完全不同：
     * 直播没有可跳位置（seek 必须忽略）、没有清晰度阶梯（ABR 不参与）、
     * 断流要主动重新取链重连、并且要用系统时间折算实时延迟。
     * 这些判断需要一条从解析阶段传到播放阶段的信号，所以放这里。
     */
    private static final ThreadLocal<Boolean> LIVE = new ThreadLocal<>();

    /** 标记本次解析是直播。 */
    public static void setLive(boolean live) {
        LIVE.set(live);
    }

    /** 取走直播标记（取完即清）。 */
    public static boolean takeLive() {
        Boolean v = LIVE.get();
        LIVE.remove();
        return v != null && v;
    }

    /** 记录解析结果里的独立音轨地址。 */
    public static void setResultAudio(String audioUrl) {
        if (audioUrl != null) RESULT_AUDIO.set(audioUrl);
    }

    /** 取走解析结果里的音轨地址（取完即清）。 */
    public static String takeResultAudio() {
        String a = RESULT_AUDIO.get();
        RESULT_AUDIO.remove();
        return a;
    }

    /** 丢弃，避免异常路径残留。 */
    public static void clear() {
        VIDEO.remove();
        AUDIO.remove();
        RESULT_AUDIO.remove();
        LIVE.remove();
    }
}
