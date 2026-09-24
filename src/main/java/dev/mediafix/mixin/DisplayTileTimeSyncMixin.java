package dev.mediafix.mixin;

import dev.mediafix.bridge.TimeSyncAware;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 给显示方块实体加一个"最近一次服务端下发时间"的戳（见 {@link dev.mediafix.bridge.TimeSyncAware}）。
 *
 * <p>放在方块实体上而不是 {@code Display} 上：{@code TimePacket.exec()} 拿到的只有 tile，
 * 而 {@code display} 字段随时可能是 null（切模式/重建期间）。写在 tile 上就一定能记下来。
 */
@Mixin(targets = "me.srrapero720.waterframes.common.block.entity.DisplayTile")
public abstract class DisplayTileTimeSyncMixin implements TimeSyncAware {

    @Unique
    private volatile long mediafix$serverSyncAt;

    @Override
    public long mediafix$lastServerSyncAt() {
        return this.mediafix$serverSyncAt;
    }

    @Override
    public void mediafix$markServerSync(long wallMs) {
        this.mediafix$serverSyncAt = wallMs;
    }
}
