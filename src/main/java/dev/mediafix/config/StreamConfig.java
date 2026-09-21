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
 * "高清缓存播放"开关：开——B站视频走 DASH 高清链路（4K/高码率/杜比，带 wbi 签名），
 * 先下载到本地缓存目录（BiliBiliMediaFiles，复用前置 mod 的 9095 本地服务器伺服）
 * 再播放，下载进度实时显示在物品栏上方；已缓存的视频再次播放时秒开。
 * 关——回到 bilibili_media 原生的 durl 下载逻辑（约 1080p）。
 *
 * <p>清晰度上限 {@link #maxQn} 可通过 /mediafix-stream quality 调节，
 * 0 = 不限（拿账号授权内最高，如 4K/8K）。
 * <p>配置存于游戏目录 mediafix-stream.json。
 */
public final class StreamConfig {
    /** true = DASH 高清缓存播放（先下载后播）；false = 前置 mod 原生逻辑。 */
    public static boolean stream = true;
    /** true = 优先 DASH(4K/8K/杜比) 高清链路；false = 走前置 durl(约1080p)。 */
    public static boolean highres = true;
    /** 清晰度上限(qn)：16=360P 32=480P 64=720P 74=720P60 80=1080P 116=1080P60 120=4K；0=不限。 */
    public static int maxQn = 80;

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("mediafix-stream.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private StreamConfig() {
    }

    public static void load() {
        try {
            if (Files.exists(CFG)) {
                Map<String, Object> map = GSON.fromJson(
                        new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (map != null) {
                    if (map.get("stream") instanceof Boolean b) stream = b;
                    if (map.get("highres") instanceof Boolean h) highres = h;
                    if (map.get("maxQn") instanceof Number n) maxQn = n.intValue();
                }
            }
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 读取流式播放配置失败", e);
        }
        save();
    }

    public static void save() {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("stream", stream);
            map.put("highres", highres);
            map.put("maxQn", maxQn);
            Files.write(CFG, GSON.toJson(map).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 保存流式播放配置失败", e);
        }
    }
}
