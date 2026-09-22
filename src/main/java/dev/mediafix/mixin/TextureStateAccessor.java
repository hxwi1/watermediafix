package dev.mediafix.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * GlStateManager$TextureState（包私有内部类）的绑定缓存字段访问。
 * 用字符串目标名引用，避免直接引用不可见的类型；运行时 Mixin 会把本接口
 * 注入到 TextureState 上，使其可被实例转型调用。
 * 用途见 {@link GlStateManagerAccessor}。
 */
@Mixin(targets = "com.mojang.blaze3d.platform.GlStateManager$TextureState")
public interface TextureStateAccessor {

    @Accessor("binding")
    void mediafix$setBinding(int binding);
}
