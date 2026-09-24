package dev.mediafix.mixin;

import dev.mediafix.MediaFix;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.proxy.DashResolver;
import dev.polaris_light.bilibili_media.util.BilibiliShortLinkMediaPlayResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.network.patchs.AbstractPatch;

import java.net.URI;

/**
 * 番剧/大会员内容的高清缓存拦截：bilibili_media 对番剧链接(bangumi/play/epXXX)
 * 使用独立的 {@code BilibiliBangumiPatch} 解析器（与普通视频的 BilibiliPatch 是两个
 * 类，NetworkAPI 按注册顺序命中），因此 BilibiliPatchMixin 拦不到番剧。
 *
 * <p>这里在 BilibiliBangumiPatch.patch 入口同样拦截：ep 链接交给 DashResolver 的
 * pgc 链路（进度提示/4K/杜比/独立重试，见 DashResolver.resolveBangumi）；
 * 失败则放行原逻辑（番剧 mp4 直链整文件下载，约1080p）。
 */
@Mixin(targets = "dev.polaris_light.bilibili_media.util.BilibiliBangumiPatch")
public abstract class BilibiliBangumiPatchMixin {

    @Inject(method = "patch",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private void mediafix$dashBangumi(URI uri, AbstractPatch.Quality prefQuality,
                                      CallbackInfoReturnable<AbstractPatch.Result> cir) {
        try {
            URI longUri = uri;
            if (uri.toString().contains("b23.tv")) {
                longUri = BilibiliShortLinkMediaPlayResolver.expand(uri);
            }
            // 只处理 ep 番剧链接；resolve 内部对 ep 走 pgc 链路（不需要 BilibiliPatch 实例）
            if (StreamConfig.highres && DashResolver.parseEpId(longUri.toString()) != null) {
                AbstractPatch.Result dash = DashResolver.resolve(longUri);
                if (dash != null) {
                    cir.setReturnValue(dash);
                    MediaFix.LOGGER.info("[mediafix] 番剧已切换为 DASH 流式直连(边下边播)");
                    return;
                }
            }
            // DASH 失败/非 ep 链接：放行，走前置原生番剧下载逻辑
            MediaFix.LOGGER.warn("[mediafix] 番剧 DASH 解析失败，回退前置原生下载逻辑");
        } catch (Throwable t) {
            // 任何异常都回退到原生逻辑，保证能播放
            MediaFix.LOGGER.warn("[mediafix] 番剧 DASH 链路异常，回退原生逻辑", t);
        }
    }
}