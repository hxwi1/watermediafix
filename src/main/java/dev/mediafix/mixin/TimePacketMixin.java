package dev.mediafix.mixin;

import dev.mediafix.bridge.TimeSyncAware;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把"服务端下发了新的播放位置"这件事记到方块实体上（见 {@link TimeSyncAware}，它在 bridge 包里）。
 *
 * <p>打点是 {@code exec(DisplayTile)}：它两边都会跑（{@code DisplayControlPacket.execute} 里
 * 无条件先调 {@code exec}），而且它就在写 {@code data.tick} 的那一步 —— 这时候
 * {@code tile.display} 可能还是 null，所以不能指望 {@code execClient} 里的 forceSeek。
 */
@Mixin(targets = "me.srrapero720.waterframes.common.network.packets.TimePacket")
public abstract class TimePacketMixin {

    @Inject(method = "exec", at = @At("RETURN"))
    private void mediafix$markServerTimeSync(DisplayTile tile, CallbackInfo ci) {
        if (tile instanceof TimeSyncAware aware) {
            aware.mediafix$markServerSync(System.currentTimeMillis());
        }
    }
}
