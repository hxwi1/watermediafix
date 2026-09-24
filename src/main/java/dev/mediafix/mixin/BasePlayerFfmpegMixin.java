package dev.mediafix.mixin;

import dev.mediafix.engine.MediaEngine;
import dev.mediafix.engine.MediaEngines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 播放器状态接管：只要该播放器由自研引擎驱动，所有状态/控制方法都由引擎回答，
 * 不再问 VLC（VLC 那边是"挂着但没播"，状态会误导 waterframes）。
 *
 * <p>两个必须接管的"元方法"，不接管会直接卡死渲染：
 * <ul>
 *   <li>{@code isWaiting()} —— VLC 空闲时恒为 true，而 waterframes 的
 *       {@code Display.canRender()} 要求 {@code !isWaiting()}，不接管就永远不渲染；</li>
 *   <li>{@code isSafeUse()/isValid()} —— VLC 的锁与"媒体是否有效"判定，
 *       waterframes 的 {@code canTick()/canRender()/tick()} 都拿它们当闸门。</li>
 * </ul>
 * 没有引擎的播放器（未启用 FFmpeg 时）走原逻辑，行为不变。
 */
@Mixin(targets = "org.watermedia.api.player.videolan.BasePlayer")
public abstract class BasePlayerFfmpegMixin {

    @Unique
    private MediaEngine mediafix$engine() {
        return MediaEngines.of(this);
    }

    // ---------------- 时间 ----------------

    @Inject(method = "getTime", at = @At("HEAD"), cancellable = true)
    private void mediafix$getTime(CallbackInfoReturnable<Long> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.timeMs());
    }

    @Inject(method = "getDuration", at = @At("HEAD"), cancellable = true)
    private void mediafix$getDuration(CallbackInfoReturnable<Long> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(Math.max(0L, e.durationMs()));
    }

    @Inject(method = "getMediaInfoDuration", at = @At("HEAD"), cancellable = true)
    private void mediafix$getMediaInfoDuration(CallbackInfoReturnable<Long> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(Math.max(0L, e.durationMs()));
    }

    // ---------------- 状态 ----------------

    @Inject(method = "isWaiting", at = @At("HEAD"), cancellable = true)
    private void mediafix$isWaiting(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.isLoading());
    }

    @Inject(method = "isLoading", at = @At("HEAD"), cancellable = true)
    private void mediafix$isLoading(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.isLoading());
    }

    @Inject(method = "isBuffering", at = @At("HEAD"), cancellable = true)
    private void mediafix$isBuffering(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.isBuffering());
    }

    @Inject(method = "isReady", at = @At("HEAD"), cancellable = true)
    private void mediafix$isReady(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        // 必须等视频源真的打开、时长已知（waterframes 会据此固定进度条总长，早了就永久为 0）
        if (e != null) cir.setReturnValue(e.readyForDisplay());
    }

    @Inject(method = "isPaused", at = @At("HEAD"), cancellable = true)
    private void mediafix$isPaused(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.isPaused());
    }

    @Inject(method = "isPlaying", at = @At("HEAD"), cancellable = true)
    private void mediafix$isPlaying(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.isPlaying());
    }

    @Inject(method = "isEnded", at = @At("HEAD"), cancellable = true)
    private void mediafix$isEnded(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.state() == MediaEngine.State.ENDED);
    }

    @Inject(method = "isBroken", at = @At("HEAD"), cancellable = true)
    private void mediafix$isBroken(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.isBroken());
    }

    @Inject(method = "isValid", at = @At("HEAD"), cancellable = true)
    private void mediafix$isValid(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(!e.isBroken() && e.hasVideo());
    }

    @Inject(method = "isSafeUse", at = @At("HEAD"), cancellable = true)
    private void mediafix$isSafeUse(CallbackInfoReturnable<Boolean> cir) {
        if (mediafix$engine() != null) cir.setReturnValue(Boolean.TRUE);
    }

    @Inject(method = "isLive", at = @At("HEAD"), cancellable = true)
    private void mediafix$isLive(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.isLive());
    }

    @Inject(method = "isSeekAble", at = @At("HEAD"), cancellable = true)
    private void mediafix$isSeekAble(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(!e.isLive() && e.durationMs() > 0);
    }

    @Inject(method = "isMuted", at = @At("HEAD"), cancellable = true)
    private void mediafix$isMuted(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.isMuted());
    }

    @Inject(method = "getRepeatMode", at = @At("HEAD"), cancellable = true)
    private void mediafix$getRepeatMode(CallbackInfoReturnable<Boolean> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.isRepeat());
    }

    @Inject(method = "getVolume", at = @At("HEAD"), cancellable = true)
    private void mediafix$getVolume(CallbackInfoReturnable<Integer> cir) {
        MediaEngine e = mediafix$engine();
        if (e != null) cir.setReturnValue(e.volumePercent());
    }

    // ---------------- 控制 ----------------

    @Inject(method = "seekTo", at = @At("HEAD"), cancellable = true)
    private void mediafix$seekTo(long time, CallbackInfo ci) {
        MediaEngine e = mediafix$engine();
        if (e != null) {
            e.seek(time);
            ci.cancel();
        }
    }

    @Inject(method = "setPauseMode", at = @At("HEAD"), cancellable = true)
    private void mediafix$setPauseMode(boolean pause, CallbackInfo ci) {
        MediaEngine e = mediafix$engine();
        if (e != null) {
            e.pause(pause);
            ci.cancel();
        }
    }

    @Inject(method = "setMuteMode", at = @At("HEAD"), cancellable = true)
    private void mediafix$setMuteMode(boolean mute, CallbackInfo ci) {
        MediaEngine e = mediafix$engine();
        if (e != null) {
            e.setMuted(mute);
            ci.cancel();
        }
    }

    @Inject(method = "setRepeatMode", at = @At("HEAD"), cancellable = true)
    private void mediafix$setRepeatMode(boolean repeat, CallbackInfo ci) {
        MediaEngine e = mediafix$engine();
        if (e != null) {
            e.setRepeat(repeat);
            ci.cancel();
        }
    }

    @Inject(method = "setVolume", at = @At("HEAD"), cancellable = true)
    private void mediafix$setVolume(int volume, CallbackInfo ci) {
        MediaEngine e = mediafix$engine();
        if (e != null) {
            // waterframes 已经把"距离衰减 + 主音量"算进这个参数（0~100）
            e.setVolumePercent(volume);
            ci.cancel();
        }
    }

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void mediafix$play(CallbackInfo ci) {
        MediaEngine e = mediafix$engine();
        if (e != null) {
            e.pause(false);
            ci.cancel();
        }
    }

    @Inject(method = "pause", at = @At("HEAD"), cancellable = true)
    private void mediafix$pause(CallbackInfo ci) {
        MediaEngine e = mediafix$engine();
        if (e != null) {
            // VLC 的 pause() 只在 canPause 时才生效，这里直接落到引擎
            e.pause(true);
            ci.cancel();
        }
    }

    @Inject(method = "stop", at = @At("HEAD"), cancellable = true)
    private void mediafix$stop(CallbackInfo ci) {
        MediaEngine e = mediafix$engine();
        if (e != null) {
            e.seek(0L);
            e.pause(true);
            ci.cancel();
        }
    }
}
