package com.compact.extremereactor.common.network;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * 自定义网络数据包：客户端 → 服务端的机器控制指令。
 *
 * 单方块模拟的 GUI 需要把玩家的操作（控制棒调节、开关、清除废料）发送到
 * 服务端，由服务端 TileEntity 应用到 ER 控制器上。这里使用 Forge 1.20 的
 * SimpleChannel 机制（FriendlyByteBuf 手写编解码）。
 */
public final class ModPackets {

    private ModPackets() {
    }

    /** 机器动作类型（编码为 int 便于用 VAR_INT 传输）。 */
    public static final int ACTION_TOGGLE_ACTIVE = 0;
    public static final int ACTION_VOID_WASTE = 1;

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CompactExtremeReactor.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    // ------------------------------------------------------------------
    // 数据包定义
    // ------------------------------------------------------------------

    /** 设置反应堆控制棒插入比例（0-100）。 */
    public record ControlRodPayload(BlockPos pos, int ratio) {

        public static void encode(ControlRodPayload payload, FriendlyByteBuf buf) {
            buf.writeBlockPos(payload.pos());
            buf.writeVarInt(payload.ratio());
        }

        public static ControlRodPayload decode(FriendlyByteBuf buf) {
            return new ControlRodPayload(buf.readBlockPos(), buf.readVarInt());
        }

        public static void handle(ControlRodPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                final ServerPlayer player = ctx.getSender();
                if (player != null
                        && player.level().getBlockEntity(payload.pos()) instanceof CompactReactorTileEntity tile
                        && player.distanceToSqr(payload.pos().getCenter()) < 64) {
                    tile.setControlRodInsertionRatio(payload.ratio());
                }
            });
            ctx.setPacketHandled(true);
        }
    }

    /** 对压缩机器执行一个动作（开关 / 清除废料）。 */
    public record MachineActionPayload(BlockPos pos, int action) {

        public static void encode(MachineActionPayload payload, FriendlyByteBuf buf) {
            buf.writeBlockPos(payload.pos());
            buf.writeVarInt(payload.action());
        }

        public static MachineActionPayload decode(FriendlyByteBuf buf) {
            return new MachineActionPayload(buf.readBlockPos(), buf.readVarInt());
        }

        public static void handle(MachineActionPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                final ServerPlayer player = ctx.getSender();
                if (player != null
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
            });
            ctx.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /** 在 FMLCommonSetupEvent 中注册所有数据包（enqueueWork 保证主线程安全）。 */
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            CHANNEL.registerMessage(0, ControlRodPayload.class,
                    ControlRodPayload::encode, ControlRodPayload::decode, ControlRodPayload::handle);
            CHANNEL.registerMessage(1, MachineActionPayload.class,
                    MachineActionPayload::encode, MachineActionPayload::decode, MachineActionPayload::handle);
        });
    }

    /** 客户端 → 服务端发送指令（GUI 按钮使用）。 */
    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }
}
