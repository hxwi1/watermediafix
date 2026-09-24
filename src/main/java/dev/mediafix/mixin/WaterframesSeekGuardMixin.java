package dev.mediafix.mixin;

import dev.mediafix.config.SeekGuardConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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

    /**
     * 第二个必须挡住的地方：{@code Display.setPauseMode(boolean)}。
     *
     * <p>它的实现是【先 seek 再改暂停状态】：
     * <pre>player.seekTo(MathAPI.tickToMs(tile.data.tick)); player.setPauseMode(pause); …</pre>
     * 也就是同步到"显示方块自己那份进度"。而实测 {@code Display.tick()} 在部分场景下
     * 每 2 秒才被调用一次（0.5Hz），那份进度几乎停在原地 —— 于是每次恢复播放都会把
     * 正在播的视频拽回开头（日志里那句"无故跳回开头"就是这么来的），
     * 顺带把音频缓冲也冲掉。
     *
     * <p>这里用与 tick 相同的"陈旧同步"判据把它挡掉；玩家自己拖动进度条走的是
     * {@code Display.forceSeek()}，不受影响。
     */
    @Shadow @Final private me.srrapero720.waterframes.common.block.entity.DisplayTile tile;

    @Redirect(
            method = "setPauseMode(Z)V",
            at = @At(value = "INVOKE", target = "org/watermedia/api/player/videolan/VideoPlayer.seekTo(J)V")
    )
    private void mediafix$guardStaleSyncOnPause(VideoPlayer player, long target, boolean pause) {
        /*
         * 用户主动暂停/停止必须完全放行。
         *
         * 遥控器的正方形 STOP 按钮发的是 PausePacket(paused=true, tick=0)：它同样会走到
         * Display.setPauseMode(true) → 先 seekTo(tickToMs(0)) 再暂停。这是用户的明确意图
         * （"停止 = 回到开头并停住"），一旦被当成陈旧同步拦掉，表现就是"点了停止按钮没效果"。
         *
         * 判据用 data.paused：只有用户（或命令/红石主控）真的把暂停写进显示数据时才会为 true；
         * 而 MC 暂停菜单、显示不在激活范围、服务端 tick 陈旧造成的同步暂停都不会置位它。
         */
        if (pause && this.tile != null && this.tile.data.paused) {
            player.seekTo(target);
            return;
        }
        if (mediafix$isStaleSync(player, target, "暂停同步")) {
            return;
        }
        player.seekTo(target);
    }

    @Redirect(
            method = "tick()V",
            at = @At(value = "INVOKE", target = "org/watermedia/api/player/videolan/VideoPlayer.seekTo(J)V")
    )
    private void mediafix$guardStaleReset(VideoPlayer player, long target) {
        if (mediafix$isStaleSync(player, target, "tick 同步")) {
            return;
        }
        player.seekTo(target);
    }

    /**
     * 判定一次 waterframes 内部进度同步是否属于"陈旧的往回跳"。
     *
     * <p><b>必须用引擎时钟当基准</b>：引擎接管后，waterframes 那个 VLC 播放器只是个壳，
     * {@code getTime()} 在暂停/缓冲期可能是 0 —— 早先守卫就是拿它比，于是"当前=0"
     * 让所有判据失效，seekTo(0) 被放行，视频跳回开头（日志里 05:47:51 那次就是这么漏的）。
     *
     * <p>放行规则：向前跳（同步/正常拖动）、微小抖动（引擎侧还会再滤一次原地 seek）、
     * 以及片尾的正常循环重播。只有"往回跳一大截"才判为陈旧同步。
     */
    private boolean mediafix$isStaleSync(VideoPlayer player, long target, String from) {
        long current = dev.mediafix.engine.MediaEngines.enginePositionOf(player);
        boolean engineOwned = current >= 0L;
        if (!engineOwned) {
            current = player.getTime();
        }
        if (current <= 0L) {
            return false;
        }
        // 片尾判定用时长：引擎接管时用引擎自己的（VLC 壳在暂停/缓冲期 duration 常为 0）
        long duration = engineOwned ? dev.mediafix.engine.MediaEngines.engineDurationOf(player)
                : player.getMediaInfoDuration();
        if (duration <= 0L) {
            duration = player.getMediaInfoDuration();
        }
        boolean atEnd = duration > 0L && current >= duration - 2000L;
        long th = SeekGuardConfig.thresholdMs;

        if (target >= current || Math.abs(target - current) <= th || atEnd) {
            return false;
        }
        dev.mediafix.MediaFix.LOGGER.info(
                "[mediafix] 拦下一次陈旧的{} seek：目标 {}ms，当前 {}ms（差 {}ms）",
                from, target, current, target - current);
        return true;
    }
}