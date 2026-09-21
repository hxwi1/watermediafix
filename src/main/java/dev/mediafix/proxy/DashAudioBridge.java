package dev.mediafix.proxy;

import java.net.URI;

/**
 * DASH 音频桥：watermedia 2.1.36 的 BasePlayer 播放时只取 Result.uri，
 * 完全忽略 Result.audioUrl —— 而 B站 DASH 视频轨本身无声，音频必须以
 * ":input-slave" 媒体选项挂给 VLC 才能出声。
 *
 * <p>桥接方式：NetworkAPI.patch 返回带 audioUrl 的 Result 时暂存到这里，
 * BasePlayer 的 play 调用处读取并追加 input-slave 选项（同一线程先后执行，
 * patch 与 play 之间无其它 patch 调用，时序安全）。
 */
public final class DashAudioBridge {
    private static volatile URI pendingAudio;

    private DashAudioBridge() {
    }

    /** 暂存最近一次 patch 结果中的独立音频轨地址。 */
    public static void set(URI audio) {
        pendingAudio = audio;
    }

    /** 取走并清空暂存的音频轨地址（一次性消费）。 */
    public static URI poll() {
        URI a = pendingAudio;
        pendingAudio = null;
        return a;
    }
}
