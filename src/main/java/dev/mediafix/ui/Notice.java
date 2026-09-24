package dev.mediafix.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * 玩家提示：解析结果/失败原因直接显示在聊天栏，不让玩家对着转圈干等。
 *
 * <p>线程安全：可从任意工作线程调用，UI 更新调度到渲染线程执行。
 *
 * <p>（原先这里还有一条"下载进度条"通道，随本地缓存下载路径一起移除了 ——
 * 本模组只做流式播放，没有下载可言。）
 */
public final class Notice {

    private Notice() {
    }

    /** 聊天栏提示（保留在聊天记录里）。 */
    public static void message(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[mediafix] " + text), false);
            }
        });
    }
}
