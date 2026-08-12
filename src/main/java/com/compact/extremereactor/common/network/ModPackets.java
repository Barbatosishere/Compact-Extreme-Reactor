package com.compact.extremereactor.common.network;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 自定义网络数据包：客户端 → 服务端的机器控制指令。
 *
 * 单方块模拟的 GUI 需要把玩家的操作（控制棒调节、开关、清除废料）发送到
 * 服务端，由服务端 TileEntity 应用到 ER 控制器上。这里使用 NeoForge 1.21
 * 的标准 payload 机制。
 */
public final class ModPackets {

    private ModPackets() {
    }

    /** 机器动作类型（编码为 int 便于用 VAR_INT 传输）。 */
    public static final int ACTION_TOGGLE_ACTIVE = 0;
    public static final int ACTION_VOID_WASTE = 1;

    // ------------------------------------------------------------------
    // 数据包定义
    // ------------------------------------------------------------------

    /** 设置反应堆控制棒插入比例（0-100）。 */
    public record ControlRodPayload(BlockPos pos, int ratio) implements CustomPacketPayload {
        public static final Type<ControlRodPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CompactExtremeReactor.MODID, "control_rod"));
        public static final StreamCodec<ByteBuf, ControlRodPayload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ControlRodPayload::pos,
                ByteBufCodecs.VAR_INT, ControlRodPayload::ratio,
                ControlRodPayload::new);

        @Override
        public Type<ControlRodPayload> type() {
            return TYPE;
        }
    }

    /** 对压缩机器执行一个动作（开关 / 清除废料）。 */
    public record MachineActionPayload(BlockPos pos, int action) implements CustomPacketPayload {
        public static final Type<MachineActionPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CompactExtremeReactor.MODID, "machine_action"));
        public static final StreamCodec<ByteBuf, MachineActionPayload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, MachineActionPayload::pos,
                ByteBufCodecs.VAR_INT, MachineActionPayload::action,
                MachineActionPayload::new);

        @Override
        public Type<MachineActionPayload> type() {
            return TYPE;
        }
    }

    // ------------------------------------------------------------------
    // 注册与处理
    // ------------------------------------------------------------------

    /** 在 MOD 事件总线的 RegisterPayloadHandlersEvent 中注册所有数据包。 */
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(CompactExtremeReactor.MODID).versioned("1");
        registrar.playToServer(ControlRodPayload.TYPE, ControlRodPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleControlRod(payload, ctx)));
        registrar.playToServer(MachineActionPayload.TYPE, MachineActionPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleMachineAction(payload, ctx)));
    }

    /** 服务端：应用控制棒插入比例（限制玩家距离防止远程操作）。 */
    private static void handleControlRod(ControlRodPayload payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player
                && player.level().getBlockEntity(payload.pos()) instanceof CompactReactorTileEntity tile
                && player.distanceToSqr(payload.pos().getCenter()) < 64) {
            tile.setControlRodInsertionRatio(payload.ratio());
        }
    }

    /** 服务端：执行机器动作（开关 / 清除废料）。 */
    private static void handleMachineAction(MachineActionPayload payload, IPayloadContext ctx) {
        if (ctx.player() instanceof ServerPlayer player
                && player.level().getBlockEntity(payload.pos()) instanceof AbstractCompactMachineTileEntity tile
                && player.distanceToSqr(payload.pos().getCenter()) < 64) {
            final ICompactController controller = tile.getController();
            if (controller == null) {
                return;
            }
            switch (payload.action()) {
                case ACTION_TOGGLE_ACTIVE -> {
                    controller.setMachineActive(!controller.isMachineActive());
                    // 激活状态需要持久化，立即标记方块保存
                    tile.setChanged();
                }
                case ACTION_VOID_WASTE -> {
                    if (controller instanceof CompactReactorController reactor) {
                        reactor.voidWaste();
                        tile.setChanged();
                    }
                }
                default -> {
                }
            }
        }
    }
}
