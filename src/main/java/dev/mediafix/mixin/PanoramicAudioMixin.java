package dev.mediafix.mixin;

import dev.mediafix.config.PanoramicAudio;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 全景声支持：强制 watermedia 的 VLC 音频输出为 Windows WASAPI(mmdevice)。
 *
 * <p>在 {@code PlayerAPI.registerFactory(String,String[])} 的方法入参（String[] 选项数组）入口处，
 * 把已有 `--aout` 的值改成 mmdevice 在前（WASAPI 原生多声道），让 5.1/7.1 等按原生声道数直出。
 * 不碰 `<clinit>`，改作用于方法自身参数，更稳。
 *
 * <p>受 {@link PanoramicAudio#enabled} 控制（预留开关，后续做 UI）。
 */
@Mixin(targets = "org.watermedia.api.player.PlayerAPI")
public abstract class PanoramicAudioMixin {

    @ModifyVariable(
            method = "registerFactory(Ljava/lang/String;[Ljava/lang/String;)Lorg/watermedia/videolan4j/factory/MediaPlayerFactory;",
            at = @At("HEAD"),
            index = 1,
            require = 1
    )
    private static String[] mediafix$forceWasapi(String[] args) {
        if (!PanoramicAudio.enabled || args == null) {
            return args;
        }
        String[] out = new String[args.length];
        System.arraycopy(args, 0, out, 0, args.length);
        for (int i = 0; i < out.length - 1; i++) {
            if ("--aout".equals(out[i])) {
                out[i + 1] = "mmdevice,directsound,waveout";
            }
        }
        return out;
    }
}