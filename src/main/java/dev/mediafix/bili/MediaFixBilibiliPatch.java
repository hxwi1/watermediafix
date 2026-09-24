package dev.mediafix.bili;

import dev.mediafix.MediaFix;
import dev.mediafix.config.StreamConfig;
import dev.mediafix.proxy.DashResolver;
import org.watermedia.api.network.patchs.AbstractPatch;

import java.net.URI;

/**
 * 自己实现并注册的 B 站解析器（不再依赖 bilibili_media）。
 *
 * <p>替换了什么：原方案是给 bilibili_media 的 {@code BilibiliPatch.patch()} 打 Mixin 去拦截；
 * 现在直接用 watermedia 的公开扩展点 {@code NetworkAPI.registerPatch()} 注册本类，
 * 由它调用 {@link DashResolver} 完成 DASH 解析。这样即使玩家没装 bilibili_media，
 * B 站链接也照常能解析。
 *
 * <p>{@code isValid} 只认 B 站域名，绝不会抢别的链接；解析失败时退回"当视频处理"，
 * 让玩家看到明确的失败状态，而不是把网页当图片去 decode。
 */
public final class MediaFixBilibiliPatch extends AbstractPatch {

    public static final String PLATFORM = "MediaFix-BiliBili";

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public boolean isValid(URI uri) {
        return BiliUrls.isBilibili(uri);
    }

    @Override
    public Result patch(URI uri, Quality prefQuality) {
        // 高清链路关闭时不做解析，但仍当视频处理（保持"能播就播、不能播看得见"）
        if (!StreamConfig.highres) {
            return new Result(uri, true, false);
        }
        try {
            URI longUri = BiliUrls.expandShortLink(uri);
            Result result = DashResolver.resolve(longUri);
            if (result != null) {
                return result;
            }
            MediaFix.LOGGER.warn("[mediafix] B 站解析失败（无可用流），回退为直连尝试: {}", longUri);
        } catch (Throwable t) {
            MediaFix.LOGGER.warn("[mediafix] B 站解析异常: {}", uri, t);
        }
        return new Result(uri, true, false);
    }
}
