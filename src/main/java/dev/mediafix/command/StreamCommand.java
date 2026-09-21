package dev.mediafix.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mediafix.MediaFix;
import dev.mediafix.config.StreamConfig;
import dev.polaris_light.bilibili_media.util.BilibiliMediaUtil;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * 指令：/mediafix-stream —— 高清缓存播放管理。
 * <ul>
 *   <li>/mediafix-stream [on|off] —— 开关 DASH 高清缓存播放；</li>
 *   <li>/mediafix-stream quality [档位] —— 查看/设置清晰度上限
 *       (360p/480p/720p/720p60/1080p/1080p60/4k/auto)；</li>
 *   <li>/mediafix-stream cache —— 查看缓存占用；</li>
 *   <li>/mediafix-stream cache clear —— 清空本地视频缓存（含前置 mod 下载的）。</li>
 * </ul>
 */
public final class StreamCommand {

    private StreamCommand() {
    }

    public static void register(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("mediafix-stream")
                .executes(ctx -> status(ctx.getSource()))
                .then(Commands.literal("on").executes(ctx -> setStream(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setStream(ctx.getSource(), false)))
                .then(Commands.literal("quality")
                        .executes(ctx -> showQuality(ctx.getSource()))
                        .then(Commands.literal("360p").executes(ctx -> setQuality(ctx.getSource(), 16)))
                        .then(Commands.literal("480p").executes(ctx -> setQuality(ctx.getSource(), 32)))
                        .then(Commands.literal("720p").executes(ctx -> setQuality(ctx.getSource(), 64)))
                        .then(Commands.literal("720p60").executes(ctx -> setQuality(ctx.getSource(), 74)))
                        .then(Commands.literal("1080p").executes(ctx -> setQuality(ctx.getSource(), 80)))
                        .then(Commands.literal("1080p60").executes(ctx -> setQuality(ctx.getSource(), 116)))
                        .then(Commands.literal("4k").executes(ctx -> setQuality(ctx.getSource(), 120)))
                        .then(Commands.literal("auto").executes(ctx -> setQuality(ctx.getSource(), 0))))
                .then(Commands.literal("cache")
                        .executes(ctx -> cacheInfo(ctx.getSource()))
                        .then(Commands.literal("clear").executes(ctx -> cacheClear(ctx.getSource()))));
        event.getDispatcher().register(cmd);
    }

    private static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "[mediafix] 高清缓存播放: " + (StreamConfig.stream ? "开" : "关")
                        + " | 清晰度上限: " + qualityName(StreamConfig.maxQn)
                        + " | /mediafix-stream quality|cache 调节"), false);
        return 1;
    }

    private static int setStream(CommandSourceStack source, boolean value) {
        StreamConfig.stream = value;
        StreamConfig.save();
        source.sendSuccess(() -> Component.literal(
                "[mediafix] 高清缓存播放 : " + (value ? "开 (DASH先下载后播)" : "关 (前置原生下载)")), false);
        return 1;
    }

    private static int showQuality(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "[mediafix] 当前清晰度上限: " + qualityName(StreamConfig.maxQn)
                        + "，可选: 360p 480p 720p 720p60 1080p 1080p60 4k auto"), false);
        return 1;
    }

    private static int setQuality(CommandSourceStack source, int qn) {
        StreamConfig.maxQn = qn;
        StreamConfig.save();
        // 清掉 watermedia 的解析/媒体缓存，让同一视频立即按新上限重新选流
        clearWatermediaCaches();
        source.sendSuccess(() -> Component.literal(
                "[mediafix] 清晰度上限已设为 " + qualityName(qn)
                        + " (新视频立即生效；已缓存视频仍是缓存时的清晰度)"), false);
        return 1;
    }

    private static int cacheInfo(CommandSourceStack source) {
        File dir = BilibiliMediaUtil.getDownloadPath().toFile();
        File[] files = dir.listFiles();
        long size = 0;
        int count = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    size += f.length();
                    count++;
                }
            }
        }
        final long sizeMb = size / 1048576;
        final int n = count;
        source.sendSuccess(() -> Component.literal(
                "[mediafix] 视频缓存: " + n + " 个文件, 共 " + sizeMb + "MB"
                        + " | 目录: " + dir.getName()
                        + " | /mediafix-stream cache clear 清空"), false);
        return 1;
    }

    private static int cacheClear(CommandSourceStack source) {
        File dir = BilibiliMediaUtil.getDownloadPath().toFile();
        long freed = 0;
        int failed = 0;
        // 先让前置清掉它登记的条目（会一并删除对应文件并更新 video.json）
        try {
            BilibiliMediaUtil.clearCache(0);
        } catch (Throwable t) {
            MediaFix.LOGGER.warn("[mediafix] 前置缓存清理异常，继续手动删除", t);
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    long len = f.length();
                    if (f.delete()) {
                        freed += len;
                    } else {
                        failed++;
                    }
                }
            }
        }
        // 缓存全清了，解析缓存也一并清掉，避免指向已删除文件
        clearWatermediaCaches();
        final long freedMb = freed / 1048576;
        final int fail = failed;
        source.sendSuccess(() -> Component.literal(
                "[mediafix] 缓存已清空, 释放 " + freedMb + "MB"
                        + (fail > 0 ? " (" + fail + " 个文件被占用未删，关闭播放后重试)" : "")), false);
        return 1;
    }

    /** 清空 watermedia 的 NetworkAPI/图片解析缓存（反射，尽力而为），使设置立即生效。 */
    private static void clearWatermediaCaches() {
        clearStaticMap("org.watermedia.api.network.NetworkAPI", "CACHE");
        clearStaticMap("org.watermedia.api.image.ImageCache", "CACHE");
    }

    private static void clearStaticMap(String cls, String field) {
        try {
            Field f = Class.forName(cls).getDeclaredField(field);
            f.setAccessible(true);
            Object o = f.get(null);
            if (o instanceof Map<?, ?> m) {
                m.clear();
            }
        } catch (Throwable ignored) {
            // 尽力而为：清不掉只是设置延后生效，不影响功能
        }
    }

    private static String qualityName(int qn) {
        return switch (qn) {
            case 0 -> "auto(不限)";
            case 16 -> "360P";
            case 32 -> "480P";
            case 64 -> "720P";
            case 74 -> "720P60";
            case 80 -> "1080P";
            case 116 -> "1080P60";
            case 120 -> "4K";
            default -> "qn" + qn;
        };
    }
}
