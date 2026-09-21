package dev.mediafix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 修复 bug2（多人播放变黑）。
 *
 * <p>原 SimpleFileServer：
 *   <ul>
 *     <li>enableRangeRequests 默认 false —— 走 sendFullFile 整文件一次性阻塞拷贝。
 *         WaterMedia 播放期间会"先中断首次请求再二次请求"（据此 seek/前进），
 *         无 Range 时每次都得重新整份下载,并发播放时互相干扰导致画面变黑。</li>
 *     <li>线程池仅 12, 并发压力下吞吐不足。</li>
 *   </ul>
 *
 * <p>这里通过重定向：
 *   <ul>
 *     <li>把 startServer() 读取静态字段 enableRangeRequests 的值改为 true，从而让
 *         FileDownloadHandler 走支持 Range 的 206 分片响应。</li>
 *     <li>把 Executors.newFixedThreadPool 的线程数上调, 提升并发吞吐。</li>
 *   </ul>
 */
@Mixin(targets = "dev.polaris_light.bilibili_media.util.SimpleFileServer")
public abstract class SimpleFileServerMixin {

    /** 本地下载服务器开启 Range 断点续传, 修复多人播放时整份重传导致的黑屏。 */
    @Redirect(
            method = "startServer",
            at = @At(
                    value = "FIELD",
                    target = "Ldev/polaris_light/bilibili_media/util/SimpleFileServer;"
                            + "enableRangeRequests:Z"
            )
    )
    private static boolean mediafix$enableRange() {
        return true;
    }

    /** 扩大本地服务器线程池, 从 12 提升并发吞吐。 */
    @Redirect(
            method = "startServer",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/concurrent/Executors;"
                            + "newFixedThreadPool(I)Ljava/util/concurrent/ExecutorService;"
            )
    )
    private static ExecutorService mediafix$biggerPool(int requested) {
        return Executors.newFixedThreadPool(Math.max(48, requested));
    }
}