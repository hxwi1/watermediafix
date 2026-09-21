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
 * PBR/自发光显示模式（修复开 shader 后屏幕因硬编码自发光失效而发暗）。
 *
 * <p>逻辑：
 *   <ul>
 *     <li>{@link #enabled} —— 总开关（暂未接 UI/滑块，后面再做设置界面）。</li>
 *     <li>{@link #brightness} —— 屏幕亮度倍率（滑块占位，>1 往白压=自发光提亮，1=原样）。</li>
 *     <li>{@link #shouldBoost()} —— 前置条件组合：开关开 + 亮度>1 + 检测到 shader(Iris) 在用时才生效。</li>
 *   </ul>
 *
 * <p>配置存于游戏目录 mediafix-pbr.json。
 */
public final class PbrMode {
    public static boolean enabled = true;
    public static float brightness = 1.35f;

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("mediafix-pbr.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private PbrMode() {
    }

    public static void load() {
        try {
            if (Files.exists(CFG)) {
                Map<String, Object> map = GSON.fromJson(
                        new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (map != null) {
                    if (map.get("enabled") instanceof Boolean b) enabled = b;
                    if (map.get("brightness") instanceof Number n) brightness = n.floatValue();
                }
            }
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 读取 PBR 配置失败", e);
        }
        save();
    }

    public static void save() {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("enabled", enabled);
            map.put("brightness", brightness);
            Files.write(CFG, GSON.toJson(map).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 保存 PBR 配置失败", e);
        }
    }

    /**
     * 前置条件：是否真正启用提亮。
     * 开关开 && 亮度非原样 && 检测到 shader 在运行。
     */
    public static boolean shouldBoost() {
        return enabled && brightness != 1.0f && isShaderActive();
    }

    private static boolean isShaderActive() {
        // 用反射查询 Iris（package 不固定官方 API 版本），缺失/异常都视为未启用。
        try {
            Class<?> mods = Class.forName("net.neoforged.fml.ModList");
            Object list = mods.getMethod("get").invoke(null);
            Object loaded = mods.getMethod("isLoaded", String.class).invoke(list, "iris");
            if (!Boolean.TRUE.equals(loaded)) {
                return false;
            }
            // IrisApi.getInstance().isShaderPackInUse()
            Class<?> api = Class.forName("net.irisshaders.iris.api.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return Boolean.TRUE.equals(api.getMethod("isShaderPackInUse").invoke(inst));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把一个 0-255 通道按倍率提亮并夹紧。 */
    public static int applyBrightness(int channel) {
        float v = channel * brightness;
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        return (int) v;
    }
}