package com.compact.extremereactor.common.network;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.menu.CompactReactorMenu;
import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkDirection;
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
    public static final int ACTION_CLEAR_INPUTS = 2;

    private static final String PROTOCOL_VERSION = "2";

    // ------------------------------------------------------------------
    // 包频率限速（DoS 防御）
    // ------------------------------------------------------------------

    /**
     * 每玩家限速缓存：控制棒 1 tick 间隔、机器动作 5 tick 间隔。
     * 防止恶意客户端高频发包制造服务端资源压力。仅主线程读写，Map 保险起见用并发实现。
     * 客户端本地值每 tick 从服务端回同步，被丢弃的包表现为"这次点击没生效"，无错位风险。
     */
    private static final java.util.concurrent.ConcurrentMap<java.util.UUID, Long> _lastControlRodTick =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<java.util.UUID, Long> _lastToggleTick =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<java.util.UUID, Long> _lastVoidWasteTick =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<java.util.UUID, Long> _lastClearInputsTick =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int ROD_PACKET_INTERVAL_TICKS = 1;
    private static final int ACTION_PACKET_INTERVAL_TICKS = 5;

    /** 注册玩家登出清理处理器（由主类在 mod 构造时调用一次）。 */
    public static void registerPlayerCleanupHandler() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(ModPackets::handlePlayerLoggedOut);
    }

    private static void handlePlayerLoggedOut(
            net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        final java.util.UUID id = event.getEntity().getUUID();
        _lastControlRodTick.remove(id);
        _lastToggleTick.remove(id);
        _lastVoidWasteTick.remove(id);
        _lastClearInputsTick.remove(id);
    }

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CompactExtremeReactor.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    // ------------------------------------------------------------------
    // 数据包定义
    // ------------------------------------------------------------------

    /** 相对调节反应堆控制棒插入比例（GUI 仅发送 -5/+5）。 */
    public record ControlRodPayload(BlockPos pos, int delta) {

        public static void encode(ControlRodPayload payload, FriendlyByteBuf buf) {
            buf.writeBlockPos(payload.pos());
            buf.writeVarInt(payload.delta());
        }

        public static ControlRodPayload decode(FriendlyByteBuf buf) {
            return new ControlRodPayload(buf.readBlockPos(), buf.readVarInt());
        }

        public static void handle(ControlRodPayload payload, Supplier<NetworkEvent.Context> ctxSupplier) {
            final NetworkEvent.Context ctx = ctxSupplier.get();
            ctx.enqueueWork(() -> {
                final ServerPlayer player = ctx.getSender();
                if (player == null) {
                    return;
                }
                // 包频率限速：每玩家每 tick 最多 1 个控制棒包（GUI 是离散 +/- 按钮，正常操作不受影响）
                final long nowTick = player.level().getGameTime();
                final Long lastRodTick = _lastControlRodTick.get(player.getUUID());
                if (lastRodTick != null && nowTick - lastRodTick < ROD_PACKET_INTERVAL_TICKS) {
                    return;
                }
                _lastControlRodTick.put(player.getUUID(), nowTick);
                // DoS 防御：见 handleMachineAction 同名注释
                if (!player.level().isLoaded(payload.pos())) {
                    return;
                }
                if (!(player.level().getBlockEntity(payload.pos()) instanceof CompactReactorTileEntity tile)) {
                    return;
                }
                if (!(player.containerMenu instanceof CompactReactorMenu menu)
                        || !menu.isForTile(tile)
                        || menu.getData(CompactReactorMenu.DATA_POS_READY) != 1) {
                    return;
                }
                if (player.distanceToSqr(payload.pos().getCenter()) >= 64) {
                    CompactExtremeReactor.LOGGER.warn("玩家 {} 尝试远程调节控制棒 @{}，距离={}，已忽略",
                            player.getName().getString(), payload.pos(),
                            String.format("%.1f", Math.sqrt(player.distanceToSqr(payload.pos().getCenter()))));
                    return;
                }
                if (tile.isControllerInitFailed()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("gui.compactextremereactor.init_failed"));
                    return;
                }
                if (!tile.isControllerReady()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("gui.compactextremereactor.controller_initializing"));
                    return;
                }
                if (payload.delta() != -5 && payload.delta() != 5) {
                    return;
                }
                final int ratio = tile.adjustControlRodInsertionRatio(payload.delta());
                CompactExtremeReactor.LOGGER.debug("玩家 {} 调节控制棒 {}，当前比例 {} @ {}",
                        player.getName().getString(), payload.delta(), ratio, payload.pos());
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
                if (player == null) {
                    return;
                }
                final java.util.concurrent.ConcurrentMap<java.util.UUID, Long> actionTicks;
                if (payload.action() == ACTION_TOGGLE_ACTIVE) {
                    actionTicks = _lastToggleTick;
                } else if (payload.action() == ACTION_VOID_WASTE) {
                    actionTicks = _lastVoidWasteTick;
                } else if (payload.action() == ACTION_CLEAR_INPUTS) {
                    actionTicks = _lastClearInputsTick;
                } else {
                    CompactExtremeReactor.LOGGER.debug("未知机器动作 {} @ {}，已忽略",
                            payload.action(), payload.pos());
                    return;
                }
                final long nowTick = player.level().getGameTime();
                final Long lastActionTick = actionTicks.get(player.getUUID());
                if (lastActionTick != null && nowTick - lastActionTick < ACTION_PACKET_INTERVAL_TICKS) {
                    return;
                }
                actionTicks.put(player.getUUID(), nowTick);
                // DoS 防御：玩家不可能站在未加载区块——isLoaded 检查避免对未加载坐标触发
                // 同步 chunk load（getBlockEntity 在未加载区块会同步生成，恶意包能冻结主线程）
                if (!player.level().isLoaded(payload.pos())) {
                    return;
                }
                if (!(player.level().getBlockEntity(payload.pos()) instanceof AbstractCompactMachineTileEntity tile)) {
                    return;
                }
                // 菜单与方块类型必须匹配：反应堆 GUI 只能操作反应堆，流化器 GUI 只能操作流化器
                // （防止用 A 机器的菜单会话操控 B 机器；涡轮机 GUI 无开关，toggle 包在下方统一拒绝）
                final boolean menuMatches;
                if (player.containerMenu instanceof CompactReactorMenu reactorMenu) {
                    menuMatches = tile instanceof CompactReactorTileEntity reactorTile
                            && reactorMenu.isForTile(reactorTile)
                            && reactorMenu.getData(CompactReactorMenu.DATA_POS_READY) == 1;
                } else if (player.containerMenu instanceof com.compact.extremereactor.common.menu.CompactFluidizerMenu fluidizerMenu) {
                    menuMatches = tile instanceof com.compact.extremereactor.common.tile.CompactFluidizerTileEntity fluidizerTile
                            && fluidizerMenu.isForTile(fluidizerTile)
                            && fluidizerMenu.getData(com.compact.extremereactor.common.menu.CompactFluidizerMenu.DATA_POS_READY) == 1;
                } else {
                    menuMatches = false;
                }
                if (!menuMatches) {
                    return;
                }
                if (player.distanceToSqr(payload.pos().getCenter()) >= 64) {
                    CompactExtremeReactor.LOGGER.warn("玩家 {} 尝试远程操作 @{}，已忽略",
                            player.getName().getString(), payload.pos());
                    return;
                }
                // 涡轮机 GUI 不提供开关：拒绝 ACTION_TOGGLE_ACTIVE 包（否则玩家关后无 UI 重开）
                if (payload.action() == ACTION_TOGGLE_ACTIVE
                        && tile instanceof com.compact.extremereactor.common.tile.CompactTurbineTileEntity) {
                    CompactExtremeReactor.LOGGER.debug("玩家 {} 尝试 toggle 涡轮机 @{}，已拒绝（无 GUI 开关）",
                            player.getName().getString(), payload.pos());
                    return;
                }
                if (tile.isControllerInitFailed()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("gui.compactextremereactor.init_failed"));
                    return;
                }
                if (!tile.isControllerReady()) {
                    player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("gui.compactextremereactor.controller_initializing"));
                    return;
                }
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
                            final int cleared = reactor.voidWaste();
                            tile.setChanged();
                            if (cleared > 0) {
                                CompactExtremeReactor.LOGGER.debug("压缩反应堆清除废料 {} 单位 @ {}", cleared, payload.pos());
                            }
                        }
                    }
                    case ACTION_CLEAR_INPUTS -> {
                        if (controller instanceof com.compact.extremereactor.common.multiblock.CompactFluidizerController fluidizer) {
                            fluidizer.clearInputs();
                            tile.setChanged();
                            CompactExtremeReactor.LOGGER.debug("压缩流化器清空进料 @ {}", payload.pos());
                        }
                    }
                    default -> {
                        // 未知 action：可能是更旧/更新客户端发的越界值，记日志便于诊断
                        CompactExtremeReactor.LOGGER.debug("未知机器动作 {} @ {}，已忽略",
                                payload.action(), payload.pos());
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
            // 显式声明方向为 PLAY_TO_SERVER：这些包只有 C2S 语义，
            // 限制后客户端会拒收同名包、服务端拒收伪装包，收窄伪造面
            CHANNEL.registerMessage(0, ControlRodPayload.class,
                    ControlRodPayload::encode, ControlRodPayload::decode, ControlRodPayload::handle,
                    java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
            CHANNEL.registerMessage(1, MachineActionPayload.class,
                    MachineActionPayload::encode, MachineActionPayload::decode, MachineActionPayload::handle,
                    java.util.Optional.of(NetworkDirection.PLAY_TO_SERVER));
        });
    }

    /** 客户端 → 服务端发送指令（GUI 按钮使用）。 */
    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }
}
