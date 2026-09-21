package dev.mediafix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 打开 VLC 自身的文件日志（诊断用）：watermedia 创建 VLC 实例时固定带
 * `--no-file-logging`，VLC 打开流失败等错误完全静默（最新症状：play 提交成功
 * 但从未连接代理，无任何日志）。这里在 registerFactory 入口改写参数数组：
 * <ul>
 *   <li>去掉 `--no-file-logging`；</li>
 *   <li>追加 `--file-logging` `--logmode=text` `--verbose=2` `--logfile=&lt;tmp&gt;/mediafix-vlc.log`。</li>
 * </ul>
 * VLC 会把 http 访问、解封装、解码的详细过程写进该文件，用于定位
 * "VLC 不连代理"的真正原因。固定客户端固定版本，诊断期间常开。
 *
 * <p>与 PanoramicAudioMixin 同目标但各改各的参数项（--aout vs 日志项），互不影响。
 */
@Mixin(targets = "org.watermedia.api.player.PlayerAPI")
public abstract class VlcLogMixin {

    /** VLC 日志文件位置（临时目录，无空格路径，便于 VLC 写入）。 */
    public static final String VLC_LOG_PATH =
            Path.of(System.getProperty("java.io.tmpdir"), "mediafix-vlc.log").toString();

    @ModifyVariable(
            method = "registerFactory(Ljava/lang/String;[Ljava/lang/String;)Lorg/watermedia/videolan4j/factory/MediaPlayerFactory;",
            at = @At("HEAD"),
            index = 1,
            require = 1
    )
    private static String[] mediafix$enableVlcFileLog(String[] args) {
        if (args == null) {
            return args;
        }
        List<String> out = new ArrayList<>(args.length + 4);
        for (String a : args) {
            if ("--no-file-logging".equals(a)) {
                continue; // 摘掉"关闭文件日志"
            }
            out.add(a);
        }
        out.add("--verbose=2");
        out.add("--file-logging");
        out.add("--logmode=text");
        out.add("--logfile=" + VLC_LOG_PATH);
        return out.toArray(new String[0]);
    }
}
