package dev.mediafix.render;

/**
 * 信箱化(黑边)状态传递：Display.preRender 每帧把"屏幕格宽高比"放进这里，
 * 同线程稍后的 VideoPlayer.preRender 帧上传处读取并据此加黑边。
 * 渲染线程串行调用，volatile 足够。
 */
public final class LetterboxState {

    /** 目标画布宽高比（格）；<=0 表示未知/不处理。 */
    private static volatile float aspect = -1f;

    private LetterboxState() {
    }

    public static void set(float targetAspect) {
        aspect = targetAspect;
    }

    public static float aspect() {
        return aspect;
    }
}
