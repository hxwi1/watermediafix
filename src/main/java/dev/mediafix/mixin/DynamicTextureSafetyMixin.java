package dev.mediafix.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * 止血：堵住 DynamicTexture 的渲染线程 flush 竞态 NPE。
 *
 * <p>已实锤崩溃点：第一个构造（NativeImage）里，若在非渲染线程 new()，直接把
 * {@code () -> } 装箱的 {@code recordRenderCall} lambda（lambda$new$0）排入队；
 * 该 lambda 捕获 {@code this.pixels}（@Nullable 字段）而非构造参数。若在渲染线程
 * flush 前被 {@code close()} / {@code setPixels(null)} 置空 pixels，flush 执行
 * {@code this.pixels.getWidth() / this.pixels.upload(...)} 时 receiver 为 null 而
 * NPE，游戏硬崩。
 *
 * <p>在 lambda 入口整体短路：HEAD 判空后 {@code ci.cancel()} 跳过整个 body，
 * getWidth/getHeight/upload 一次绕开，也不让 prepareImage 对死亡纹理 id 分配 1x1
 * 存储（避免埋 GL_INVALID_OPERATION）。require=0：找不到该 lambda 注入点就静默
 * 跳过，绝不引发生成崩（本 mod 的 Mixin 硬崩教训）。
 */
@Mixin(DynamicTexture.class)
public abstract class DynamicTextureSafetyMixin {

    @Shadow
    @Nullable
    private NativeImage pixels;

    @Inject(method = "lambda$new$0", at = @At("HEAD"), cancellable = true, require = 0)
    private void mediafix$guardNullPixels(CallbackInfo ci) {
        if (this.pixels == null) {
            ci.cancel();
        }
    }
}