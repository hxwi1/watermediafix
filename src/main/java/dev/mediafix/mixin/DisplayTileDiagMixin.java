package dev.mediafix.mixin;

import dev.mediafix.MediaFix;
import me.srrapero720.waterframes.client.display.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.image.ImageCache;

/**
 * 诊断注入：DisplayTile.requestDisplay() 是 waterframes 每 tick 的入口，
 * 按缓存状态决定"创建 Display / 重新 load / FORGOTTEN 重置"。
 * 打印进入时的缓存状态与 Display 是否已存在，确认 FORGOTTEN 循环的成因，
 * 以及 Display 是否被创建、URI 是否与方块一致。
 */
@Mixin(targets = "me.srrapero720.waterframes.common.block.entity.DisplayTile")
public abstract class DisplayTileDiagMixin {

    @Shadow public ImageCache imageCache;
    @Shadow public Display display;

    /** requestDisplay 日志节流用（mixin 静态字段）。 */
    private static long mediafix$lastLog;

    @Inject(method = "requestDisplay", at = @At("HEAD"))
    private void mediafix$logRequest(CallbackInfoReturnable<Display> cir) {
        long now = System.currentTimeMillis();
        if (now - mediafix$lastLog < 2000) {
            return;
        }
        mediafix$lastLog = now;
        MediaFix.LOGGER.info("[mediafix][diag] requestDisplay: display={} cacheStatus={} cacheVideo={} cacheUri={}",
                display == null ? "未创建" : "已存在",
                imageCache == null ? "无缓存" : imageCache.getStatus(),
                imageCache != null && imageCache.isVideo(),
                imageCache == null ? "-" : imageCache.uri);
    }
}
