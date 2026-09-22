package dev.mediafix.render;

import dev.mediafix.mixin.GlStateManagerAccessor;
import dev.mediafix.mixin.TextureStateAccessor;
import org.lwjgl.opengl.GL13;

/**
 * 把 Minecraft GlStateManager 的纹理绑定缓存同步为“当前真实 GL 状态”。
 *
 * <p>watermedia 的视频帧上传在渲染线程之外直接调原生 glBindTexture，
 * GlStateManager 的缓存（TEXTURES[n].binding / activeTexture）不知情，
 * 之后 MC 画 GUI 时因缓存命中跳过真正的 bind → 采样到残留的视频纹理，
 * 表现为固定几个 GUI 材质被视频画面污染。
 *
 * <p>调用时机：VideoPlayerMixin 在 lambda$display$0 尾部已用原生 GL 恢复
 * 绑定后，调用本类把缓存改成同样的值，使缓存与真实状态重新一致。
 *
 * <p>本类必须是普通类（不能在 mixin 包里放实现逻辑被注入代码引用）。
 */
public final class GlStateSync {

    private GlStateSync() {
    }

    /**
     * 同步缓存。
     * @param glActiveUnit 原生 GL_ACTIVE_TEXTURE 枚举值（GL_TEXTURE0 + n）
     * @param binding2D    该单元当前真实绑定的 GL_TEXTURE_2D 纹理名
     */
    public static void sync(int glActiveUnit, int binding2D) {
        try {
            int unit = glActiveUnit - GL13.GL_TEXTURE0;
            if (unit < 0) {
                return;
            }
            GlStateManagerAccessor.mediafix$setActiveTexture(unit);
            Object[] textures = GlStateManagerAccessor.mediafix$getTextures();
            if (textures != null && unit < textures.length) {
                ((TextureStateAccessor) textures[unit]).mediafix$setBinding(binding2D);
            }
        } catch (Throwable ignored) {
            // 同步失败只意味着回到原状（可能污染），绝不能因此抛异常影响播放
        }
    }
}
