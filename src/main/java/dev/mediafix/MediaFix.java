package dev.mediafix;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mediafix.command.StreamCommand;
import dev.mediafix.config.CommentHide;
import dev.mediafix.config.CookieHarden;
import dev.mediafix.config.PanoramicAudio;
import dev.mediafix.config.PbrMode;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.config.SeekGuardConfig;
import dev.mediafix.proxy.MediaStreamProxy;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 纯客户端附属修复 mod。
 * 通过 Mixin 注入修复 WaterFrames + Bilibili-Media-Mod 播放网络视频的若干问题：
 * 1. 边播边下载 —— 流式代理边拉边播、不落盘（/mediafix-stream on）。
 * 2. 多人播放黑屏 —— 本地 HTTP 服务器开启 Range、扩容线程池、随机端口避免冲突。
 * 3. 皮肤贴图串流 —— 空 VideoPlayer 上传帧不恢复 GL 状态污染后续渲染。
 * 4. 进度偶发跳回开头 —— Display.tick 的陈旧同步 seek 到 0 被跳过。
 * 5. 全景声 —— 强制 VLC aout=mmdevice(WASAPI) 直出多声道。
 * 登录：沿用 bilibili_media 独立的 /bilimedia login 扫码，仅用于客户端获取高清晰度
 * 直链；本 mod 只负责安全加固（Cookie 限权 + 提示）与流式转发。
 */
@Mod(MediaFix.MODID)
public final class MediaFix {
    public static final String MODID = "mediafix";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public MediaFix() {
        IEventBus buses = NeoForge.EVENT_BUS;
        if (FMLEnvironment.dist.isClient()) {
            PbrMode.load();
            PanoramicAudio.load();
            CommentHide.load();
            StreamConfig.load();
            SeekGuardConfig.load();
            buses.addListener(MediaFix::registerClientCommands);
            buses.addListener(MediaFix::registerStreamCommands);
            buses.addListener(MediaFix::onServerStopped);
            buses.addListener(MediaFix::onPlayerLoggedIn);
            LOGGER.info("[mediafix] 已加载 (纯客户端修复, bilibili_media/watermedia/waterframes/worldcomment 可选)");
        }
    }

    /** 注册客户端指令：/mediafix hide [on|off] —— 切换 WorldComment 评论全隐藏；
     *  /mediafix seekguard [秒] —— 查看/调整进度纠偏阈值。 */
    private static void registerClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> cmd = net.minecraft.commands.Commands.literal("mediafix")
                .then(net.minecraft.commands.Commands.literal("hide")
                        .executes(ctx -> setHide(ctx.getSource(), !CommentHide.enabled))
                        .then(net.minecraft.commands.Commands.literal("on")
                                .executes(ctx -> setHide(ctx.getSource(), true)))
                        .then(net.minecraft.commands.Commands.literal("off")
                                .executes(ctx -> setHide(ctx.getSource(), false))))
                .then(net.minecraft.commands.Commands.literal("seekguard")
                        // 无参：只查看当前阈值
                        .executes(ctx -> showSeekGuard(ctx.getSource()))
                        // 有参：设置新的纠偏阈值（秒），清单 1~60
                        .then(net.minecraft.commands.Commands.argument("seconds",
                                        IntegerArgumentType.integer(1, 60))
                                .executes(ctx -> setSeekGuard(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(net.minecraft.commands.Commands.literal("help")
                        .executes(ctx -> showHelp(ctx.getSource())));

        event.getDispatcher().register(cmd);
    }

    /** 独立指令：/mediafix-stream [on|off] —— 切流式播放（与登录逻辑分离）。 */
    private static void registerStreamCommands(RegisterClientCommandsEvent event) {
        StreamCommand.register(event);
    }

    /** 离开世界/服务端停止时关闭本机流式代理，释放随机端口。 */
    private static void onServerStopped(ServerStoppedEvent event) {
        MediaStreamProxy.stop();
    }

    /** 进世界后：收紧 Cookie 文件权限，并一次性提示登录态仅限本账号、勿外传。 */
    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        CookieHarden.harden();
        if (CookieHarden.hasCookie() && CookieHarden.shouldNotice()) {
            Player player = event.getEntity();
            if (player != null) {
                player.displayClientMessage(Component.literal(
                        "[mediafix] 检测到B站登录态(Cookie=账号凭证)。已给密钥文件限权，仅当前系统用户可读，请勿泄露给他人。"),
                        false);
            }
        }
    }

    /** /mediafix help —— 显示所有可用子指令帮助。 */
    private static int showHelp(CommandSourceStack source) {
        sendSuccess(source, "[mediafix] 用法:");
        sendSuccess(source, "  /mediafix help            - 显示本帮助");
        sendSuccess(source, "  /mediafix hide [on|off]   - 切换 WorldComment 评论隐藏(默认切换)");
        sendSuccess(source, "  /mediafix seekguard       - 查看当前进度纠偏阈值");
        sendSuccess(source, "  /mediafix seekguard <秒>  - 设置纠偏阈值(1~60秒)");
        sendSuccess(source, "  /mediafix-stream [on|off] - 切DASH高清单缓存播放");
        sendSuccess(source, "  /mediafix-stream quality  - 设置清晰度/qn");
        sendSuccess(source, "  /mediafix-stream cache    - 查看/清空视频缓存");
        return 1;
    }

    /** 给指令执行者发送一条成功消息。 */
    private static void sendSuccess(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
    }

    private static int setHide(CommandSourceStack source, boolean value) {
        CommentHide.enabled = value;
        CommentHide.save();
        source.sendSuccess(() -> Component.literal(
                "[mediafix] WorldComment 评论隐藏: " + (value ? "开 (全部隐藏)" : "关 (正常显示)")), false);
        return 1;
    }

    /** /mediafix seekguard —— 显示当前进度纠偏阈值。 */
    private static int showSeekGuard(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "[mediafix] 进度纠偏阈值: " + SeekGuardConfig.thresholdMs / 1000
                        + " 秒 (" + SeekGuardConfig.thresholdMs + "ms)。查看帮助: /mediafix seekguard <秒>"),
                false);
        return 1;
    }

    /** /mediafix seekguard <秒> —— 设置进度纠偏阈值(1~60 秒)，立即生效并持久化。 */
    private static int setSeekGuard(CommandSourceStack source, int seconds) {
        SeekGuardConfig.thresholdMs = Math.max(1000, seconds * 1000);
        SeekGuardConfig.save();
        source.sendSuccess(() -> Component.literal(
                "[mediafix] 进度纠偏阈值已设为: " + SeekGuardConfig.thresholdMs / 1000
                        + " 秒 (已播放超过该值才视为陈旧同步,跳过 seekTo(0))"),
                false);
        return 1;
    }
}