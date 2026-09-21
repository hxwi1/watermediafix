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
 * WorldComment 评论"全隐藏"开关（客户端本地、不影响服务端和其它玩家）。
 * <p>受 mixin {@code CommentHideMixin} 使用：开则注释的 {@code isCommentVisible} 恒 false，
 * 世界空间评论全部隐藏。配置存于游戏目录 mediafix-comment.json，可用
 * {@code /mediafix hide [on|off]} 指令切换。
 */
public final class CommentHide {
    public static boolean enabled = false;

    private static final Path CFG = FMLPaths.GAMEDIR.get().resolve("mediafix-comment.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CommentHide() {
    }

    public static void load() {
        try {
            if (Files.exists(CFG)) {
                Map<String, Object> map = GSON.fromJson(
                        new String(Files.readAllBytes(CFG), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, Object>>() {}.getType());
                if (map != null && map.get("enabled") instanceof Boolean b) {
                    enabled = b;
                }
            }
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 读取评论隐藏配置失败", e);
        }
        save();
    }

    public static void save() {
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("enabled", enabled);
            Files.write(CFG, GSON.toJson(map).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            MediaFix.LOGGER.warn("[mediafix] 保存评论隐藏配置失败", e);
        }
    }
}