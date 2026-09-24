package dev.mediafix;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.mediafix.command.StreamCommand;
import dev.mediafix.config.CommentHide;
import dev.mediafix.config.CookieHarden;
import dev.mediafix.bili.MediaFixBilibiliPatch;
import dev.mediafix.config.BiliConfig;
import dev.mediafix.config.FfmpegConfig;
import dev.mediafix.ffmpeg.FfmpegRuntime;
import dev.mediafix.config.PanoramicAudio;
import dev.mediafix.config.PbrMode;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.config.SeekGuardConfig;
import net.minecraft.commands.CommandSourceStack;

import java.net.URI;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * 纯客户端附属修复 mod。
 * 通过 Mixin 注入修复 WaterFrames + Bilibili-Media-Mod 播放网络视频的若干问题：
 * 1. 流式直连 —— B 站 DASH 音视频两条链边下边播、不落盘（/mediafix-stream streaming）。
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
    /**
     * 本模组日志门面。
     * {@code info/warn/error} 与方法名和 SLF4J 一致（现有调用无需改动），
     * 但要区分"高频诊断"和"事件"时用 {@code LOGGER.diag(...)} —— 它只写 mediafixlogs，不污染 latest.log。
     */
    public static final dev.mediafix.log.MediaFixLog LOGGER = new dev.mediafix.log.MediaFixLog();

    public MediaFix() {
        IEventBus buses = NeoForge.EVENT_BUS;
        if (FMLEnvironment.dist.isClient()) {
            // 先开日志，后面所有诊断才有地方落
            dev.mediafix.config.LogConfig.load();
            dev.mediafix.log.MediaFixLog.init(
                    net.neoforged.fml.loading.FMLPaths.GAMEDIR.get(),
                    dev.mediafix.config.LogConfig.dir,
                    dev.mediafix.config.LogConfig.keepFiles,
                    dev.mediafix.config.LogConfig.mirrorToMain);
            dev.mediafix.log.MediaFixLog.setDiagEnabled(dev.mediafix.config.LogConfig.diagEnabled);
            PbrMode.load();
            PanoramicAudio.load();
            CommentHide.load();
            StreamConfig.load();
            SeekGuardConfig.load();
            FfmpegConfig.load();
            BiliConfig.load();
            ensureBiliPatch();
            buses.addListener(MediaFix::registerClientCommands);
            buses.addListener(MediaFix::registerStreamCommands);
            buses.addListener(MediaFix::onServerStopped);
            buses.addListener(MediaFix::onClientTick);
            buses.addListener(MediaFix::onPlayerLoggedIn);
            LOGGER.info("[mediafix] 已加载 (纯客户端修复, bilibili_media/watermedia/waterframes/worldcomment 可选)");
            String ver = version();
            LOGGER.info("[mediafix] 本模组日志: {}", dev.mediafix.log.MediaFixLog.sessionFile());
            LOGGER.info("[mediafix] MediaFix {} 启动", ver);

            // 启动即打印状态，方便从日志判断"到底有没有启用"
            LOGGER.info("[mediafix] 状态: 自研引擎=常开 DASH流式直连={} 清晰度上限qn={} 硬件解码={}",
                    StreamConfig.dashStreaming ? "开" : "关",
                    StreamConfig.maxQn,
                    FfmpegConfig.hwAccel ? "开" : "关");

            // 引擎常开：原生库可能要解压几十 MB，放后台线程，不阻塞游戏启动
            {
                Thread boot = new Thread(() -> {
                    boolean ok = FfmpegRuntime.ensure();
                    LOGGER.info("[mediafix] FFmpeg 视频解码: {} ({})", ok ? "可用" : "不可用", FfmpegRuntime.status());
                }, "mediafix-ffmpeg-bootstrap");
                boot.setDaemon(true);
                boot.start();
            }
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
                .then(net.minecraft.commands.Commands.literal("login")
                        .executes(ctx -> startLogin(ctx.getSource()))
                        .then(net.minecraft.commands.Commands.literal("status")
                                .executes(ctx -> loginStatus(ctx.getSource())))
                        .then(net.minecraft.commands.Commands.literal("logout")
                                .executes(ctx -> logout(ctx.getSource()))))
                .then(net.minecraft.commands.Commands.literal("audio")
                        .executes(ctx -> showAudio(ctx.getSource()))
                        .then(net.minecraft.commands.Commands.literal("dolby")
                                .executes(ctx -> setAudioPref(ctx.getSource(), "dolby")))
                        .then(net.minecraft.commands.Commands.literal("hires")
                                .executes(ctx -> setAudioPref(ctx.getSource(), "hires")))
                        .then(net.minecraft.commands.Commands.literal("best")
                                .executes(ctx -> setAudioPref(ctx.getSource(), "best"))))
                .then(net.minecraft.commands.Commands.literal("streams")
                        .executes(ctx -> showStreams(ctx.getSource())))
                .then(net.minecraft.commands.Commands.literal("refresh")
                        .executes(ctx -> refreshPlayback(ctx.getSource(), false))
                        .then(net.minecraft.commands.Commands.literal("full")
                                .executes(ctx -> refreshPlayback(ctx.getSource(), true))))
                .then(net.minecraft.commands.Commands.literal("status")
                        .executes(ctx -> showStatus(ctx.getSource())))
                .then(net.minecraft.commands.Commands.literal("help")
                        .executes(ctx -> showHelp(ctx.getSource())));

        event.getDispatcher().register(cmd);
    }

    /** 独立指令：/mediafix-stream [on|off] —— 切流式播放（与登录逻辑分离）。 */
    private static void registerStreamCommands(RegisterClientCommandsEvent event) {
        StreamCommand.register(event);
    }
    /** 离开世界/服务端停止时主动收掉播放引擎，别把原生资源留到 JVM 关闭阶段。 */
    private static void onServerStopped(ServerStoppedEvent event) {
        try {
            dev.mediafix.engine.MediaEngines.closeAll();
        } catch (Throwable t) {
            LOGGER.warn("[mediafix] 退出世界时关闭引擎失败", t);
        }
    }

    /**
     * 离开世界时主动收掉播放引擎。
     *
     * <p>实测 {@code ServerStoppedEvent} 在"退出世界"时并不一定会走到（崩溃时 5 条引擎线程
     * 全都还活着，说明压根没关过）。引擎线程和 FFmpeg 原生上下文如果一直留到 JVM 关闭阶段，
     * MC 那边正在拆 GL/JIT/类加载器，我们这边还在跑原生解码，出问题的概率就高了。
     * 这里用"上一 tick 在世界里、这一 tick 不在"来判断，保证在游戏还活着的时候收摊。
     */
    private static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        boolean inWorld = net.minecraft.client.Minecraft.getInstance() != null
                && net.minecraft.client.Minecraft.getInstance().level != null;
        if (levelWasLoaded && !inWorld) {
            try {
                dev.mediafix.engine.MediaEngines.closeAll();
            } catch (Throwable t) {
                LOGGER.warn("[mediafix] 离开世界时关闭引擎失败", t);
            }
        }
        levelWasLoaded = inWorld;
    }

    private static boolean levelWasLoaded;

    /** 进世界后：收紧 Cookie 文件权限，并一次性提示登录态仅限本账号、勿外传。 */
    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        ensureBiliPatch();   // watermedia 的网络层可能后于本模组初始化，这里补一次（幂等）
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

    /**
     * 把自研 B 站解析器注册进 watermedia 的 NetworkAPI（幂等）。
     * 这是替代 bilibili_media 的关键：以前靠 Mixin 拦它的 patch，现在用官方扩展点自己注册。
     */
    public static void ensureBiliPatch() {
        try {
            for (String platform : org.watermedia.api.network.NetworkAPI.getPatchPlatforms()) {
                if (MediaFixBilibiliPatch.PLATFORM.equals(platform)) return;
            }
            org.watermedia.api.network.NetworkAPI.registerPatch(new MediaFixBilibiliPatch());
            LOGGER.info("[mediafix] 已注册自研 B 站解析器（不依赖 bilibili_media）");
        } catch (Throwable t) {
            LOGGER.warn("[mediafix] 注册 B 站解析器失败", t);
        }
    }

    /**
     * /mediafix login —— B 站扫码登录。
     *
     * <p>把二维码内容 URL 交给玩家：聊天栏给出链接并自动用系统浏览器打开（浏览器若已登录 B 站，
     * 点一下"确认"就等同扫码）；也可以用手机扫这个链接对应的二维码。随后后台轮询直到确认。
     */
    private static int startLogin(CommandSourceStack source) {
        sendSuccess(source, "[mediafix] 正在请求登录二维码…");
        Thread t = new Thread(() -> {
            String url = dev.mediafix.bili.BiliLogin.start(msg -> notify(source, msg));
            if (url == null) return;
            // 二维码内容用手机 B 站 App 扫；这里画成 PNG 并交给系统看图器打开（聊天栏字体不等宽，
            // 用字符拼二维码会变形，扫不出来）
            java.nio.file.Path png = dev.mediafix.bili.BiliLogin.renderQrPng(url);
            if (png != null) {
                notify(source, "请用手机 B 站 App 扫描刚弹出的二维码图片（文件: " + png + "）");
                try {
                    net.minecraft.Util.getPlatform().openFile(png.toFile());
                } catch (Throwable e) {
                    notify(source, "（自动打开图片失败，请手动打开上面的文件）");
                }
            } else {
                notify(source, "二维码图片生成失败，请手动在手机浏览器打开下面的链接：");
                notify(source, url);
            }
        }, "mediafix-login-request");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** 异步回报：命令回执必须回到主线程。 */
    private static void notify(CommandSourceStack source, String message) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null) {
            mc.execute(() -> source.sendSuccess(() -> Component.literal("[mediafix] " + message), false));
        }
    }

    /** /mediafix login status —— 查登录态（昵称/UID/大会员）。 */
    private static int loginStatus(CommandSourceStack source) {
        sendSuccess(source, "[mediafix] 正在查询登录态…");
        Thread t = new Thread(() -> {
            String s = dev.mediafix.bili.BiliLogin.status();
            notify(source, s == null ? "未登录（/mediafix login 扫码登录）" : "已登录: " + s);
        }, "mediafix-login-status");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /** /mediafix login logout —— 清除登录态。 */
    private static int logout(CommandSourceStack source) {
        dev.mediafix.bili.BiliLogin.logout();
        dev.mediafix.bili.BiliLogin.cancel();
        sendSuccess(source, "[mediafix] 已清除登录态（重启后不再带 Cookie 请求）");
        return 1;
    }

    /**
     * /mediafix refresh [full] —— 手动刷新当前视频。
     *
     * <p>普通模式：重新解析直链 + 无缝换链（视频与音频两条链一起换，保持播放位置、声音不断）。
     * 这条路径与"直链到期自动续期"完全相同，只是由玩家主动触发，
     * 用来救画面卡住/黑屏/直链失效这类只能靠换链恢复的情况。
     *
     * <p>full 模式：整个引擎重建（丢掉全部缓冲重新打开），换链都救不回来时的最后手段。
     *
     * <p>重新解析要发网络请求，所以放在后台线程；结果回到主线程提示。
     */
    private static int refreshPlayback(CommandSourceStack source, boolean full) {
        dev.mediafix.engine.MediaEngine e = dev.mediafix.engine.MediaEngines.lastCreated();
        if (e == null || e.isClosed()) {
            sendSuccess(source, "[mediafix] 当前没有正在播放的视频（刷新只对正在播的显示起作用）");
            return 0;
        }
        if (full) {
            sendSuccess(source, "[mediafix] 强制重建引擎中…（会重新打开两条链，位置尽量保留）");
        } else {
            sendSuccess(source, "[mediafix] 正在重新解析直链并无缝换链…");
        }
        Thread t = new Thread(() -> {
            boolean ok;
            String detail;
            if (full) {
                dev.mediafix.engine.MediaEngine fresh = dev.mediafix.engine.MediaEngines.rebuildLast();
                ok = fresh != null;
                detail = ok ? "引擎已重建" : "重建失败：找不到对应的播放器";
            } else {
                ok = e.refreshManually();
                detail = ok ? "已换上新鲜直链（无缝，位置不变）" : "重新解析失败：检查登录态或稍后重试";
            }
            final boolean okFinal = ok;
            final String msg = detail;
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.execute(() -> source.sendSuccess(
                    () -> Component.literal("[mediafix] 手动刷新" + (okFinal ? "完成" : "失败") + "：" + msg), false));
        }, "mediafix-manual-refresh");
        t.setDaemon(true);
        t.start();
        return 1;
    }

    /**
     * /mediafix audio —— 音频链现状：选中的音轨、源格式与输出格式、设备能吃的最大声道数。
     *
     * <p>杜比全景声是 6ch E-AC-3；如果这里显示"输出 2ch"，说明系统里那块输出设备只暴露了立体声，
     * 我们按标准矩阵下混（不是丢声道）。想让环绕出声，得先让系统把设备配成 5.1/7.1。
     */
    private static int showAudio(CommandSourceStack source) {
        sendSuccess(source, "[mediafix] 音频偏好: " + StreamConfig.audioPreference
                + "（dolby=杜比全景声优先 / hires=Hi-Res无损优先 / best=只取普通最高码率）");
        sendSuccess(source, "[mediafix] 本次解析选中: " + dev.mediafix.proxy.DashResolver.lastAudioSummary());
        dev.mediafix.engine.MediaEngine e = dev.mediafix.engine.MediaEngines.lastCreated();
        sendSuccess(source, "[mediafix] 引擎音频链: " + (e == null ? "尚未启动" : e.audioSummary()));
        int max = dev.mediafix.engine.AudioSink.maxSupportedChannels(48000);
        sendSuccess(source, "[mediafix] 本机输出能力: 最多 " + max + " 声道 @48kHz"
                + (max <= 2 ? "（Java Sound 只看到立体声设备；杜比会被下混成立体声）"
                            : "（可输出多声道，杜比按设备能力直出 5.1/7.1）"));
        for (String d : dev.mediafix.engine.AudioSink.describeDevices()) {
            if (d.contains("Direct Audio Device")) {
                sendSuccess(source, "   " + d);
            }
        }
        sendSuccess(source, "[mediafix] 切换: /mediafix audio dolby|hires|best（下次播放生效）");
        return 1;
    }

    private static int setAudioPref(CommandSourceStack source, String pref) {
        StreamConfig.audioPreference = pref;
        StreamConfig.save();
        sendSuccess(source, "[mediafix] 音频偏好已设为 " + pref + "（下次播放生效）");
        return 1;
    }

    /**
     * /mediafix streams —— 列出当前视频实际可选的每一条流（清晰度/编码/真实分辨率/码率）。
     * 用来分辨"真 4K"与"标称 4K 实为 1080p"，以及看清 B 站到底给了多大的码率。
     */
    private static int showStreams(CommandSourceStack source) {
        java.util.List<dev.mediafix.abr.AbrLadder.Candidate> all = dev.mediafix.abr.AbrLadder.all();
        if (all.isEmpty()) {
            sendSuccess(source, "[mediafix] 还没有解析记录：先播放一个 B 站视频，再执行本指令");
            return 1;
        }
        int cur = dev.mediafix.abr.AbrLadder.currentIndex();
        sendSuccess(source, "[mediafix] 本视频可选流（共 " + all.size() + " 条，" + all.get(0).resolution() + " ~ "
                + all.get(all.size() - 1).resolution() + "）：");
        for (int i = 0; i < all.size(); i++) {
            dev.mediafix.abr.AbrLadder.Candidate c = all.get(i);
            sendSuccess(source, String.format("  %s %-14s %-11s %6dkbps  qn=%d",
                    i == cur ? "▶" : " ", c.label(), c.resolution(), c.bandwidthBps() / 1000, c.qn()));
        }
        sendSuccess(source, "[mediafix] 自动模式会随网速在这些流之间切换（音频始终最高音质，不参与）");
        return 1;
    }

    /** /mediafix status —— 只读状态：引擎常开，这里只报告原生库与开关情况。 */
    private static int showStatus(CommandSourceStack source) {
        sendSuccess(source, "[mediafix] 自研引擎: 常开（不可关闭 —— 关掉就没法播 B 站内容）"
                + " | 原生库: " + FfmpegRuntime.status());
        sendSuccess(source, "[mediafix] DASH 流式直连: " + (StreamConfig.dashStreaming ? "开" : "关")
                + " | 清晰度上限: qn" + StreamConfig.maxQn
                + " | 音画偏移: " + FfmpegConfig.avOffsetMs + "ms（改 mediafix-ffmpeg.json 生效）");
        dev.mediafix.engine.MediaEngine e = dev.mediafix.engine.MediaEngines.lastCreated();
        sendSuccess(source, e == null
                ? "[mediafix] 本局还没播放过"
                : "[mediafix] 本次播放: 状态=" + e.state() + " 位置=" + e.timeMs() + "ms"
                        + " | seek 执行 " + e.seekCount() + " 次，忽略原地 seek " + e.ignoredSeekCount()
                        + " 次（每次忽略都省下一次重开连接+清空音频缓冲）");
        return 1;
    }

    /** /mediafix help —— 显示所有可用子指令帮助。 */
    private static int showHelp(CommandSourceStack source) {
        sendSuccess(source, "[mediafix] MediaFix " + version() + " —— 自研 FFmpeg DASH 流式引擎");
        sendSuccess(source, "");
        sendSuccess(source, "【播放】");
        sendSuccess(source, "  /mediafix refresh         - 手动刷新当前视频（重新解析直链 + 无缝换链，位置不变）");
        sendSuccess(source, "      ↳ 画面卡住/黑屏/直链失效时用，声音不断；与自动续期是同一条路径");
        sendSuccess(source, "  /mediafix refresh full    - 强制重建引擎（丢掉全部缓冲重开，换链救不回来时用）");
        sendSuccess(source, "");
        sendSuccess(source, "【清晰度】");
        sendSuccess(source, "  /mediafix streams         - 列出本视频实际可选的每条流（编码/真实分辨率/码率，辨别真假 4K）");
        sendSuccess(source, "  /mediafix-stream quality <档位> - 锁定清晰度：auto(默认，按网速自适应) / 360p~4k / max");
        sendSuccess(source, "  /mediafix-stream streaming on|off      - 开关 DASH 流式直连（关掉则回退前置模组原生播放）");
        sendSuccess(source, "  /mediafix-stream streaming <毫秒偏移>   - 音画偏移微调（正=画面提前）");
        sendSuccess(source, "");
        sendSuccess(source, "【音频】");
        sendSuccess(source, "  /mediafix audio           - 音频链现状：选中音轨、源/输出格式、设备最大声道数");
        sendSuccess(source, "  /mediafix audio dolby|hires|best - 音频偏好：杜比全景声优先 / Hi-Res 无损优先 / 只取普通最高码率");
        sendSuccess(source, "      ↳ 杜比是 6ch E-AC-3；设备只有 2ch 时按标准下混矩阵折成立体声（不是丢声道）");
        sendSuccess(source, "");
        sendSuccess(source, "【登录】");
        sendSuccess(source, "  /mediafix login           - B 站扫码登录（4K/杜比/高清直链必需）");
        sendSuccess(source, "  /mediafix login status    - 查看登录态");
        sendSuccess(source, "  /mediafix login logout    - 清除登录态");
        sendSuccess(source, "");
        sendSuccess(source, "【诊断】");
        sendSuccess(source, "  /mediafix status          - 引擎/原生库/流式开关 + 本次播放的 seek 统计");
        sendSuccess(source, "  /mediafix seekguard [秒]  - 查看/调整进度纠偏阈值（默认 5 秒，用于挡「陈旧同步跳回开头」）");
        sendSuccess(source, "  /mediafix-stream          - 流式播放参数现状（清晰度/偏移/开关）");
        sendSuccess(source, "");
        sendSuccess(source, "【其它】");
        sendSuccess(source, "  /mediafix hide [on|off]   - 切换 WorldComment 评论隐藏（无参=切换）");
        sendSuccess(source, "  /mediafix help            - 显示本帮助");
        sendSuccess(source, "");
        sendSuccess(source, "配置文件都在游戏目录：mediafix-stream.json / mediafix-ffmpeg.json / mediafix-seekguard.json");
        return 1;
    }

    /** 模组版本号（取自 mods.toml / neoforge.mods.toml）。 */
    private static String version() {
        try {
            return net.neoforged.fml.ModList.get().getModContainerById(MODID)
                    .map(c -> String.valueOf(c.getModInfo().getVersion())).orElse("?");
        } catch (Throwable t) {
            return "?";
        }
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
                "[mediafix] 进度纠偏阈值: " + SeekGuardConfig.thresholdMs / 1000 + " 秒 ("
                        + SeekGuardConfig.thresholdMs + "ms)，原地 seek 容差 "
                        + SeekGuardConfig.noopToleranceMs + "ms"
                        + "。查看帮助: /mediafix seekguard <秒>"),
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