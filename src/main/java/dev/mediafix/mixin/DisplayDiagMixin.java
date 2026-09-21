package dev.mediafix.mixin;

import dev.mediafix.MediaFix;
import me.srrapero720.waterframes.client.display.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.watermedia.api.image.ImageCache;

/**
 * 诊断注入：waterframes 的 Display.tick() 在 PICTURE 模式下每 tick 检查
 * imageCache.isVideo()，为 true 才调 switchVideoMode() 创建 VideoPlayer。
 * 打印这两处的实际状态，确认"视频模式切换"到底有没有发生、被什么挡住。
 */
@Mixin(targets = "me.srrapero720.waterframes.client.display.Display")
public abstract class DisplayDiagMixin {

    @Shadow private Display.Mode displayMode;
    @Shadow private boolean notVideo;
    @Shadow private ImageCache imageCache;

    /** tick 日志节流用（mixin 静态字段）。 */
    private static long mediafix$lastTickLog;

    @Inject(method = "switchVideoMode", at = @At("HEAD"))
    private void mediafix$logSwitch(CallbackInfo ci) {
        MediaFix.LOGGER.info("[mediafix][diag] switchVideoMode 被调用: isVideo={} status={}",
                imageCache.isVideo(), imageCache.getStatus());
    }

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void mediafix$logTick(CallbackInfo ci) {
        long now = System.currentTimeMillis();
        if (now - mediafix$lastTickLog < 2000) {
            return;
        }
        mediafix$lastTickLog = now;
        MediaFix.LOGGER.info("[mediafix][diag] Display.tick: mode={} notVideo={} isVideo={} status={}",
                displayMode, notVideo, imageCache.isVideo(), imageCache.getStatus());
    }
}
