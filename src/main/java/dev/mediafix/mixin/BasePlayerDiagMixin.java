package dev.mediafix.mixin;

import dev.mediafix.MediaFix;
import dev.mediafix.config.FfmpegConfig;
import dev.mediafix.engine.MediaEngines;
import dev.mediafix.ffmpeg.FfmpegRuntime;
import dev.mediafix.ffmpeg.FfmpegSources;
import dev.mediafix.proxy.DashHandoff;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.watermedia.videolan4j.player.base.MediaApi;

import java.net.URI;
import java.util.Arrays;

/**
 * 播放入口：{@code BasePlayer.lambda$start$0} 是这个播放器真正"开播"的地方
 * （解析完 URL 之后、调用 VLC 之前）。
 *
 * <p>这里做两件事：
 * <ol>
 *   <li><b>引擎优先</b>：启用 FFmpeg 时不再启动 VLC，改由自研引擎播放
 *       （音视频各自解码 + 自有时钟）。VLC 只剩一个"挂着的空壳"，
 *       因为 waterframes 有一处会直接摸 {@code raw()} 的状态。</li>
 *   <li><b>VLC 兜底</b>：引擎不可用时走原路径，含 DASH 分轨的 {@code :input-slave} 音频桥。</li>
 * </ol>
 */
@Mixin(targets = "org.watermedia.api.player.videolan.BasePlayer")
public abstract class BasePlayerDiagMixin {

    @Redirect(
            method = "lambda$start$0",
            at = @At(value = "INVOKE",
                    target = "org/watermedia/videolan4j/player/base/MediaApi.play(Ljava/net/URI;[Ljava/lang/String;)Z")
    )
    private boolean mediafix$playViaEngineOrVlc(MediaApi api, URI mrl, String[] options) {
        // 解析阶段放在线程内的交接棒：先取走，无论走哪条路都不留残留
        String handoffVideo = DashHandoff.takeVideo();
        String handoffAudio = DashHandoff.takeAudio();
        String resultAudio = DashHandoff.takeResultAudio();
        boolean liveSession = DashHandoff.takeLive();   // 直播：seek/ABR/重连行为都不同
        DashHandoff.clear();

        // ---------- 1) 自研引擎 ----------
        if (FfmpegRuntime.available()) {
            try {
                URI videoUri = mediafix$parse(handoffVideo);
                URI providedAudio = mediafix$parse(handoffAudio);
                if (providedAudio == null) providedAudio = mediafix$parse(resultAudio);

                // 关键：解析发生在别的线程、而且结果可能被 watermedia 缓存命中，
                // 这时上面的线程内交接棒是空的 —— 按"播放器拿到的 URI"反查两条链。
                // 直播标记同理（它也只在解析线程的交接棒里），所以要一并从全局表补上。
                var pair = DashHandoff.lookup(mrl.toString());
                if (pair != null) {
                    if (!liveSession && pair.live()) liveSession = true;
                    if (videoUri == null || providedAudio == null) {
                        if (videoUri == null) videoUri = mediafix$parse(pair.video());
                        if (providedAudio == null) providedAudio = mediafix$parse(pair.audio());
                        MediaFix.LOGGER.info("[mediafix] 由全局交接表取回两条链: video={} audio={}",
                                MediaFix.LOGGER.url(pair.video()), MediaFix.LOGGER.url(pair.audio()));
                    }
                }
                if (videoUri == null) videoUri = mrl;
                URI audioUri = MediaEngines.resolveAudioUri(videoUri, providedAudio);

                if (MediaEngines.create(this, videoUri, audioUri, FfmpegSources.buildHeaders(videoUri), liveSession) != null) {
                    MediaFix.LOGGER.info("[mediafix] 自研引擎接管播放: video={} audio={}",
                    MediaFix.LOGGER.url(videoUri), MediaFix.LOGGER.url(audioUri));
                    return true;   // 告诉上游"开播成功"，但 VLC 不再参与
                }
                MediaFix.LOGGER.warn("[mediafix] 引擎创建失败，回退 VLC 播放");
            } catch (Throwable t) {
                MediaFix.LOGGER.error("[mediafix] 引擎接管异常，回退 VLC 播放", t);
            }
        }

        // ---------- 2) VLC 兜底（原逻辑：DASH 独立音轨用 input-slave 挂载） ----------
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
        MediaFix.LOGGER.info("[mediafix] VLC 播放: mrl={} 返回={}", mrl, ok);
        return ok;
    }

    private static URI mediafix$parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return URI.create(s);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 为"本地成对文件"推导配套音频的 URI：{@code xxx_v.mp4} → 同目录的 {@code xxx_a.m4a}。
     *
     * <p>流式直连时音频链由 DashResolver 的 {@code result.audioUrl} 直接给出，不走这里；
     * 这里只服务于玩家放在本地的成对文件（他们自己的素材）。
     */
    private static URI mediafix$deriveDashAudio(URI mrl) {
        try {
            if (!mrl.toString().endsWith("_v.mp4")) {
                return null;
            }
            if ("file".equalsIgnoreCase(mrl.getScheme())) {
                java.nio.file.Path video = java.nio.file.Path.of(mrl);
                String fileName = video.getFileName().toString();
                String aName = fileName.substring(0, fileName.length() - "_v.mp4".length()) + "_a.m4a";
                java.nio.file.Path audio = video.resolveSibling(aName);
                if (!java.nio.file.Files.exists(audio)) return null;
                return audio.toUri();
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
