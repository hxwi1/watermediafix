package dev.mediafix.mixin;

import dev.mediafix.MediaFix;
import dev.mediafix.proxy.DashAudioBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.watermedia.api.network.patchs.AbstractPatch;

import java.net.URI;

/**
 * 诊断 + 音频桥暂存：NetworkAPI.patch 是 ImageFetch 与 VideoPlayer 共用的
 * 解析入口（带 Result 缓存）。打印每次 patch 的入参与出参，确认播放器拿到的
 * 到底是代理 URL 还是原始链接；同时把 Result.audioUrl 暂存到 DashAudioBridge，
 * 供 BasePlayer 播放时以 input-slave 挂载音频轨。
 */
@Mixin(targets = "org.watermedia.api.network.NetworkAPI")
public abstract class NetworkApiDiagMixin {

    @Inject(
            method = "patch(Ljava/net/URI;)Lorg/watermedia/api/network/patchs/AbstractPatch$Result;",
            at = @At("RETURN")
    )
    private static void mediafix$logPatch(URI uri, CallbackInfoReturnable<AbstractPatch.Result> cir) {
        AbstractPatch.Result r = cir.getReturnValue();
        if (r != null && r.audioUrl != null) {
            DashAudioBridge.set(r.audioUrl);
            // 自研引擎要用它当音频链（下载模式下是本地 m4a）
            dev.mediafix.proxy.DashHandoff.setResultAudio(r.audioUrl.toString());
        }
        MediaFix.LOGGER.diag("[mediafix][diag] NetworkAPI.patch: in={} out={} audio={} 线程={}",
                uri,
                r == null ? "null" : r.uri,
                r == null || r.audioUrl == null ? "-" : r.audioUrl,
                Thread.currentThread().getName());
    }
}