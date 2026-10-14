package com.compact.extremereactor.common.tile;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.capability.CompactEnergyStorage;
import com.compact.extremereactor.common.capability.CompactReactorFluidHandler;
import com.compact.extremereactor.common.capability.DeferredFluidHandler;
import com.compact.extremereactor.common.multiblock.ICompactController;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.structure.StructureInventory;
import com.compact.extremereactor.common.structure.StructureKind;
import com.compact.extremereactor.common.structure.StructureState;
import net.minecraft.core.NonNullList;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 管理控制器的延迟初始化、逐 tick 模拟、存档和能力生命周期。 */
public abstract class AbstractCompactMachineTileEntity extends BlockEntity {
    private StructureInventory structureInventory;
    private StructureState structureState;
    private int structureRevision = -1;
    private int assembledScale = -1;
    private int assembledRevision = -1;
    private boolean structureDropped;

    public StructureKind getStructureKind() {
        if (this instanceof CompactReactorTileEntity) return StructureKind.REACTOR;
        if (this instanceof CompactTurbineTileEntity) return StructureKind.TURBINE;
        return StructureKind.FLUIDIZER;
    }

    public final StructureInventory getStructureInventory() {
        if (this.structureInventory == null) {
            this.structureInventory = new StructureInventory(this.getStructureKind());
            this.structureInventory.addListener(container -> {
                this.structureState = null;
                if (this.level != null && !this.level.isClientSide) this.setChanged();
            });
        }
        return this.structureInventory;
    }

    public final StructureState getStructureState() {
        int revision = CompactConfig.structureRevision;
        if (this.structureState == null || this.structureRevision != revision) {
            this.structureState = StructureState.read(
                    this.getStructureKind(), this.getStructureInventory());
            this.structureRevision = revision;
        }
        return this.structureState;
    }

    protected final boolean prepareStructureTick() {
        int scale = Math.max(100_000, this.getStructureState().scaleUnits());
        if (this._controller != null && (this.assembledScale != scale || this.assembledRevision != this.structureRevision)) {
            this.snapshotControllerData();
            this.releaseController();
            this.onControllerReleased();
            this.invalidateCaps();
            TICKING_MACHINES.remove(this);
            super.reviveCaps();
            this.enqueuePendingInit();
            return false;
        }
        return true;
    }

    /** 每游戏刻向相邻方块推送的最大能量（FE/t），模拟 PowerTap 的输出上限。 */
    protected static final long POWER_TRANSFER_RATE = 1_000_000L;

    /** POWER_TRANSFER_RATE 的 WideAmount 缓存（避免 pushPower 每方向每 tick 创建新对象）。 */
    protected static final WideAmount POWER_TRANSFER_AMOUNT = WideAmount.from(POWER_TRANSFER_RATE);

    /** 缓存方向数组，避免每 tick 克隆 Direction.values()。 */
    private static final Direction[] DIRS = Direction.values();

    @Nullable
    private PowerNeighbor[] _powerNeighbors;

    private boolean _inControllerTick;

    /** 正能力随 LazyOptional 失效；空能力每次重查，兼容原实体后续暴露能力。 */
    private static final class PowerNeighbor {
        private final BlockPos position;
        private final Direction side;
        @Nullable
        private BlockEntity entity;
        private LazyOptional<IEnergyStorage> capability = LazyOptional.empty();

        private PowerNeighbor(BlockPos position, Direction side) {
            this.position = position;
            this.side = side;
        }

        @Nullable
        private IEnergyStorage getCapability(Level level) {
            if (!level.isLoaded(this.position)) {
                this.entity = null;
                this.capability = LazyOptional.empty();
                return null;
            }
            final BlockEntity current = level.getBlockEntity(this.position);
            if (current == null || current.isRemoved()) {
                this.entity = null;
                this.capability = LazyOptional.empty();
                return null;
            }
            if (current != this.entity || !this.capability.isPresent()) {
                this.entity = current;
                this.capability = current.getCapability(ForgeCapabilities.ENERGY, this.side);
            }
            return this.capability.orElse(null);
        }
    }

    /** tick 注册表；迭代期间的生命周期回调可能增删机器。 */
    private static final Set<AbstractCompactMachineTileEntity> TICKING_MACHINES = ConcurrentHashMap.newKeySet();

    /** 装配推迟到顶层服务端 tick，避免读档时反向等待正在加载的区块。Server.execute 在主线程可能内联，不能替代此队列。 */
    private static final Set<AbstractCompactMachineTileEntity> PENDING_INIT = ConcurrentHashMap.newKeySet();

    @Nullable
    protected volatile ICompactController _controller;

    /** 初始化前暂存的控制器 NBT；卸载快照也通过此字段恢复。 */
    @Nullable
    private volatile CompoundTag _pendingControllerTag;

    /** 控制器 NBT 顶层 key（用于 saveAdditional / loadAdditional 嵌套存根）。 */
    public static final String NBT_KEY_CONTROLLER = "controller";

    /** 能力对象缓存（Forge 1.20 能力系统经 LazyOptional 暴露，此处缓存实际对象）。 */
    @Nullable
    private CompactEnergyStorage _energyStorage;

    @Nullable
    private IFluidHandler _fluidHandler;

    /** 稳定的流体能力代理；控制器尚未初始化时只排队，不触发初始化副作用。 */
    @Nullable
    private DeferredFluidHandler _fluidCapabilityHandler;

    /** 卸载、移除或失败清理期间阻止能力查询重新懒初始化控制器。 */
    private volatile boolean _runtimeUnavailable;

    /** LazyOptional 缓存：同一能力必须返回同一实例并在失效时通知缓存方（Forge 规范）。 */
    @Nullable
    private LazyOptional<IEnergyStorage> _energyCapability;

    @Nullable
    private LazyOptional<IFluidHandler> _fluidCapability;

    public AbstractCompactMachineTileEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }


    /** 创建模拟控制器（子类实现，从配置读取模拟参数）。 */
    protected abstract ICompactController createController();

    /** 控制器初始化完成钩子（子类可在此设置机器激活状态等）。 */
    protected void onControllerInitialized(ICompactController controller) {
    }

    /** 获取已初始化的控制器；尚未初始化时只排队，绝不在调用栈内同步装配。 */
    @Nullable
    public ICompactController getController() {
        if (this._controller == null && this.canExposeRuntimeCapabilities()) {
            this.enqueuePendingInit();
        }
        return this._controller;
    }

    /** 初始化失败后停止重试，避免逐 tick 重复报错。 */
    private volatile boolean _initFailed;

    private synchronized void initController() {
        if (this.level == null || this.level.isClientSide || this._controller != null
                || this._runtimeUnavailable || this._initFailed) {
            return;
        }
        try {
            this._controller = this.createController();
            this.assembledScale = Math.max(100_000, this.getStructureState().scaleUnits());
            this.assembledRevision = this.structureRevision;
            this._controller.setStructureReady(() -> this.getStructureState().ready());
            // 外部能力变更立即标记存档；模拟中的重复回调在 tick 末尾合并。
            this._controller.setFluidDirtyCallback(this::markControllerChanged);
            // 1. 模拟装配：用配置的模拟尺寸初始化能量/流体/燃料容量
            this._controller.simulateAssembly();
            this.onControllerInitialized(this._controller);
            // 2. 应用暂存的存档数据（能量内容、燃料、蒸汽等）
            if (this._pendingControllerTag != null) {
                this._controller.syncDataFrom(this._pendingControllerTag, ISyncableEntity.SyncReason.FullSync);
                this._pendingControllerTag = null;
            }
            // 3. 创建真实流体处理器；对外能力代理保持同一对象并自动开始转发
            this._fluidHandler = this.createFluidHandler();
            // 初始容量和状态立即参与存档。
            this.setChanged();
        } catch (Throwable t) {
            // 禁用失败机器，释放半成品并保留可恢复数据。
            CompactExtremeReactor.LOGGER.error("Compact {} 初始化控制器失败 @{}, 机器将被禁用",
                    this.getClass().getSimpleName(), this.worldPosition, t);
            this.snapshotControllerData();
            this._runtimeUnavailable = true;
            this._initFailed = true;
            this.releaseController();
            this.onControllerReleased();
            this.invalidateCaps();
            // 保留 snapshot/pending NBT，确保初始化异常不会把已恢复的机器状态写成空数据。
            this.markChangedSafely();
        }
    }

    /** 释放前快照控制器；已有 pending NBT 优先保留。 */
    private void snapshotControllerData() {
        final ICompactController controller = this._controller;
        if (controller == null || this._pendingControllerTag != null) {
            return;
        }
        final CompoundTag snapshot = new CompoundTag();
        try {
            controller.syncDataTo(snapshot, ISyncableEntity.SyncReason.FullSync);
            if (!snapshot.isEmpty()) {
                this._pendingControllerTag = snapshot;
            }
        } catch (Throwable t) {
            CompactExtremeReactor.LOGGER.error("Compact {} 保存控制器异常 @{}，保留已有 pending 数据",
                    this.getClass().getSimpleName(), this.worldPosition, t);
        }
    }

    private void releaseController() {
        this.releaseEnergyStorage();
        final ICompactController controller = this._controller;
        if (controller != null) {
            controller.releaseFluidHandlers();
        }
        this._controller = null;
        this.releaseFluidHandlers();
    }

    /** 释放能量包装，使外部已经缓存的旧能力引用立即 fail-closed。 */
    private void releaseEnergyStorage() {
        this._powerNeighbors = null;
        final CompactEnergyStorage storage = this._energyStorage;
        if (storage != null) {
            storage.release();
            this._energyStorage = null;
        }
    }

    /** 释放真实流体处理器和稳定代理，使卸载前已缓存的引用立即 fail-closed。 */
    private void releaseFluidHandlers() {
        if (this._fluidHandler instanceof CompactReactorFluidHandler reactorFluidHandler) {
            reactorFluidHandler.release();
        }
        this._fluidHandler = null;
        if (this._fluidCapabilityHandler != null) {
            this._fluidCapabilityHandler.release();
        }
        this._fluidCapabilityHandler = null;
    }

    /** 异常隔离路径尽力标记方块脏，不能让二次异常覆盖原始故障。 */
    private void markChangedSafely() {
        try {
            if (this.level != null && !this.level.isClientSide) {
                this.setChanged();
            }
        } catch (Throwable t) {
            CompactExtremeReactor.LOGGER.warn("Compact {} 标记存档变更失败 @{}",
                    this.getClass().getSimpleName(), this.worldPosition, t);
        }
    }

    /** 禁用异常机器并保留存档快照，使旧能力引用失效。 */
    public void markInitFailedAndUnregister() {
        TICKING_MACHINES.remove(this);
        PENDING_INIT.remove(this);
        this.snapshotControllerData();
        this._runtimeUnavailable = true;
        this._initFailed = true;
        this.releaseController();
        this.onControllerReleased();
        // 保留快照，并通知能力缓存方丢弃旧引用。
        this.invalidateCaps();
        this.markChangedSafely();
    }

    /** 控制器被释放（init 失败/方块移除）后的子类清理钩子：清空包装该控制器的派生对象（如物品处理器）。 */
    protected void onControllerReleased() {
    }

    /** GUI 用此标志显示初始化失败并禁用操作。 */
    public boolean isControllerInitFailed() {
        return this._initFailed;
    }

    /** 控制器是否已完成初始化并可处理玩家操作；纯状态读取，不触发延迟初始化。 */
    public boolean isControllerReady() {
        return this._controller != null && !this._runtimeUnavailable && !this._initFailed;
    }

    /** 是否有待恢复的存档 NBT（子类 onControllerInitialized 用于区分"读档"与"新放置"）。 */
    protected final boolean hasPendingControllerTag() {
        return this._pendingControllerTag != null;
    }

    // 全局 tick 驱动（ServerTickEvent）

    /** 注册 ServerTickEvent 处理器（由主类在 mod 构造时调用一次）。 */
    public static void registerServerTickHandler() {
        MinecraftForge.EVENT_BUS.addListener(AbstractCompactMachineTileEntity::handleServerTick);
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> clearRuntimeSets());
    }

    private static void clearRuntimeSets() {
        TICKING_MACHINES.clear();
        PENDING_INIT.clear();
    }

    /** 每个服务端游戏刻遍历所有已加载机器，驱动 serverTick()。 */
    private static void handleServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (PENDING_INIT.isEmpty() && TICKING_MACHINES.isEmpty()) {
            return;
        }
        final MachineTickContext tickContext = new MachineTickContext();
        // 先处理待初始化队列：此时已脱离 chunk 加载/onLoad 调用栈，可安全同步调 initController()。
        if (!PENDING_INIT.isEmpty()) {
            final var pendingIt = PENDING_INIT.iterator();
            while (pendingIt.hasNext()) {
                final AbstractCompactMachineTileEntity be = pendingIt.next();
                final net.minecraft.world.level.Level level = be.level;
                if (be.isRemoved() || level == null || level.isClientSide) {
                    pendingIt.remove();
                    continue;
                }
                if (!tickContext.shouldTick(level, be.worldPosition)) {
                    continue;
                }
                pendingIt.remove();
                try {
                    be.initController();
                } catch (Throwable t) {
                    CompactExtremeReactor.LOGGER.error("Compact {} 待初始化处理异常 @{}, 机器已被禁用",
                            be.getClass().getSimpleName(), be.worldPosition, t);
                    be.markInitFailedAndUnregister();
                    continue;
                }
                if (be._controller != null) {
                    TICKING_MACHINES.add(be);
                }
            }
        }

        final var it = TICKING_MACHINES.iterator();
        while (it.hasNext()) {
            final AbstractCompactMachineTileEntity be = it.next();
            final net.minecraft.world.level.Level level = be.level;
            if (be.isRemoved() || level == null || level.isClientSide) {
                it.remove();
                continue;
            }
            if (!tickContext.shouldTick(level, be.worldPosition)) {
                continue;
            }
            // 隔离单台机器异常，允许其余机器继续运行。
            try {
                be.serverTick();
            } catch (Throwable t) {
                CompactExtremeReactor.LOGGER.error("Compact {} serverTick 异常 @{}, 机器已被禁用",
                        be.getClass().getSimpleName(), be.worldPosition, t);
                be.markInitFailedAndUnregister();
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 此处仍可能处于区块加载栈；由 PENDING_INIT 在服务端 tick 完成装配。
        if (this.level == null || this.level.isClientSide || this.isRemoved()) {
            return;
        }
        this._runtimeUnavailable = false;
        // 重复 onLoad 保持已初始化机器的注册，Set 避免重复 tick。
        if (this._controller != null) {
            this.restoreRuntimeHandlers();
            TICKING_MACHINES.add(this);
        } else {
            this.enqueuePendingInit();
        }
    }

    /** 重新创建区块卸载后失效的能力包装，不触发控制器初始化或存档写入。 */
    private void restoreRuntimeHandlers() {
        final ICompactController controller = this._controller;
        if (controller == null || this._runtimeUnavailable || this.level == null || this.level.isClientSide) {
            return;
        }
        controller.setFluidDirtyCallback(this::markControllerChanged);
        if (this._fluidHandler == null) {
            this._fluidHandler = this.createFluidHandler();
        }
    }

    /** 把机器加入待初始化队列（幂等：已初始化/已失败/已在队列中都不重复处理）。 */
    private void enqueuePendingInit() {
        if (this._controller == null && !this._runtimeUnavailable && !this._initFailed && !this.isRemoved()
                && this.level != null && !this.level.isClientSide) {
            PENDING_INIT.add(this);
        }
    }

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        if (this.level != level) {
            this._powerNeighbors = null;
        }
        super.setLevel(level);

    }

    /** 为已初始化的控制器创建流体能力包装。 */
    protected abstract IFluidHandler createFluidHandler();

    /** 控制器初始化前对外声明的逻辑流体槽数量。 */
    protected abstract int getPendingFluidTankCount();

    /** 控制器初始化前使用的静态流体过滤，不读取当前槽内容或容量。 */
    protected abstract boolean isPendingFluidValid(int tank, net.minecraftforge.fluids.FluidStack stack);


    /** 每个服务端游戏刻调用一次：驱动控制器模拟并推送能量。 */
    public void serverTick() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        if (!this.prepareStructureTick()) return;
        final ICompactController controller = this._controller;
        if (controller == null) {
            return;
        }
        // 驱动一游戏刻的机器逻辑（燃料消耗、热量、发电、流体循环等）
        this.tickController(controller);
        // 模拟 PowerTap：向相邻方块主动推送能量
        this.pushPower(controller);
    }

    /** 当 tick 标记真实区块，保证模拟后的燃料、热量和缓冲数据参与下一次保存。 */
    protected final void tickController(ICompactController controller) {
        this._inControllerTick = true;
        try {
            controller.tick();
        } finally {
            this._inControllerTick = false;
            this.markControllerChanged();
        }
    }

    /**
     * 机器没有比较器输出。高频控制器回调只需标记区块，不需要遍历六面邻居。
     * GUI/物品操作等显式 setChanged() 仍使用原版的完整通知路径。
     */
    private void markControllerChanged() {
        if (!this._inControllerTick && this.level != null && !this.level.isClientSide) {
            this.level.blockEntityChanged(this.worldPosition);
        }
    }

    /** 模拟 PowerTap 输出：向 6 个相邻方块的 IEnergyStorage 能力推送能量。 */
    protected void pushPower(ICompactController controller) {
        long available = controller.extractEnergy(EnergySystem.ForgeEnergy,
                POWER_TRANSFER_AMOUNT, OperationMode.Simulate).longValue();
        if (available <= 0) {
            return;
        }
        if (this._powerNeighbors == null) {
            this._powerNeighbors = new PowerNeighbor[DIRS.length];
            for (Direction dir : DIRS) {
                this._powerNeighbors[dir.ordinal()] = new PowerNeighbor(this.worldPosition.relative(dir), dir.getOpposite());
            }
        }
        boolean refreshAvailable = false;
        for (PowerNeighbor target : this._powerNeighbors) {
            final IEnergyStorage neighbor = target.getCapability(this.level);
            if (neighbor == null) {
                continue;
            }
            // Reuse the initial simulation for the first receiver; each side keeps its own limit.
            if (refreshAvailable) {
                available = controller.extractEnergy(EnergySystem.ForgeEnergy,
                        POWER_TRANSFER_AMOUNT, OperationMode.Simulate).longValue();
                if (available <= 0) {
                    break;
                }
            }
            refreshAvailable = true;
            final int offered = (int) Math.min(available, Integer.MAX_VALUE);
            final int accepted = Math.min(offered, Math.max(0, neighbor.receiveEnergy(offered, false)));
            if (accepted > 0) {
                final long extracted = controller.extractEnergy(EnergySystem.ForgeEnergy,
                        WideAmount.from(accepted), OperationMode.Execute).longValue();
                if (extracted <= 0) {
                    break;
                }
            }
        }
    }

    /** 区块卸载时快照控制器并释放运行时对象；若实例被重挂载，则从 pending NBT 重建。 */
    @Override
    public void onChunkUnloaded() {
        TICKING_MACHINES.remove(this);
        PENDING_INIT.remove(this);
        this.snapshotControllerData();
        this._runtimeUnavailable = true;
        this.releaseController();
        this.onControllerReleased();
        this.invalidateCaps();
    }

    /** 方块被移除时释放控制器与能力缓存，并从全局 tick 列表注销（由方块类调用）。 */
    public void onBlockRemoved() {
        if (this.level != null && !this.level.isClientSide && !this.structureDropped) {
            this.structureDropped = true;
            this.getStructureInventory().dropContents(this.level, this.worldPosition, ItemStack::isSameItemSameTags);
        }
        TICKING_MACHINES.remove(this);
        PENDING_INIT.remove(this);
        this._runtimeUnavailable = true;
        this.releaseController();
        this._pendingControllerTag = null;
        this.onControllerReleased();
        this.invalidateCaps();
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        // Forge 1.20：失效已分发的 LazyOptional，让相邻线缆/机器及时丢弃缓存引用
        if (this._energyCapability != null) {
            this._energyCapability.invalidate();
            this._energyCapability = null;
        }
        if (this._fluidCapability != null) {
            this._fluidCapability.invalidate();
            this._fluidCapability = null;
        }
    }


    /**
     * 能量能力对象（由主类 AttachCapabilitiesEvent 注册）。
     * 单方块机器对方向无差别，side 参数忽略。
     */
    @Nullable
    public IEnergyStorage getEnergyStorage(@Nullable Direction side) {
        if (!this.canExposeRuntimeCapabilities()) {
            return null;
        }
        this.enqueuePendingInit();
        if (this._energyStorage == null) {
            this._energyStorage = this.createEnergyStorage();
        }
        return this._energyStorage;
    }

    /** 创建能量能力包装（子类覆写：流化器是能量消费方，需返回 {@link com.compact.extremereactor.common.capability.CompactEnergySink}）。 */
    protected CompactEnergyStorage createEnergyStorage() {
        return new CompactEnergyStorage(() -> this._controller);
    }

    /** 流体能力对象（由主类 AttachCapabilitiesEvent 注册）。 */
    @Nullable
    public IFluidHandler getFluidHandler(@Nullable Direction side) {
        if (!this.canExposeRuntimeCapabilities()) {
            return null;
        }
        this.enqueuePendingInit();
        this.restoreRuntimeHandlers();
        if (this._fluidCapabilityHandler == null) {
            this._fluidCapabilityHandler = new DeferredFluidHandler(
                    () -> this._fluidHandler,
                    this.getPendingFluidTankCount(),
                    this::isPendingFluidValid);
        }
        return this._fluidCapabilityHandler;
    }

    /** 子类能力仅在已挂载、服务端且未进入卸载/失败状态时对外暴露。 */
    protected final boolean canExposeRuntimeCapabilities() {
        return !this._runtimeUnavailable && !this._initFailed && !this.isRemoved()
                && this.level != null && !this.level.isClientSide;
    }

    /** 能量能力的 LazyOptional 视图（缓存单实例，失效后自动重建）。 */
    public LazyOptional<IEnergyStorage> getEnergyCapability(@Nullable Direction side) {
        final IEnergyStorage storage = this.getEnergyStorage(side);
        if (storage == null) {
            return LazyOptional.empty();
        }
        if (this._energyCapability == null || !this._energyCapability.isPresent()) {
            this._energyCapability = LazyOptional.of(() -> storage);
        }
        return this._energyCapability.cast();
    }

    /** 流体能力的 LazyOptional 视图（缓存单实例，失效后自动重建）。 */
    public LazyOptional<IFluidHandler> getFluidCapability(@Nullable Direction side) {
        final IFluidHandler handler = this.getFluidHandler(side);
        if (handler == null) {
            return LazyOptional.empty();
        }
        if (this._fluidCapability == null || !this._fluidCapability.isPresent()) {
            this._fluidCapability = LazyOptional.of(() -> handler);
        }
        return this._fluidCapability.cast();
    }


    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        var items = NonNullList.withSize(StructureInventory.SLOT_COUNT, ItemStack.EMPTY);
        for (int i = 0; i < items.size(); ++i) items.set(i, this.getStructureInventory().getItem(i));
        ContainerHelper.saveAllItems(tag, items);
        CompoundTag controllerTag = new CompoundTag();
        saveControllerData(controllerTag);
        // 控制器尚未初始化且无暂存数据时不写键，避免空数据覆盖旧存档
        if (!controllerTag.isEmpty()) {
            tag.put(NBT_KEY_CONTROLLER, controllerTag);
        }
    }

    // 47.1.x 分支的 BlockEntity 只暴露 load(CompoundTag)（无 loadAdditional），直接覆写 load
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        var items = NonNullList.withSize(StructureInventory.SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, items);
        for (int i = 0; i < items.size(); ++i) this.getStructureInventory().setItem(i, items.get(i));
        this.structureState = null;
        if (tag.contains(NBT_KEY_CONTROLLER)) {
            CompoundTag controllerTag = tag.getCompound(NBT_KEY_CONTROLLER);
            if (controllerTag != null && !controllerTag.isEmpty()) {
                loadControllerData(controllerTag);
            }
        }
    }

    /** 控制器数据写入 NBT（委托给控制器 syncDataTo，全量存档）。 */
    protected void saveControllerData(CompoundTag tag) {
        if (this._controller != null) {
            this._controller.syncDataTo(tag, ISyncableEntity.SyncReason.FullSync);
        } else if (this._pendingControllerTag != null) {
            // 控制器尚未初始化（加载后未 tick 就被保存）：原样写回暂存数据，防止丢失
            tag.merge(this._pendingControllerTag);
        }
    }

    /** 从 NBT 恢复控制器数据；控制器未创建时暂存，待初始化后应用。 */
    protected void loadControllerData(CompoundTag tag) {
        if (this._controller != null) {
            this._controller.syncDataFrom(tag, ISyncableEntity.SyncReason.FullSync);
        } else {
            this._pendingControllerTag = tag;
        }
    }


    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithFullMetadata();
    }
}
