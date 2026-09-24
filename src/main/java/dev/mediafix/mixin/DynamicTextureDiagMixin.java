package dev.mediafix.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.mediafix.MediaFix;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 诊断：谁在"非渲染线程"构造 DynamicTexture，以及谁在 flush 前 close 把它置空。
 *
 * <p>已实锤：崩溃点 = DynamicTexture 构造入队的 recordRenderCall lambda 在 flush 执行
 * 时 {@code this.pixels.getWidth()} 抛 NPE —— 而 pixels 是 @Nullable 字段，值在
 * lambda 入队后、渲染线程 flush 前被 {@code close()} 置 null。临界前提是
 * {@code !RenderSystem.isOnRenderThread()}（worker 线程构造）。
 *
 * <p>因此要害不是"谁在刷建/删"，而是"谁在 worker 线程异步构造 + 谁随后 close"。
 * 本 Mixin 抓三件事（各去重一次）：
 * <ul>
 *   <li>非渲染线程构造：打调用栈头（这是竞态入口）</li>
 *   <li>close()：打调用栈头（这是把 pixels 置 null 的元凶）</li>
 *   <li>把两者的线程名写上，便于交叉比对</li>
 * </ul>
 */
@Mixin(DynamicTexture.class)
public abstract class DynamicTextureDiagMixin {

    @Shadow
    @javax.annotation.Nullable
    private NativeImage pixels;

    private static final Set<String> SEEN_CTOR_WORKER = ConcurrentHashMap.newKeySet();
    private static final Set<String> SEEN_CLOSE = ConcurrentHashMap.newKeySet();
    private static final Set<String> SEEN_CTOR_RENDER = ConcurrentHashMap.newKeySet();

    /** 匹配所有构造（NativeImage / int,int,boolean），只打非渲染线程的那次。 */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void mediafix$diagCtor(CallbackInfo ci) {
        try {
            boolean onRender = com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread();
            String tname = Thread.currentThread().getName();
            if (onRender) {
                if (SEEN_CTOR_RENDER.add(tname)) {
                    MediaFix.LOGGER.diag("[mediafix][diag] DynamicTexture ctor ON-render thread={} size={}x{} caller={}",
                            tname,
                            pixels == null ? -1 : pixels.getWidth(),
                            pixels == null ? -1 : pixels.getHeight(),
                            callerSig(3));
                }
                return;
            }
            String sig = tname + " | " + callerSig(3);
            if (SEEN_CTOR_WORKER.add(sig)) {
                MediaFix.LOGGER.diag("[mediafix][diag] !!! DynamicTexture ctor OFF-render thread={} size={}x{} caller={}",
                        tname,
                        pixels == null ? -1 : pixels.getWidth(),
                        pixels == null ? -1 : pixels.getHeight(),
                        callerSig(3));
            }
        } catch (Throwable ignored) {
        }
    }

    /** close() 是把 pixels 置 null 的元凶，务必抓调用方。 */
    @Inject(method = "close", at = @At("HEAD"))
    private void mediafix$diagClose(CallbackInfo ci) {
        try {
            String sig = Thread.currentThread().getName() + " | " + callerSig(3);
            if (SEEN_CLOSE.add(sig)) {
                MediaFix.LOGGER.diag("[mediafix][diag] DynamicTexture closed thread={} caller={}",
                        Thread.currentThread().getName(), callerSig(3));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 拼调用栈头，从调用者一路追到根帧（Minecraft/ResourceManager/Atlas/stitch）。 */
    private static String callerSig(int depth) {
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        if (st == null || st.length <= depth) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = depth; i < st.length && sb.length() < 240; i++) {
            StackTraceElement e = st[i];
            String s = e.getClassName() + "." + e.getMethodName();
            if (s.startsWith("dev.mediafix") || s.startsWith("java.lang") || s.startsWith("sun.")) {
                continue;
            }
            if (e.getClassName().contains("Minecraft")
                    || e.getClassName().contains("ResourceManager")
                    || e.getClassName().contains("SimpleTexture")
                    || e.getClassName().contains("DynamicTexture")
                    || e.getClassName().contains("stitch")
                    || e.getClassName().contains("Atlas")) {
                sb.append(s);
                break;
            }
            sb.append(s).append(" <- ");
        }
        String out = sb.toString();
        return out.isEmpty() ? "unknown-root" : out.substring(0, out.length() - 4);
    }
}