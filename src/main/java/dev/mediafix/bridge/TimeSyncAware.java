package dev.mediafix.bridge;

/**
 * "这个显示方块刚收到服务端下发的播放位置"的标记。
 *
 * <p>waterframes 的进度同步有两个来源，必须分清楚：
 * <ul>
 *   <li><b>服务端/玩家明确要求</b> —— {@code TimePacket}（拖动进度条、遥控器快进/回退、
 *       另一个玩家在服务端改进度）经 {@code DisplayNetwork} 下发，{@code exec()} 里直接写
 *       {@code data.tick}，{@code execClient()} 里 {@code Display.forceSeek()}。这是"意图"；</li>
 *   <li><b>waterframes 自己重算</b> —— {@code Display.tick()} 每 tick 用
 *       {@code tickToMs(data.tick)+deltaFrames} 算出一个目标，条件成立就 {@code seekTo}。
 *       这里的 {@code data.tick} 可能瞬时为 0（方块/分块重同步），也可能是那条
 *       {@code getMediaInfoDuration()==0} 的 floorMod 分支把目标算成 0。这是"意外"。</li>
 * </ul>
 *
 * <p>两种情况下算出来的后续 seek 长得一模一样，光看数字区分不了，只能看来源：
 * 由 {@code DisplayTileTimeSyncMixin} 落在方块实体上、{@code TimePacketMixin} 打时间戳。
 *
 * <p>★ 这个接口刻意<b>不放在 {@code dev.mediafix.mixin} 包里</b>：那个包被 mixin 配置
 * 声明为"mixin 专属包"，里面的类不能被直接加载/引用，否则启动时抛
 * {@code IllegalClassLoadError: ... is in a defined mixin package ... and cannot be referenced directly}
 * （实测 2026-09-24 11:00:34 的崩溃就是这么来的，waterframes 直接加载失败）。
 */
public interface TimeSyncAware {

    /** 服务端最近一次下发播放位置的墙钟时刻（毫秒）；从未下发过返回 0。 */
    long mediafix$lastServerSyncAt();

    /** 记录一次服务端下发。 */
    void mediafix$markServerSync(long wallMs);
}
