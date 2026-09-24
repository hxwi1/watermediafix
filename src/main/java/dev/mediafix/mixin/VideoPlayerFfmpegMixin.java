package dev.mediafix.mixin;

import dev.mediafix.engine.MediaEngine;
import dev.mediafix.engine.MediaEngines;
import dev.mediafix.ffmpeg.FfmpegVideoSource;
import dev.mediafix.render.GlSafeUpload;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.player.videolan.VideoPlayer;
import org.watermedia.videolan4j.player.base.MediaPlayer;
import org.watermedia.videolan4j.player.embedded.videosurface.callback.BufferFormat;

import java.nio.ByteBuffer;

/**
 * 视频输出接管：把 frame 来源从 VLC 回调换成自研引擎。
 *
 * <p>接管点仍然是 {@code preRender()}：waterframes 的渲染器每帧走
 * {@code DisplayRenderer.render → Display.preRender → VideoPlayer.preRender}，
 * 也就是"渲染线程 + GL 上下文当前"。在这里向引擎要一帧并上传到本播放器已创建的纹理上。
 */
@Mixin(targets = "org.watermedia.api.player.videolan.VideoPlayer")
public abstract class VideoPlayerFfmpegMixin {

    @Shadow @Final private int texture;

    /**
     * 本播放器实例当前挂着的纹理 id（-1 = 还没上传过）。
     *
     * <p>必须按实例记：纹理 id 会被 GL 回收复用，光看"这个 id 曾经分配过多大"
     * 会漏掉"新播放器拿到被复用的同名 id、但存储其实没分配"的情况，结果就是有声音没画面。
     */
    @Unique private int mediafix$tex = -1;

    @Inject(method = "preRender", at = @At("HEAD"))
    private void mediafix$presentEngineFrame(CallbackInfoReturnable<Integer> cir) {
        MediaEngine engine = MediaEngines.of(this);
        if (engine == null) return;

        FfmpegVideoSource.Frame frame = engine.acquireVideoFrame();
        if (frame == null) return;

        try {
            // 尺寸跟踪（含信箱化画布）在 GlSafeUpload 内部；但"要不要重新分配存储"必须由本实例说了算：
            // 纹理换了（新播放器、或被回收复用的 id）就一定重新分配，否则会写进一块空纹理 = 黑屏有声。
            boolean first = this.texture != this.mediafix$tex;
            if (first) {
                dev.mediafix.MediaFix.LOGGER.info(
                        "[mediafix] 视频纹理重新分配: tex={} 尺寸={}x{}（播放器实例或纹理 id 变了）",
                        this.texture, frame.width, frame.height);
            }
            this.mediafix$tex = this.texture;
            GlSafeUpload.uploadFrame(frame.buf, this.texture, frame.width, frame.height, first);
        } finally {
            engine.releaseVideoFrame(frame);
        }
    }

    /** VLC 侧若还有帧回调（未接管时才有），引擎在跑就直接丢弃，避免两个来源抢同一张纹理。 */
    @Inject(method = "display", at = @At("HEAD"), cancellable = true)
    private void mediafix$dropVlcFrames(MediaPlayer mediaPlayer, ByteBuffer[] buffers,
                                        BufferFormat bufferFormat, CallbackInfo ci) {
        if (MediaEngines.of(this) != null) ci.cancel();
    }

    @Inject(method = "width", at = @At("RETURN"), cancellable = true)
    private void mediafix$width(CallbackInfoReturnable<Integer> cir) {
        MediaEngine engine = MediaEngines.of(this);
        if (engine != null && engine.videoWidth() > 1) cir.setReturnValue(engine.videoWidth());
    }

    @Inject(method = "height", at = @At("RETURN"), cancellable = true)
    private void mediafix$height(CallbackInfoReturnable<Integer> cir) {
        MediaEngine engine = MediaEngines.of(this);
        if (engine != null && engine.videoHeight() > 1) cir.setReturnValue(engine.videoHeight());
    }

    @Inject(method = "release", at = @At("HEAD"))
    private void mediafix$releaseEngine(CallbackInfo ci) {
        MediaEngines.close(this);
    }
}
