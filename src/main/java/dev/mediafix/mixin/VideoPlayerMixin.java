package dev.mediafix.mixin;

import dev.mediafix.render.GlStateSync;
import dev.mediafix.render.Letterbox;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * 两个修复（都作用于 org.watermedia VideoPlayer）：
 *
 * <p><b>1. 画面变成某玩家的皮肤贴图（bug3）</b>
 * <p>根因：watermedia 的 VideoPlayer.lambda$display$0() 每帧在“当前活动纹理单元”上
 * 调用 RenderAPI.bindTexture/uploadBuffer 上传视频帧，但既不恢复 GL_TEXTURE_BINDING_2D，
 * 也不恢复 GL_ACTIVE_TEXTURE，把 Minecraft 后续渲染所依赖的贴图绑定状态污染，
 * 导致屏幕误采样到上一个输入的纹理（如玩家皮肤贴图层）。
 * 这里在该方法执行前后保存并恢复 GL_ACTIVE_TEXTURE、当前单元的 GL_TEXTURE_BINDING_2D，
 * 以及 uploadBuffer 会硬改而不恢复的三个解包状态(UNPACK_ROW_LENGTH/SKIP_ROWS/SKIP_PIXELS)，
 * 保证视频帧上传后不再污染 Minecraft 后续的世界与 UI 渲染。
 *
 * <p><b>2. 自适应比例：不拉伸、允许黑边（信箱化）</b>
 * <p>VideoPlayer.lambda$display$0() 把视频帧原样上传为 GL 纹理，渲染时整张贴图被
 * 铺满任意宽高比的屏幕方块 → 视频被拉伸。这里拦截其中的
 * {@code RenderAPI.uploadBuffer} 调用，转发给 {@link Letterbox#upload}：
 * 按屏幕格比例（见 DisplayAspectMixin / LetterboxState）把视频帧居中拷入等比
 * 黑底画布再上传。信箱化实现放在普通类里——mixin 内部类不能被注入代码引用。
 */
@Mixin(targets = "org.watermedia.api.player.videolan.VideoPlayer")
public abstract class VideoPlayerMixin {

    /** 线程局部保存“上传前的 GL 纹理状态”，避免跨线程(tm)干扰。
     *  布局: [0]=GL_ACTIVE_TEXTURE [1]=当前单元 GL_TEXTURE_BINDING_2D
     *        [2]=GL_UNPACK_ROW_LENGTH [3]=GL_UNPACK_SKIP_ROWS [4]=GL_UNPACK_SKIP_PIXELS。 */
    private static final ThreadLocal<int[]> SAVED_STATE = ThreadLocal.withInitial(() -> new int[5]);

    @Inject(method = "lambda$display$0", at = @At("HEAD"))
    private void mediafix$saveGlState(CallbackInfo ci) {
        int[] s = SAVED_STATE.get();
        // uploadBuffer 会在“当前活动纹理单元”上绑定视频纹理并上传，
        // 且把像素存储(解包)状态硬写为 0 而不恢复；这里连同这些一起保存。
        s[0] = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        s[1] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        s[2] = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        s[3] = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        s[4] = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
    }

    @Inject(method = "lambda$display$0", at = @At("TAIL"))
    private void mediafix$restoreGlState(CallbackInfo ci) {
        int[] s = SAVED_STATE.get();
        // 恢复活动纹理单元与其绑定 —— 否则视频纹理残留在该单元，后续 UI 采样误读到它。
        GL13.glActiveTexture(s[0]);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, s[1]);
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, s[2]);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, s[3]);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, s[4]);
        // 关键：MC 的 GlStateManager 绑定缓存被原生 GL 调用绕过后与真实状态脱节，
        // 后续 MC 因缓存命中跳过 bind → 固定几个 GUI 材质采样到视频纹理（F3+T 才恢复）。
        // 这里把缓存同步回真实状态，从根上消除 GUI 污染。
        GlStateSync.sync(s[0], s[1]);
    }

    /** 信箱化上传：转发给 Letterbox（实现见 dev.mediafix.render.Letterbox）。 */
    @Redirect(
            method = "lambda$display$0",
            at = @At(value = "INVOKE",
                    target = "org/watermedia/api/render/RenderAPI.uploadBuffer(Ljava/nio/ByteBuffer;IIIIZ)V")
    )
    private static void mediafix$letterboxUpload(ByteBuffer data, int texId, int format,
                                                 int w, int h, boolean full) {
        Letterbox.upload(data, texId, format, w, h, full);
    }
}
