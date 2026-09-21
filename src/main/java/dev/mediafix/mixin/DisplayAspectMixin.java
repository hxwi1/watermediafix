package dev.mediafix.mixin;

import dev.mediafix.render.LetterboxState;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 自适应比例（不拉伸、允许黑边）第一步：在 Display.preRender（视频帧上传前的
 * 渲染线程入口）捕获当前屏幕的格宽高比，交给 VideoPlayer 的帧上传处做信箱化。
 * 屏幕的渲染画布就是 data.getWidth() x getHeight() 格，比例即宽/高。
 */
@Mixin(targets = "me.srrapero720.waterframes.client.display.Display")
public abstract class DisplayAspectMixin {

    @Shadow @Final private DisplayTile tile;

    @Inject(method = "preRender", at = @At("HEAD"))
    private void mediafix$captureAspect(CallbackInfo ci) {
        try {
            float w = tile.data.getWidth();
            float h = tile.data.getHeight();
            LetterboxState.set(h > 0f ? w / h : -1f);
        } catch (Throwable ignored) {
            LetterboxState.set(-1f);
        }
    }
}
