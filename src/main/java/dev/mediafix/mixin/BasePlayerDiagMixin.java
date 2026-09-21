package dev.mediafix.mixin;

import dev.mediafix.MediaFix;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.watermedia.videolan4j.player.base.MediaApi;

import java.net.URI;
import java.util.Arrays;

/**
 * 音频桥（原诊断 Mixin 精简版）：BasePlayer.lambda$start$0 是 VLC 实际开播的地方。
 * watermedia 2.1.36 忽略 Result.audioUrl，而 B站 DASH 视频轨无声——这里从视频缓存
 * URL 推导配套音频文件（mediafix_*_v.mp4 → _a.m4a），以 ":input-slave" 选项
 * 挂给 VLC，实现视频+音频双轨合并播放。
 *
 * <p>注意：不要在此长期持有 MediaApi 引用或做延迟轮询——播放器被释放后
 * 原生结构体随即回收，事后访问会触发 JNA "Invalid memory access"，
 * 严重时直接进程级崩溃（已实测）。
 */
@Mixin(targets = "org.watermedia.api.player.videolan.BasePlayer")
public abstract class BasePlayerDiagMixin {

    @Inject(method = "lambda$start$0", at = @At("HEAD"))
    private void mediafix$logStartThread(URI uri, String[] options, CallbackInfo ci) {
        MediaFix.LOGGER.info("[mediafix][diag] VideoPlayer.start 线程执行: 入参uri={} 线程={}",
                uri, Thread.currentThread().getName());
    }

    @Redirect(
            method = "lambda$start$0",
            at = @At(value = "INVOKE",
                    target = "org/watermedia/videolan4j/player/base/MediaApi.play(Ljava/net/URI;[Ljava/lang/String;)Z")
    )
    private boolean mediafix$logPlayAndAttachAudio(MediaApi api, URI mrl, String[] options) {
        // DASH 音频桥：B站 DASH 视频流无声，从视频 URL 推导同缓存的音频文件
        // (mediafix_<bvid>_<cid>_v.mp4 → _a.m4a)，以 ":input-slave" 选项挂给 VLC
        URI audio = mediafix$deriveDashAudio(mrl);
        String[] finalOptions = options;
        if (audio != null) {
            boolean hasSlave = options != null && Arrays.stream(options)
                    .anyMatch(o -> o != null && o.startsWith(":input-slave"));
            if (!hasSlave) {
                int len = options == null ? 0 : options.length;
                finalOptions = new String[len + 1];
                if (len > 0) {
                    System.arraycopy(options, 0, finalOptions, 0, len);
                }
                finalOptions[len] = ":input-slave=" + audio;
                MediaFix.LOGGER.info("[mediafix] 已挂载 DASH 独立音频轨: {}", audio);
            }
        }
        boolean ok = api.play(mrl, finalOptions);
        MediaFix.LOGGER.info("[mediafix][diag] VLC play: mrl={} 返回={} options={} 线程={}",
                mrl, ok, Arrays.toString(finalOptions), Thread.currentThread().getName());
        return ok;
    }

    /**
     * 从 DASH 缓存视频 URL 推导配套音频文件 URL：
     * {@code .../download?file=mediafix_<bvid>_<cid>_v.mp4} →
     * {@code .../download?file=mediafix_<bvid>_<cid>_a.m4a}。
     * 本地音频文件存在才返回（下载完成才可能有声），否则返回 null。
     */
    private static URI mediafix$deriveDashAudio(URI mrl) {
        try {
            String s = mrl.toString();
            int i = s.indexOf("file=mediafix_");
            if (i < 0 || !s.endsWith("_v.mp4")) {
                return null;
            }
            String vName = s.substring(i + "file=".length());
            String aName = vName.substring(0, vName.length() - "_v.mp4".length()) + "_a.m4a";
            if (!java.nio.file.Files.exists(
                    dev.polaris_light.bilibili_media.util.BilibiliMediaUtil.getDownloadPath().resolve(aName))) {
                return null;
            }
            return URI.create(s.substring(0, i + "file=".length()) + aName);
        } catch (Throwable t) {
            return null;
        }
    }
}
