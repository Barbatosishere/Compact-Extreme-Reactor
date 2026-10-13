package com.compact.extremereactor.common.network;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.tile.CompactFluidizerTileEntity;
import com.compact.extremereactor.common.menu.CompactFluidizerMenu;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 客户端机器控制包；服务端校验菜单、目标、距离和频率后执行。 */
public final class ModPackets {

    private ModPackets() {
    }

    /** 机器动作类型（编码为 int 便于用 VAR_INT 传输）。 */
    public static final int ACTION_TOGGLE_ACTIVE = 0;
    public static final int ACTION_VOID_WASTE = 1;
    /** 流化器：清空全部进料（物品槽 + 流体罐）。 */
    public static final int ACTION_CLEAR_INPUTS = 2;

    // 包处理及玩家清理均在服务端主线程执行。
    private static final Map<UUID, Long> _lastControlRodTick =
            new HashMap<>();
    private static final Map<UUID, Long> _lastToggleTick =
            new HashMap<>();
    private static final Map<UUID, Long> _lastVoidWasteTick =
            new HashMap<>();
    private static final Map<UUID, Long> _lastClearInputsTick =
            new HashMap<>();
    private static final int ROD_PACKET_INTERVAL_TICKS = 1;
    private static final int ACTION_PACKET_INTERVAL_TICKS = 5;
    private static final long PLAYER_PURGE_INTERVAL_TICKS = 200L;
    private static long _lastPlayerPurgeTick = -1L;

    private static boolean tryAcquirePacketSlot(
            Map<UUID, Long> lastTicks,
            ServerPlayer player,
            int intervalTicks) {
        final long nowTick = Integer.toUnsignedLong(player.server.getTickCount());
        final UUID playerId = player.getUUID();
        maybePurgeStalePlayers(player, nowTick);
        final Long lastTick = lastTicks.get(playerId);
        if (lastTick != null && elapsedTicks(nowTick, lastTick) < intervalTicks) {
            return false;
        }
        lastTicks.put(playerId, nowTick);
        return true;
    }

    private static void maybePurgeStalePlayers(ServerPlayer player, long nowTick) {
        if (_lastControlRodTick.size() <= 256 && _lastToggleTick.size() <= 256
                && _lastVoidWasteTick.size() <= 256 && _lastClearInputsTick.size() <= 256) {
            return;
        }
        final long previous = _lastPlayerPurgeTick;
        if (previous != -1L && elapsedTicks(nowTick, previous) < PLAYER_PURGE_INTERVAL_TICKS) {
            return;
        }
        _lastPlayerPurgeTick = nowTick;
        removeOfflinePlayers(_lastControlRodTick, player.server.getPlayerList());
        removeOfflinePlayers(_lastToggleTick, player.server.getPlayerList());
        removeOfflinePlayers(_lastVoidWasteTick, player.server.getPlayerList());
        removeOfflinePlayers(_lastClearInputsTick, player.server.getPlayerList());
    }

    private static void removeOfflinePlayers(
            Map<UUID, Long> ticks,
            net.minecraft.server.players.PlayerList players) {
        ticks.entrySet().removeIf(entry -> players.getPlayer(entry.getKey()) == null);
    }

    private static long elapsedTicks(long nowTick, long previousTick) {
        return (nowTick - previousTick) & 0xFFFF_FFFFL;
    }

    /** 注册玩家登出清理处理器（由主类在 mod 构造时调用一次）。 */
    public static void registerPlayerCleanupHandler() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ModPackets::handlePlayerLoggedOut);
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ModPackets::handleServerStopped);
    }

    private static void handleServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        _lastControlRodTick.clear();
        _lastToggleTick.clear();
        _lastVoidWasteTick.clear();
        _lastClearInputsTick.clear();
        _lastPlayerPurgeTick = -1L;
    }

    private static void handlePlayerLoggedOut(
            net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        final UUID id = event.getEntity().getUUID();
        _lastControlRodTick.remove(id);
        _lastToggleTick.remove(id);
        _lastVoidWasteTick.remove(id);
        _lastClearInputsTick.remove(id);
    }


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
        if (!(player.containerMenu instanceof CompactReactorMenu menu)
                || !menu.isForPosition(payload.pos())
                || menu.getData(CompactReactorMenu.DATA_POS_READY) != 1) {
            return;
        }
        // 先检查区块已加载，避免恶意坐标触发同步加载。
        if (!player.level().isLoaded(payload.pos())) {
            return;
        }
        if (!(player.level().getBlockEntity(payload.pos()) instanceof CompactReactorTileEntity tile)) {
            return;
        }
        if (!menu.isForTile(tile)) {
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
        // 仅对已通过所有权限和目标校验的有效操作限速，避免错误包阻塞合法 GUI 操作。
        if (!tryAcquirePacketSlot(_lastControlRodTick, player, ROD_PACKET_INTERVAL_TICKS)) {
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
        final Map<UUID, Long> actionTicks;
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
        if (!((player.containerMenu instanceof CompactReactorMenu reactorMenu
                && reactorMenu.isForPosition(payload.pos()))
                || (player.containerMenu instanceof CompactFluidizerMenu fluidizerMenu
                && fluidizerMenu.isForPosition(payload.pos())))) {
            return;
        }
        // 避免为包中坐标同步加载区块。
        if (!player.level().isLoaded(payload.pos())) {
            return;
        }
        if (!(player.level().getBlockEntity(payload.pos()) instanceof AbstractCompactMachineTileEntity tile)) {
            return;
        }
        // 菜单必须绑定当前机器实例，防止重放旧菜单会话。
        final boolean menuMatches;
        if (player.containerMenu instanceof CompactReactorMenu reactorMenu) {
            menuMatches = tile instanceof CompactReactorTileEntity reactorTile
                    && reactorMenu.isForTile(reactorTile)
                    && reactorMenu.getData(CompactReactorMenu.DATA_POS_READY) == 1;
        } else if (player.containerMenu instanceof CompactFluidizerMenu fluidizerMenu) {
            menuMatches = tile instanceof CompactFluidizerTileEntity fluidizerTile
                    && fluidizerMenu.isForTile(fluidizerTile)
                    && fluidizerMenu.getData(CompactFluidizerMenu.DATA_POS_READY) == 1;
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
        if (payload.action() == ACTION_VOID_WASTE
                && !(controller instanceof CompactReactorController)) {
            return;
        }
        if (payload.action() == ACTION_CLEAR_INPUTS
                && !(controller instanceof CompactFluidizerController)) {
            return;
        }
        // 仅对已通过所有权限和目标校验的有效操作限速，避免错误包阻塞合法 GUI 操作。
        if (!tryAcquirePacketSlot(actionTicks, player, ACTION_PACKET_INTERVAL_TICKS)) {
            return;
        }
        switch (payload.action()) {
            case ACTION_TOGGLE_ACTIVE -> {
                controller.setMachineActive(!controller.isMachineActive());

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
                if (controller instanceof CompactFluidizerController fluidizer) {
                    fluidizer.clearInputs();
                    tile.setChanged();
                    CompactExtremeReactor.LOGGER.debug("压缩流化器清空进料 @ {}", payload.pos());
                }
            }
        }
    }
}
