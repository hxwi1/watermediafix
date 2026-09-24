package dev.mediafix.mixin;

import dev.mediafix.MediaFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 诊断注入：ImageFetch 出错回调（lambda$load$1）是 watermedia 把
 * VideoTypeException 转换成 "video=true + READY" 的唯一入口。
 * 打印回调收到的异常类型与 isVideo 布尔值，以及当时缓存状态，
 * 用于确认 DASH 代理流是否被正确识别为视频。
 */
@Mixin(targets = "org.watermedia.api.image.ImageCache")
public abstract class ImageCacheDiagMixin {

    @Shadow private volatile org.watermedia.api.image.ImageCache.Status status;
    @Shadow private volatile boolean video;

    @Inject(method = "lambda$load$1", at = @At("HEAD"))
    private void mediafix$logErrCallback(Exception e, Boolean isVideo, CallbackInfo ci) {
        MediaFix.LOGGER.diag("[mediafix][diag] ImageCache 错误回调: ex={} isVideo={} status={} video={}",
                e == null ? "null" : e.getClass().getSimpleName(), isVideo, status, video);
    }

    @Inject(method = "lambda$load$0", at = @At("HEAD"))
    private void mediafix$logOkCallback(org.watermedia.api.image.ImageRenderer renderer, Boolean cached, CallbackInfo ci) {
        MediaFix.LOGGER.diag("[mediafix][diag] ImageCache 成功回调: renderer={} cached={} status={} video={}",
                renderer == null ? "null" : renderer.getClass().getSimpleName(), cached, status, video);
    }
}