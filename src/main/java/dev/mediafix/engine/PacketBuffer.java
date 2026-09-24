package dev.mediafix.engine;

import org.bytedeco.ffmpeg.avcodec.AVPacket;

import java.util.ArrayDeque;

import static org.bytedeco.ffmpeg.global.avcodec.av_packet_clone;
import static org.bytedeco.ffmpeg.global.avcodec.av_packet_free;

/**
 * 压缩数据预读队列（demux 线程 → 解码线程）。
 *
 * <p><b>为什么缓的是压缩包而不是解码后的帧</b>：4K 一帧解码后是 33MB（RGBA），缓 30 秒需要 30GB，
 * 不可能；而同一段视频的压缩数据只有几十 MB。真正的流媒体播放器都是这么做的
 * （缓冲区里放的是码流，播放过的部分直接丢弃）。
 *
 * <p>背压：队列字节数超过 {@code maxBytes} 时 {@link #put} 阻塞，因此预读深度天然被预算约束；
 * 播放过的包出队后立即 {@code av_packet_free}，内存不会随时间增长。
 *
 * <p>serial（代次）：每次 seek 递增，解码线程据此丢弃旧代次的数据包并重置解码器 ——
 * 因此 formatContext 只被 demux 线程使用、解码器只被解码线程使用，两者不跨线程共享原生对象。
 */
public final class PacketBuffer {

    private record Entry(AVPacket packet, int serial) {
    }

    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private final long maxBytes;

    private long bytes;
    private int serial;
    private boolean finished;
    private boolean aborted;

    public PacketBuffer(long maxBytes) {
        this.maxBytes = Math.max(1L << 20, maxBytes);
    }

    /** 克隆入库（调用方保留自己的包）。预算满则阻塞。 */
    public boolean put(AVPacket packet) throws InterruptedException {
        synchronized (this.lock) {
            while (!this.aborted && this.bytes >= this.maxBytes) {
                this.lock.wait(50);
            }
            if (this.aborted) return false;
            AVPacket clone = av_packet_clone(packet);
            if (clone == null) return false;
            this.queue.addLast(new Entry(clone, this.serial));
            this.bytes += Math.max(0, clone.size());
            this.lock.notifyAll();
            return true;
        }
    }

    /**
     * 取出一个包（所有权归调用方，用完必须 av_packet_free）。
     * @return null = 已结束或已中止
     */
    public AVPacket poll(int[] serialOut) throws InterruptedException {
        synchronized (this.lock) {
            while (this.queue.isEmpty() && !this.finished && !this.aborted) {
                this.lock.wait(50);
            }
            if (this.aborted) return null;
            if (this.queue.isEmpty()) return null;
            Entry e = this.queue.pollFirst();
            this.bytes -= Math.max(0, e.packet().size());
            if (serialOut != null && serialOut.length > 0) serialOut[0] = e.serial();
            this.lock.notifyAll();
            return e.packet();
        }
    }

    /** 丢弃所有包并递增代次（seek 时调用；解码线程会自行重置解码器）。 */
    public void bumpSerial() {
        synchronized (this.lock) {
            freeLocked();
            this.serial++;
            this.finished = false;
            this.lock.notifyAll();
        }
    }

    /** 丢弃所有包但不改变代次。 */
    public void clear() {
        synchronized (this.lock) {
            freeLocked();
            this.lock.notifyAll();
        }
    }

    private void freeLocked() {
        while (!this.queue.isEmpty()) {
            av_packet_free(this.queue.pollFirst().packet());
        }
        this.bytes = 0;
    }

    public void finish() {
        synchronized (this.lock) {
            this.finished = true;
            this.lock.notifyAll();
        }
    }

    public void abort() {
        synchronized (this.lock) {
            this.aborted = true;
            this.lock.notifyAll();
        }
    }

    /** null 是"干净结束"还是"被中止"，解码线程据此决定是否排空解码器。 */
    public boolean endOfFile() {
        synchronized (this.lock) {
            return this.finished && !this.aborted;
        }
    }

    public int serial() {
        synchronized (this.lock) {
            return this.serial;
        }
    }

    /** 当前预读的字节数（诊断用）。 */
    public long bufferedBytes() {
        synchronized (this.lock) {
            return this.bytes;
        }
    }

    public void free() {
        synchronized (this.lock) {
            freeLocked();
            this.aborted = true;
            this.lock.notifyAll();
        }
    }
}
