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
 * 全景声开关（预留，后续再做 UI）。
 * <p>开：把 VLC 音频输出强制为 Windows WASAPI(mmdevice)，让多声道视频按原生声道数直出系统
 * 设备，避免默认 directsound 下混成 2.0。关：保持 watermedia 默认。
 */
public final class PanoramicAudio {
    public static boolean enabled = true;

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("mediafix-audio.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PanoramicAudio() {
    }

    public static void load() {
        try {
            if (Files.exists(CFG)) {
                Map<String, Object> map = GSON.fromJson(
                        new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (map != null && map.get("enabled") instanceof Boolean b) enabled = b;
            }
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 读取全景声配置失败", e);
        }
        save();
    }

    public static void save() {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("enabled", enabled);
            Files.write(CFG, GSON.toJson(map).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 保存全景声配置失败", e);
        }
    }
}