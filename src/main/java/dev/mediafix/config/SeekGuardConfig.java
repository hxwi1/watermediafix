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
 * 播放进度纠偏阈值（毫秒）：
 * <p>修复 waterframes 在服务端/多播放器同步时，因 <code>data.tick</code> 或缓冲时长瞬时为 0
 * 而对"已播一段"的视频错误执行 <code>seekTo(0)</code>（进度随机跳回开头）。
 * 思路：当目标为 0 且本地已播放超过 {@link #thresholdMs} 且未到片尾时，判定为陈旧同步并跳过。
 * <p>该阈值此前硬编码为 2000ms，现改为可配置、默认 5000ms，可用
 * <code>/mediafix seekguard &lt;秒&gt;</code> 在游戏内实时调整。
 * <p>配置存于游戏目录 mediafix-seekguard.json。
 */
public final class SeekGuardConfig {
    /** 纠偏阈值（毫秒）：已播放超过该值才视为"陈旧同步"，跳过 seekTo(0)。默认 5 秒。 */
    public static int thresholdMs = 5000;

    /**
     * "原地 seek"容差（毫秒）：目标与当前进度之差小于该值的 seek 直接忽略。
     *
     * <p>对网络流来说，seek 的代价极高 —— 重开两条 CDN 连接、清空 2.5 秒音频缓冲、
     * 必然进一次 BUFFERING；而"回到当前位置"这种 seek 画面和声音完全没变化，
     * 属于纯亏本操作。waterframes 的进度同步会频繁发出这类 seek
     * （实测日志：时钟 53440ms 时 seek 到 53450ms，差 10ms，却把缓冲整个砸掉）。
     */
    public static int noopToleranceMs = 1500;

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("mediafix-seekguard.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SeekGuardConfig() {
    }

    public static void load() {
        try {
            if (Files.exists(CFG)) {
                Map<String, Object> map = GSON.fromJson(
                        new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (map != null && map.get("seekGuardMs") instanceof Number n) {
                    thresholdMs = n.intValue();
                }
                if (map != null && map.get("noopToleranceMs") instanceof Number n) {
                    noopToleranceMs = n.intValue();
                }
            }
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 读取进度纠偏配置失败", e);
        }
        save();
    }

    public static void save() {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("seekGuardMs", thresholdMs);
            map.put("noopToleranceMs", noopToleranceMs);
            Files.write(CFG, GSON.toJson(map).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 保存进度纠偏配置失败", e);
        }
    }
}