package dev.mediafix.mixin;

import dev.mediafix.engine.MediaEngine;
import dev.mediafix.engine.MediaEngines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.player.videolan.VideoPlayer;

/**
 * waterframes 的 {@code Display.isBuffering()} 里有一处直接摸 VLC：
 * <pre>mediaPlayer.isBuffering() || mediaPlayer.isLoading()
 *     || mediaPlayer.raw().mediaPlayer().status().state() == State.NOTHING_SPECIAL</pre>
 * 引擎接管后 VLC 从不播放，最后那一项恒为真 → 永远画"缓冲中"。
 * 这里在有引擎时直接改用引擎状态。
 */
@Mixin(targets = "me.srrapero720.waterframes.client.display.Display")
public abstract class DisplayFfmpegMixin {

    @Shadow private VideoPlayer mediaPlayer;
    @Shadow private me.srrapero720.waterframes.common.block.entity.DisplayTile tile;
    /** 上一条"拒绝渲染"诊断的时间（限流）。 */
    @Unique private long mediafix$lastNoRenderAt;

    @Inject(method = "isBuffering", at = @At("HEAD"), cancellable = true, require = 0)
    private void mediafix$bufferingFromEngine(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine engine = MediaEngines.of(this.mediaPlayer);
        if (engine != null) {
            cir.setReturnValue(engine.isBuffering());
        }
    }

    /**
     * "有声音没画面"的定论诊断。
     *
     * <p>引擎接管后 renderer 只会在 {@code canRender()} 为 true 时才画纹理；一旦它为 false，
     * 表现就是画面全黑、音频照常。这个方法的每个闸门其实都被我们接管了，所以一旦出现 false，
     * 必须留下"到底哪一闸不通过"的证据 —— 不然只能靠猜。
     */
    @Inject(method = "canRender", at = @At("RETURN"))
    private void mediafix$diagCanRender(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine engine = MediaEngines.of(this.mediaPlayer);
        if (engine == null || cir.getReturnValueZ()) return;
        long now = System.currentTimeMillis();
        if (now - this.mediafix$lastNoRenderAt < 3000L) return;
        this.mediafix$lastNoRenderAt = now;
        dev.mediafix.MediaFix.LOGGER.warn(
                "[mediafix] 引擎在播但 waterframes 不渲染画面：canRender=false"
                        + " | safeUse={} waiting={} loading={} ready={} displayActive={} 引擎状态={} 引擎已关闭={} 引擎就绪可显示={}",
                this.mediaPlayer.isSafeUse(), this.mediaPlayer.isWaiting(), this.mediaPlayer.isLoading(),
                this.mediaPlayer.isReady(), this.tile != null && this.tile.data.active,
                engine.state(), engine.isClosed(), engine.readyForDisplay());
    }
}
