package com.compact.extremereactor.common.network;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.menu.CompactReactorMenu;
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
    // 包频率限速（DoS 防御）
    // ------------------------------------------------------------------

    /**
     * 每玩家每服务端 tick 最多处理 1 个控制棒包——超过则丢弃。
     * 防止恶意客户端用 auto-clicker 工具高频点击制造服务端资源压力。
     * 底层 ConcurrentHashMap：当前仅在服务端主线程读写（putIfAbsent 原子更新），
     * 无锁语义为未来潜在的非主线程路径预留。
     */
    private static final java.util.concurrent.ConcurrentMap<java.util.UUID, Long> _lastControlRodTick =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<java.util.UUID, Long> _lastToggleTick =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<java.util.UUID, Long> _lastVoidWasteTick =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int ROD_PACKET_INTERVAL_TICKS = 1;
    private static final int ACTION_PACKET_INTERVAL_TICKS = 5;

    /** 注册玩家登出清理处理器（由主类在 mod 构造时调用一次）。 */
    public static void registerPlayerCleanupHandler() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ModPackets::handlePlayerLoggedOut);
    }

    private static void handlePlayerLoggedOut(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        final java.util.UUID id = event.getEntity().getUUID();
        _lastControlRodTick.remove(id);
        _lastToggleTick.remove(id);
        _lastVoidWasteTick.remove(id);
    }

    // ------------------------------------------------------------------
    // 数据包定义
    // ------------------------------------------------------------------

    /** 相对调节反应堆控制棒插入比例（GUI 仅发送 -5/+5）。 */
    public record ControlRodPayload(BlockPos pos, int delta) implements CustomPacketPayload {
        public static final Type<ControlRodPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(CompactExtremeReactor.MODID, "control_rod"));
        public static final StreamCodec<ByteBuf, ControlRodPayload> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, ControlRodPayload::pos,
                ByteBufCodecs.VAR_INT, ControlRodPayload::delta,
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
        final PayloadRegistrar registrar = event.registrar(CompactExtremeReactor.MODID).versioned("2");
        registrar.playToServer(ControlRodPayload.TYPE, ControlRodPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleControlRod(payload, ctx)));
        registrar.playToServer(MachineActionPayload.TYPE, MachineActionPayload.STREAM_CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> handleMachineAction(payload, ctx)));
    }

    /** 服务端：应用控制棒插入比例（限制玩家距离防止远程操作）。 */
    private static void handleControlRod(ControlRodPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        // 包频率限速：每玩家每 tick 最多 1 个控制棒包（GUI 是离散 +/- 按钮，正常操作不受影响；
        // 客户端本地值每 tick 从服务端回同步，被丢弃的包表现为"这次点击没生效"，无错位风险）
        final long nowTick = player.level().getGameTime();
        final Long lastRodTick = _lastControlRodTick.get(player.getUUID());
        if (lastRodTick != null && nowTick - lastRodTick < ROD_PACKET_INTERVAL_TICKS) {
            return;
        }
        _lastControlRodTick.put(player.getUUID(), nowTick);
        // DoS 防御：玩家不可能站在未加载区块——isLoaded 检查避免对未加载坐标触发
        // 同步 chunk load（getBlockEntity 在未加载区块会同步生成，恶意包能冻结主线程）
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
            // 远距离操作：审计日志（防止玩家用 mod 工具绕过距离限制）
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
    }

    /** 服务端：执行机器动作（开关 / 清除废料）。 */
    private static void handleMachineAction(MachineActionPayload payload, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        final java.util.concurrent.ConcurrentMap<java.util.UUID, Long> actionTicks;
        if (payload.action() == ACTION_TOGGLE_ACTIVE) {
            actionTicks = _lastToggleTick;
        } else if (payload.action() == ACTION_VOID_WASTE) {
            actionTicks = _lastVoidWasteTick;
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
        // DoS 防御：见 handleControlRod 同名注释
        if (!player.level().isLoaded(payload.pos())) {
            return;
        }
        if (!(player.level().getBlockEntity(payload.pos()) instanceof AbstractCompactMachineTileEntity tile)) {
            return;
        }
        if (!(player.containerMenu instanceof CompactReactorMenu menu)
                || !(tile instanceof CompactReactorTileEntity reactorTile)
                || !menu.isForTile(reactorTile)
                || menu.getData(CompactReactorMenu.DATA_POS_READY) != 1) {
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
            default -> {
                // 未知 action：可能是更旧/更新客户端发的越界值，记日志便于诊断
                CompactExtremeReactor.LOGGER.debug("未知机器动作 {} @ {}，已忽略",
                        payload.action(), payload.pos());
            }
        }
    }
}
