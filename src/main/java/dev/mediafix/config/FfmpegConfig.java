package dev.mediafix.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.mediafix.MediaFix;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 自研引擎（FFmpeg）的参数。配置存于游戏目录 mediafix-ffmpeg.json。
 *
 * <p><b>没有总开关</b>：引擎是本模组的核心，关掉它就没法播 B 站内容（前置模组的能力已经被
 * DASH 直连取代），所以它常开 —— 只保留这些调优参数。
 */
public final class FfmpegConfig {

    /** 解码线程数；0 = 自动（CPU 核心数的一半，至少 1）。 */
    public static int threads = 0;

    /** 环形帧槽数量（同时也是能容忍的抖动深度）。 */
    public static int ringSize = 4;

    /** 让 VLC 不解视频轨（追加 :no-video 媒体选项）。 */
    public static boolean disableVlcVideo = true;

    /** 原生库（FFmpeg DLL/so）压缩包路径；空 = 自动在 mods/ 与 config/mediafix/ 下寻找。 */
    public static String nativesPath = "";

    /** 是否把 FFmpeg 的详细日志写进游戏日志。 */
    public static boolean verbose = false;

    /** 音画偏移（毫秒）：正数 = 画面提前。用于两条独立流起播时间不同时的对齐。 */
    public static int avOffsetMs = 0;

    /** 硬件解码（D3D11VA / DXVA2）。4K 软解基本跑不动，默认开；失败会自动退回软解。 */
    public static boolean hwAccel = true;

    /** 开播前先缓冲的音频毫秒数：缓够了才开始播，避免一上来就卡。 */
    public static int prebufferMs = 1500;

    /** 音频输出缓冲深度（毫秒）：网络抖动靠它吸收。 */
    public static int audioBufferMs = 2500;

    /**
     * 视频码流预读预算（MB）：队列里缓存的是【压缩数据】，不是解码后的帧。
     * 4K 约 15Mbps → 48MB ≈ 25 秒；1080p 约 5Mbps → 48MB ≈ 75 秒。
     */
    public static int videoReadAheadMb = 48;

    /** 音频码流预读预算（MB）：音频码率低，8MB 就够好几分钟。 */
    public static int audioReadAheadMb = 8;

    /**
     * 色彩转换（NV12→RGBA）的并行线程数。0 = 自动（按核数取 1~4）。
     * 分带独立转换，离线验证过与整帧转换【逐字节一致】，零画质损失。
     */
    public static int convertThreads = 0;

    /**
     * 解码后帧缓冲的内存预算（MB）。参考流媒体播放器的水位线思想：
     * 缓冲该按"能撑住多少秒"来算，而不是固定几帧。
     * 4K 一帧 RGBA 是 33MB，256MB 只能放 7 帧（约 0.3 秒）；1080p 同预算能放 30 帧（约 1.2 秒）。
     * 设得越大抗抖动越强，代价是显存外的原生内存占用。
     */
    public static int videoFrameBufferMb = 256;


    /**
     * 关闭时是否释放 FFmpeg 原生上下文。
     * 默认 true；设为 false 可以"只泄漏不释放"，用来判断退出世界时的崩溃是否来自原生释放路径。
     */
    public static boolean freeNativeOnClose = true;

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("mediafix-ffmpeg.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private FfmpegConfig() {
    }

    public static void load() {
        try {
            if (Files.exists(CFG)) {
                Map<String, Object> map = GSON.fromJson(
                        new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (map != null) {
                                if (map.get("threads") instanceof Number n) threads = n.intValue();
                    if (map.get("ringSize") instanceof Number n) ringSize = Math.max(2, n.intValue());
                    if (map.get("disableVlcVideo") instanceof Boolean b) disableVlcVideo = b;
                    if (map.get("nativesPath") instanceof String s) nativesPath = s;
                    if (map.get("verbose") instanceof Boolean b) verbose = b;
                    if (map.get("avOffsetMs") instanceof Number n) avOffsetMs = n.intValue();
                    if (map.get("hwAccel") instanceof Boolean b) hwAccel = b;
                    if (map.get("prebufferMs") instanceof Number n) prebufferMs = Math.max(0, n.intValue());
                    if (map.get("audioBufferMs") instanceof Number n) audioBufferMs = Math.max(250, n.intValue());
                    if (map.get("videoReadAheadMb") instanceof Number n) videoReadAheadMb = Math.max(4, n.intValue());
                    if (map.get("audioReadAheadMb") instanceof Number n) audioReadAheadMb = Math.max(1, n.intValue());
        if (map.get("convertThreads") instanceof Number n) convertThreads = Math.max(0, n.intValue());
        if (map.get("videoFrameBufferMb") instanceof Number n) videoFrameBufferMb = Math.max(32, n.intValue());
        if (map.get("freeNativeOnClose") instanceof Boolean b) freeNativeOnClose = b;
                }
            }
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 读取 FFmpeg 配置失败", e);
        }
        save();
    }

    public static void save() {
        try {
            Map<String, Object> map = new HashMap<>();
                map.put("threads", threads);
            map.put("ringSize", ringSize);
            map.put("disableVlcVideo", disableVlcVideo);
            map.put("nativesPath", nativesPath);
            map.put("verbose", verbose);
            map.put("avOffsetMs", avOffsetMs);
            map.put("hwAccel", hwAccel);
            map.put("prebufferMs", prebufferMs);
            map.put("audioBufferMs", audioBufferMs);
            map.put("videoReadAheadMb", videoReadAheadMb);
            map.put("audioReadAheadMb", audioReadAheadMb);
        map.put("convertThreads", convertThreads);
        map.put("videoFrameBufferMb", videoFrameBufferMb);
        map.put("freeNativeOnClose", freeNativeOnClose);
            Files.write(CFG, GSON.toJson(map).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 保存 FFmpeg 配置失败", e);
        }
    }
}
