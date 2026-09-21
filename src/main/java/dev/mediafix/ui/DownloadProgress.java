package dev.mediafix.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 下载进度 UI：把 DASH 视频缓存进度实时显示在物品栏上方（actionbar 覆盖层），
 * 代替 waterframes 一直转圈的"加载中"。进度刷新节流 400ms；
 * 完成与缓存命中提示走聊天栏（保留一条记录便于回看）。
 *
 * <p>线程安全：可从任意工作线程调用，UI 更新调度到渲染线程执行。
 */
public final class DownloadProgress {

    /** 进度刷新节流间隔。 */
    private static final long THROTTLE_MS = 400;
    private static volatile long lastUi;

    private DownloadProgress() {
    }

    /** 在 actionbar 上显示一条带百分比的下载进度。 */
    public static void progress(String label, long read, long total) {
        long now = System.currentTimeMillis();
        if (now - lastUi < THROTTLE_MS) {
            return;
        }
        lastUi = now;
        String text = "[mediafix] " + label + " " + percent(read, total)
                + " (" + mb(read) + " / " + mb(total) + " MB)";
        show(text, true);
    }

    /** 聊天栏提示（overlay=false 保留在聊天记录里）。 */
    public static void message(String text) {
        show("[mediafix] " + text, false);
    }

    private static void show(String text, boolean overlay) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal(text), overlay);
            }
        });
    }

    private static String percent(long read, long total) {
        if (total <= 0) {
            return mb(read) + "MB";
        }
        return String.format("%.0f%%", Math.min(100.0, read * 100.0 / total));
    }

    private static String mb(long bytes) {
        return String.format("%.1f", bytes / 1048576.0);
    }
}
