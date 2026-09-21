package dev.mediafix.mixin;

import dev.mediafix.config.CommentHide;
import net.minecraft.client.Minecraft;
import neoforge.cn.zbx1425.worldcomment.data.CommentEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * WorldComment 评论全隐藏：拦截 {@code ClientConfig.isCommentVisible(Minecraft,CommentEntry)Z}。
 *
 * <p>世界空间评论的渲染统一走 {@code CommentWorldRenderer.renderComments}，它逐条调用
 * {@code isCommentVisible} 判断是否显示。这里 HEAD 拦截：当 {@link CommentHide#enabled} 开启时
 * 直接返回 false，从而在客户端本地隐藏全部评论，不影响服务端和其它玩家；关闭则走原逻辑。
 *
 * <p>仅限 neoforge 版本的 WorldComment（目标类带 neoforge 前缀），注入失败即崩溃以便排查。
 */
@Mixin(targets = "neoforge.cn.zbx1425.worldcomment.ClientConfig")
public abstract class CommentHideMixin {

    @Inject(
            method = "isCommentVisible(Lnet/minecraft/client/Minecraft;Lneoforge/cn/zbx1425/worldcomment/data/CommentEntry;)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 1
    )
    private void mediafix$hideAllComments(
            Minecraft mc,
            CommentEntry entry,
            CallbackInfoReturnable<Boolean> cir) {
        if (CommentHide.enabled) {
            cir.setReturnValue(false);
        }
    }
}