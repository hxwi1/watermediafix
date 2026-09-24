package dev.mediafix.render;

import dev.mediafix.MediaFix;
import org.lwjgl.opengl.GL13;

import java.lang.reflect.Array;
import java.lang.reflect.Field;

/**
 * 把 Minecraft {@code GlStateManager} 的纹理绑定缓存同步为"当前真实 GL 状态"。
 *
 * <p>背景：watermedia 的视频帧上传在渲染线程之外直接调原生 glBindTexture，MC 的缓存
 * （{@code TEXTURES[n].binding} / {@code activeTexture}）不知情，之后 MC 画 GUI 时因缓存
 * 命中而跳过真正的 bind，就会采样到残留的视频纹理。
 *
 * <p>为什么用反射而不是 Mixin Accessor：{@code TEXTURES} 的元素类型是包私有内部类
 * {@code GlStateManager$TextureState}，Accessor 方法的描述符对不上（实测注入失败）。
 *
 * <p>说明：MediaFix 的上传路径会在前后<b>精确保存并还原</b>真实 GL 状态，所以正常情况下
 * 缓存不会脱节，这里只是双保险；反射不可用时静默跳过，绝不影响播放。
 */
public final class GlStateSync {

    private static boolean resolved;
    private static boolean warned;
    private static Field activeTextureField;
    private static Field texturesField;
    private static Field bindingField;
    private static java.lang.reflect.Method textureStateBinding;   // 兜底：字段拿不到就找方法

    private GlStateSync() {
    }

    /**
     * @param glActiveUnit 原生 GL_ACTIVE_TEXTURE 枚举值（GL_TEXTURE0 + n）
     * @param binding2D    该单元当前真实绑定的 GL_TEXTURE_2D 纹理名
     */
    public static void sync(int glActiveUnit, int binding2D) {
        try {
            int unit = glActiveUnit - GL13.GL_TEXTURE0;
            if (unit < 0) return;
            if (!resolved) resolve();
            if (activeTextureField != null) {
                activeTextureField.setInt(null, unit);
            }
            if (texturesField != null && bindingField != null) {
                Object textures = texturesField.get(null);
                if (textures != null && unit < Array.getLength(textures)) {
                    Object state = Array.get(textures, unit);
                    if (state != null) {
                        bindingField.setInt(state, binding2D);
                    }
                }
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                MediaFix.LOGGER.debug("[mediafix] GL 绑定缓存同步不可用（不影响播放）: {}", String.valueOf(t));
            }
        }
    }

    private static synchronized void resolve() {
        resolved = true;
        try {
            Class<?> gsm = Class.forName("com.mojang.blaze3d.platform.GlStateManager");
            activeTextureField = accessible(gsm, "activeTexture");
            texturesField = accessible(gsm, "TEXTURES");
            if (texturesField != null) {
                Class<?> stateType = texturesField.getType().getComponentType();
                if (stateType != null) bindingField = accessible(stateType, "binding");
            }
        } catch (Throwable t) {
            MediaFix.LOGGER.debug("[mediafix] 解析 GlStateManager 字段失败: {}", String.valueOf(t));
        }
    }

    private static Field accessible(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }
}
