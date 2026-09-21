package dev.mediafix.mixin;

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
 * 这里在该方法执行前后保存并恢复 GL_ACTIVE_TEXTURE 与当前单元的 GL_TEXTURE_BINDING_2D。
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

    /** 线程局部保存“上传前的 GL 纹理状态”，避免跨线程(tm)干扰。 */
    private static final ThreadLocal<int[]> SAVED_STATE = ThreadLocal.withInitial(() -> new int[2]);

    @Inject(method = "lambda$display$0", at = @At("HEAD"))
    private void mediafix$saveGlState(CallbackInfo ci) {
        int[] s = SAVED_STATE.get();
        s[0] = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);      // 当前活动纹理单元
        s[1] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);  // 当前绑定到 GL_TEXTURE_2D 的纹理
    }

    @Inject(method = "lambda$display$0", at = @At("TAIL"))
    private void mediafix$restoreGlState(CallbackInfo ci) {
        int[] s = SAVED_STATE.get();
        GL13.glActiveTexture(s[0]);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, s[1]);
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
