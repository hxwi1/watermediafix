package dev.mediafix.render;

import org.watermedia.api.render.RenderAPI;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * 信箱化(黑边)帧上传：把视频帧居中拷入一块与屏幕格比例等比的黑色画布，
 * 再交给 RenderAPI 上传——画面比例永远正确、黑边为真实黑色像素、零拉伸。
 *
 * <p>画布缓冲按尺寸缓存复用；黑边只在分配/视频分辨率变化时重涂一次，
 * 常态每帧仅一次行拷贝。目标比例由 {@link LetterboxState} 提供
 * （DisplayAspectMixin 在 Display.preRender 每帧写入）。
 *
 * <p>本类必须是普通类（不能放在 mixin 包里做内部类——mixin 包内的类
 * 不允许被注入后的代码直接引用，会 IllegalClassLoadError）。
 */
public final class Letterbox {

    private static final int MAX_CANVAS = 8;
    private static final int MAX_SIDE = 8192;

    private Letterbox() {
    }

    /**
     * 信箱化上传入口（VideoPlayerMixin 的 uploadBuffer 重定向转发到这里）。
     * 比例已一致（误差 1% 内）或拿不到屏幕比例时原样透传。
     */
    public static void upload(ByteBuffer data, int texId, int format,
                              int w, int h, boolean full) {
        float aspect = LetterboxState.aspect();
        if (aspect <= 0.05f || !Float.isFinite(aspect) || w <= 0 || h <= 0
                || w > MAX_SIDE || h > MAX_SIDE) {
            RenderAPI.uploadBuffer(data, texId, format, w, h, full);
            return;
        }
        float videoAspect = (float) w / (float) h;
        if (Math.abs(videoAspect - aspect) <= aspect * 0.01f) {
            RenderAPI.uploadBuffer(data, texId, format, w, h, full);
            return;
        }

        // 等比画布：包住视频且比例等于屏幕比例
        int cw, ch;
        if (videoAspect > aspect) {
            cw = w;
            ch = Math.max(h, Math.round(w / aspect));
        } else {
            ch = h;
            cw = Math.max(w, Math.round(h * aspect));
        }
        if (cw > MAX_SIDE || ch > MAX_SIDE || (long) cw * ch > 32L * 1024 * 1024 / 4) {
            RenderAPI.uploadBuffer(data, texId, format, w, h, full);
            return;
        }

        Canvas c = CANVASES.get(key(cw, ch));
        if (c == null) {
            if (CANVASES.size() >= MAX_CANVAS) {
                CANVASES.clear();
            }
            c = new Canvas(cw, ch);
            CANVASES.put(key(cw, ch), c);
        }
        boolean needFull = full || c.lastVw != w || c.lastVh != h;
        if (c.lastVw != w || c.lastVh != h) {
            c.paintBlack(); // 视频尺寸变了，黑边里可能有旧画面残留
            c.lastVw = w;
            c.lastVh = h;
        }

        // 逐行居中拷贝（4字节/像素，格式由 RenderAPI 处理）
        int srcPos = data.position();
        int srcLimit = data.limit();
        int srcStride = w * 4;
        int dstStride = cw * 4;
        int barTop = (ch - h) / 2;
        int barLeft = (cw - w) / 2;
        int dstBase = (barTop * cw + barLeft) * 4;
        // 防御：源缓冲装不下整帧（包装后分辨率与实际帧缓冲不匹配时会出现
        // limit - position < w*4 的截断缓冲，如重连/Display 重建瞬间）。
        // 此时按 watermedia 原语义整幅透传，确保任何一帧异常都绝不让
        // lambda$display$0 抛异常把 semaphore 卡死(表现为播一秒后黑屏)。
        long srcBytes = (long) srcLimit - srcPos;
        if (srcStride <= 0 || srcBytes < (long) h * srcStride
                || (long) dstBase + (long) (h - 1) * dstStride + (long) srcStride
                        > (long) c.buf.capacity()) {
            RenderAPI.uploadBuffer(data, texId, format, w, h, full);
            return;
        }
        try {
            for (int row = 0; row < h; row++) {
                data.position(row * srcStride).limit(row * srcStride + srcStride);
                c.buf.position(dstBase + row * dstStride).limit(dstBase + row * dstStride + srcStride);
                c.buf.put(data);
            }
        } catch (RuntimeException ex) {
            data.position(srcPos).limit(srcLimit);
            RenderAPI.uploadBuffer(data, texId, format, w, h, full);
            return;
        }
        data.position(srcPos).limit(srcLimit);
        c.buf.clear();

        RenderAPI.uploadBuffer(c.buf, texId, format, cw, ch, needFull);
    }

    /** 画布缓存：尺寸 -> 画布（渲染线程独占访问）。 */
    private static final Map<Long, Canvas> CANVASES = new HashMap<>();

    private static long key(int w, int h) {
        return ((long) w << 32) | (h & 0xFFFFFFFFL);
    }

    /** 画布：复用的直接缓冲 + 上次视频尺寸（变化时需重涂黑边并整幅上传）。 */
    private static final class Canvas {
        final ByteBuffer buf;
        final int w;
        final int h;
        int lastVw = -1;
        int lastVh = -1;

        Canvas(int w, int h) {
            this.w = w;
            this.h = h;
            this.buf = ByteBuffer.allocateDirect(w * h * 4);
            paintBlack();
        }

        /** 整幅涂不透明黑（RGB=0, A=255）。 */
        private void paintBlack() {
            byte[] black = {0, 0, 0, -1};
            byte[] row = new byte[w * 4];
            for (int x = 0; x < w; x++) {
                System.arraycopy(black, 0, row, x * 4, 4);
            }
            buf.clear();
            for (int y = 0; y < h; y++) {
                buf.put(row);
            }
            buf.clear();
        }
    }
}
