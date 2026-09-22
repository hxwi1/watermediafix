package dev.mediafix.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 访问 GlStateManager 的纹理绑定缓存（绕过会做渲染线程断言的公开方法）。
 *
 * <p>背景：MC 的 {@code GlStateManager._bindTexture} 有一层缓存——若
 * {@code TEXTURES[activeTexture].binding} 已等于目标值就直接跳过真正的
 * {@code glBindTexture}。而 watermedia 的视频帧上传（RenderAPI.uploadBuffer）
 * 在渲染线程之外直接调原生 GL 绑定/解绑，缓存与真实 GL 状态脱节：
 * MC 后续以为“GUI 纹理已绑定”而跳过 bind，实际采样到的却是残留的视频纹理，
 * 表现为固定几个 GUI 材质被视频画面污染（F3+T 重建状态后恢复）。
 *
 * <p>这里通过 Accessor 直接读写缓存字段，让缓存与我们在
 * {@code VideoPlayerMixin} 里恢复的真实 GL 状态保持一致。
 * {@code TEXTURES} 元素类型是包私有内部类 TextureState，因此返回 Object[]，
 * 字段访问交给 {@link TextureStateAccessor}（运行时由 Mixin 注入实现）。
 */
@Mixin(GlStateManager.class)
public interface GlStateManagerAccessor {

    /** 当前活动纹理单元索引（0..11），与 GlStateManager.activeTexture 同步。 */
    @Accessor("activeTexture")
    static void mediafix$setActiveTexture(int unit) {
        throw new AssertionError();
    }

    /** 各纹理单元的绑定缓存数组（元素为包私有 TextureState）。 */
    @Accessor("TEXTURES")
    static Object[] mediafix$getTextures() {
        throw new AssertionError();
    }
}
