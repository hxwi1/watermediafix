package dev.mediafix.mixin;

import dev.mediafix.bridge.TimeSyncAware;
import dev.mediafix.config.SeekGuardConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
     * <p>这里 <code>@Redirect</code> tick 里唯一那处 <code>seekTo(J)V</code> 调用，以及
     * {@code setPauseMode} 里的那一处，判据是<b>来源 + 目标</b>两条：
     * <ol>
     *   <li><b>服务端下发过新位置就放行</b> —— {@link TimePacketMixin} 打戳，
     *       {@link #mediafix$serverTimeSynced()} 判断。拖动进度条、遥控器快进/回退、
     *       别的玩家在服务端改进度，都走这条路。这是"在服务器播放时要和服务端进度对齐"的唯一通道，
     *       拦下来就等于永远对不上（实测日志 08:19:25 连续拦过三次）；</li>
     *   <li><b>没有下发、又是"往回跳一大截到 0"的，才继续拦</b> —— 那正是上面那条 floorMod/tick=0
     *       的 bug，也是这个守卫存在的唯一理由。目标非 0 的往回跳属于"有人在改进度"，放行。</li>
     * </ol>
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

    /**
     * "服务端刚下发过播放位置"的放行窗口（毫秒）。
     *
     * <p>拖动进度条时每一步都会来一个 {@code TimePacket}，窗口只需覆盖"下发 → waterframes
     * 下一 tick 用新的 tick 重算目标"这之间的延迟；实测这一跳在 100ms 内。
     */
    private static final long MEDIAFIX_SYNC_WINDOW_MS = 3000L;

    /**
     * 只有"目标 ≈ 0"才算那条记录在案的陈旧同步（毫秒）。
     *
     * <p>{@code Display.tick()} 把目标算成 0 只有两条路：{@code data.tick/tickMax} 瞬时为 0，
     * 或 {@code getMediaInfoDuration()} 为 0 时那条 {@code floorMod} 分支。它们的产物都是 0。
     */
    private static final long MEDIAFIX_ZERO_GLITCH_MS = 1000L;

    /** 上一条"跟随服务端同步"日志的时间（限流）。 */
    @Unique private long mediafix$lastFollowLogAt;

    /** 上一条"waterframes 要求暂停/播放"日志的时间（限流）。 */
    @Unique private long mediafix$lastPauseLogAt;

    @Redirect(
            method = "setPauseMode(Z)V",
            at = @At(value = "INVOKE", target = "org/watermedia/api/player/videolan/VideoPlayer.seekTo(J)V")
    )
    private void mediafix$guardStaleSyncOnPause(VideoPlayer player, long target, boolean pause) {
        /*
         * 先把"谁让我们暂停/播放的"记下来。waterframes 只有三处会调 setPauseMode：
         * Display.tick()（data.paused / !data.active / MC 暂停菜单）、
         * switchVideoMode()（换源时把 data.paused 套给新播放器）、PausePacket（遥控器）。
         * 不记这一条，"播放器怎么一直暂停着"就只能靠猜 —— 实测 11:09 切到直播源后
         * 引擎整场都是 PAUSED，事后完全看不出是方块数据本来就是暂停的。
         */
        long logNow = System.currentTimeMillis();
        if (logNow - this.mediafix$lastPauseLogAt > 500L) {
            this.mediafix$lastPauseLogAt = logNow;
            var d = this.tile != null ? this.tile.data : null;
            dev.mediafix.MediaFix.LOGGER.info(
                    "[mediafix] waterframes 要求{}：方块数据 paused={} active={} tick={}（目标 {}ms）",
                    pause ? "暂停" : "播放", d != null && d.paused, d != null && d.active,
                    d != null ? d.tick : -1, target);
        }
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
     * 片尾的正常循环重播、以及<b>服务端刚下发过新位置</b>的往回跳。
     * 只有"服务端没说、又往回跳到 ≈0"才判为陈旧同步。
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

        // 向前跳（同步/正常拖动）、微小抖动、片尾正常循环重播：一律放行
        if (target >= current || Math.abs(target - current) <= th || atEnd) {
            return false;
        }
        /*
         * ★ 服务端说了算：有人（自己或别的玩家）在服务端把进度移到了别处，服务端会下发
         * TimePacket，那这次的"往回跳"就是它的意思，必须放行。
         *
         * 以前这里不看来源，只要"往回跳超过阈值"就拦 —— 实测日志 08:19:25 里
         * 目标 2059150ms / 823650ms / 0ms 连着被拦了三次（当前 2562500ms），
         * 表现就是"在服务端播放时，进度和服务端对不上"。
         */
        if (mediafix$serverTimeSynced()) {
            long now = System.currentTimeMillis();
            if (now - this.mediafix$lastFollowLogAt > 1000L) {
                this.mediafix$lastFollowLogAt = now;
                dev.mediafix.MediaFix.LOGGER.info(
                        "[mediafix] 跟随服务端进度（{}）：目标 {}ms，当前 {}ms（差 {}ms）",
                        from, target, current, target - current);
            }
            return false;
        }
        /*
         * 走到这里说明：往回跳、且服务端并没下发过新位置。剩下唯一记录在案的成因，
         * 就是 waterframes 自己把目标算成了 0（见 MEDIAFIX_ZERO_GLITCH_MS）。
         * 非 0 的往回跳不做拦截 —— 那属于"有人在改进度"，拦下来只会让我们和进度对不上。
         */
        if (target > MEDIAFIX_ZERO_GLITCH_MS) {
            return false;
        }
        dev.mediafix.MediaFix.LOGGER.info(
                "[mediafix] 拦下一次陈旧的{} seek：目标 {}ms，当前 {}ms（差 {}ms）",
                from, target, current, target - current);
        return true;
    }

    /** 服务端最近几秒内给这个显示方块下发过新的播放位置（TimePacket）。 */
    @Unique
    private boolean mediafix$serverTimeSynced() {
        return this.tile instanceof TimeSyncAware aware
                && System.currentTimeMillis() - aware.mediafix$lastServerSyncAt() < MEDIAFIX_SYNC_WINDOW_MS;
    }
}