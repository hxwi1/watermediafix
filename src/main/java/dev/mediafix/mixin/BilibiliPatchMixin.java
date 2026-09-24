package dev.mediafix.mixin;

import dev.mediafix.MediaFix;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.proxy.DashResolver;
import dev.polaris_light.bilibili_media.util.BilibiliPatch;
import dev.polaris_light.bilibili_media.util.BilibiliShortLinkMediaPlayResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.network.patchs.AbstractPatch;

import java.net.URI;

/**
 * 高清缓存播放：在 {@code BilibiliPatch.patch(uri, quality)} 入口处拦截。
 * 当开关打开时，走 DASH 高清链路（4K/8K/杜比，带 wbi 签名）——先下载到
 * 前置 mod 的缓存目录（下载进度实时显示在 actionbar），再经其 9095 本地
 * 服务器播放；DASH 失败则放行原逻辑（durl 整文件下载，约 1080p），保证始终可播。
 *
 * <p>登录清晰度：下载请求带上 BiliCookieStore 中的已登录 Cookie（见 DashResolver），
 * 登录本身仍沿用 bilibili_media 自带的 {@code /bilimedia login} 扫码，不做新登录。
 */
@Mixin(targets = "dev.polaris_light.bilibili_media.util.BilibiliPatch")
public abstract class BilibiliPatchMixin {

    @Inject(method = "patch",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void mediafix$streamingPatch(URI uri, AbstractPatch.Quality prefQuality,
                                         CallbackInfoReturnable<AbstractPatch.Result> cir) {
        try {
            URI longUri = uri;
            if (uri.toString().contains("b23.tv")) {
                longUri = BilibiliShortLinkMediaPlayResolver.expand(uri);
            }
            BilibiliPatch self = (BilibiliPatch) (Object) this;

            // DASH 高清链路（能拿多高清拿多高清，受 /mediafix-stream quality 上限约束）
            if (StreamConfig.highres) {
                AbstractPatch.Result dash = DashResolver.resolve(longUri);
                if (dash != null) {
                    cir.setReturnValue(dash);
                    MediaFix.LOGGER.info("[mediafix] 已切换为 DASH 流式直连(边下边播)");
                    return;
                }
            }
            // DASH 失败：放行，走 bilibili_media 原生 durl 下载逻辑
            MediaFix.LOGGER.warn("[mediafix] DASH 解析失败，回退前置原生下载逻辑");
        } catch (Throwable t) {
            // 任何异常都回退到 bilibili_media 原逻辑，保证能播放
            MediaFix.LOGGER.warn("[mediafix] DASH 链路异常，回退原生逻辑", t);
        }
    }
}