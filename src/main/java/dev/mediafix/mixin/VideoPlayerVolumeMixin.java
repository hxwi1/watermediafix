package dev.mediafix.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 让 VLC 系统直出(全景声)的音频也吃 MC 主音量，避免绕过 OpenAL 后"只受系统音量控制"。
 *
 * <p>做法：watermedia 的 {@code BasePlayer.setVolume(I)V} 是所有屏幕音频的收口点
 * （VideoPlayer 继承自 BasePlayer；waterframes 已把 per-screen 距离衰减算进该参数）。
 * 在此把入参乘上一个"MC 主音量系数"，即可让 VLC 输出随 MC 主音量一起升降；
 * 主音量拉满=1 时不受影响，拉低则视频音量随之降低。
 */
@Mixin(targets = "org.watermedia.api.player.videolan.BasePlayer")
public abstract class VideoPlayerVolumeMixin {

    @ModifyVariable(method = "setVolume(I)V", at = @At("HEAD"), index = 1, require = 1)
    private int mediafix$syncMasterVolume(int volume) {
        // 自研引擎接管时，主音量统一在引擎的音频输出里乘一次，这里必须让位（否则双乘）
        if (dev.mediafix.engine.MediaEngines.of((Object) this) != null) {
            return volume;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return volume;
        }
        // MC 主音量：0.0 ~ 1.0（NeoForge 映射下方法名为 getSoundSourceVolume）
        float master = mc.options.getSoundSourceVolume(SoundSource.MASTER);
        if (master >= 1.0f || master <= 0.0f) {
            // 满/静音直接套用，省一次乘法误差
            return master <= 0.0f ? 0 : volume;
        }
        return (int) (volume * master);
    }
}