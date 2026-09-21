package dev.mediafix.mixin;

import dev.mediafix.config.SeekGuardConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.watermedia.api.player.videolan.VideoPlayer;

/**
 * 修复 waterframes 本来就有的进度 bug：个别客户端播放中的视频会随机“跳回开头重新播放”，
 * 而服务端/其它玩家看到的进度都正常（纯客户端、随机）。
 *
 * <p>根因（<code>me.srrapero720.waterframes.client.display.Display.tick()V</code> 的 VIDEO 分支）：
 *   <ul>
 *     <li>目标进度 <code>l = tickToMs(data.tick) + delta</code>；</li>
 *     <li>当 <code>data.tick</code> 因方块/分块重同步短暂为 0，或缓冲期
 *         <code>getMediaInfoDuration()</code> 瞬时为 0 时，<code>l</code> 被算成 0；</li>
 *     <li>双闸条件 <code>|l-getTime()|&gt;TH 且 |l-currentLastTime|&gt;TH</code> 一成立，
     *         就 <code>seekTo(0)</code>，把已经播了一段(超过阈值 TH)的视频拉回开头。</li>
     *   </ul>
     *
     * <p>这里 <code>@Redirect</code> tick 里唯一那处 <code>seekTo(J)V</code> 调用：仅拦截
     * “目标为 0 而本地已播放超过阈值（默认为 5 秒，见 {@link SeekGuardConfig}）且未到片尾”的
     * 陈旧同步（真正的循环/重播发生在片尾，予以放行）。其余 seek（包括玩家正常拖动进度条、
     * 其他玩家同步拖动）全部放行，交由 waterframes 自身的 2000ms 阈值过滤微小抖动。
     * 阈值可用 /mediafix seekguard &lt;秒&gt; 实时调整。
     */
@Mixin(targets = "me.srrapero720.waterframes.client.display.Display")
public abstract class WaterframesSeekGuardMixin {

    @Redirect(
            method = "tick()V",
            at = @At(value = "INVOKE", target = "org/watermedia/api/player/videolan/VideoPlayer.seekTo(J)V")
    )
    private void mediafix$guardStaleReset(VideoPlayer player, long target) {
        long current = player.getTime();
        long duration = player.getMediaInfoDuration();
        boolean atEnd = duration > 0L && current >= duration - 2000L;
        long th = SeekGuardConfig.thresholdMs;

        // 仅拦截“陈旧同步回 0”：目标为 0、本地已播超阈值、且不在片尾（正常循环/重播）。
        // 其余一切 seek（拖动进度条、其他玩家同步）直接放行。
        if (target <= 0L && current > th && !atEnd) {
            return;
        }
        player.seekTo(target);
    }
}