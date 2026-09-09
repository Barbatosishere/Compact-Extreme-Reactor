package com.compact.extremereactor.common.tile;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.capability.CompactEnergyStorage;
import com.compact.extremereactor.common.capability.CompactReactorFluidHandler;
import com.compact.extremereactor.common.capability.DeferredFluidHandler;
import com.compact.extremereactor.common.multiblock.ICompactController;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 压缩机器 TileEntity 基类。
 *
 * 职责：
 * 1. 延迟初始化并驱动 ER 多方块控制器模拟（见 {@link ICompactController}）。
 *    由于 TileEntity 构造时拿不到 Level，控制器统一排队到顶层服务端 tick 创建；
 *    存档中的控制器 NBT 先暂存，初始化完成后再恢复。
 * 2. NBT 存取：委托给控制器的 syncDataFrom / syncDataTo（与 ER 原生存档格式一致，
 *    存的是燃料/蒸汽/能量/转子状态，容量由模拟装配重新计算）。
 * 3. 能力暴露：IEnergyStorage（提取功率）+ IFluidHandler（进料/出料）。
 * 4. 每服务端刻由 {@link ServerTickEvent} 驱动的全局 tick：模拟 PowerTap，
 *    向 6 个相邻方块的能源接口主动推送能量、驱动控制器模拟。
 *
 * <p><b>为什么用 ServerTickEvent 而不是方块 ticker：</b>
 * NeoForge 1.21.1 的 {@code setblock} 路径（{@code Level.setBlock} → {@code LevelChunk.addEntity}
 * → {@code LevelChunk.setBlockEntity}）不注册方块 ticker（ticker 注册只发生在
 * {@code LevelChunk.addAndRegisterBlockEntity()} → {@code updateBlockEntityTicker()}，
 * 而 {@code setBlockEntity} 不调用它）。因此 {@code setblock} 放置的机器（含 vanilla 熔炉）
 * 在 1.21.1 上 {@code serverTick()} 永不执行。改用全局事件驱动，绕开 ticker 机制。</p>
 */
public abstract class AbstractCompactMachineTileEntity extends BlockEntity {

    /** 每游戏刻向相邻方块推送的最大能量（FE/t），模拟 PowerTap 的输出上限。 */
    protected static final long POWER_TRANSFER_RATE = 1_000_000L;

    /** POWER_TRANSFER_RATE 的 WideAmount 缓存（避免 pushPower 每方向每 tick 创建新对象）。 */
    protected static final WideAmount POWER_TRANSFER_AMOUNT = WideAmount.from(POWER_TRANSFER_RATE);

    /**
     * 缓存的方向数组（消除 pushPower 每 tick 调 {@code Direction.values()} 创建新数组的 GC 压力）。
     * Minecraft 的 {@code Direction.values()} 实际返回 {@code $VALUES.clone()}——每次都新建数组。
     */
    private static final Direction[] DIRS = Direction.values();

    /** 所有已加载的压缩机器实例（服务端），由 ServerTickEvent 驱动 tick。
     * 用 {@link ConcurrentHashMap#newKeySet()} 实现：当前所有写入都在服务端主线程，
     * 但 set 的 CAS/无锁语义对未来潜在的 worker-thread 路径免疫（zero-cost 主线程访问）。 */
    private static final Set<AbstractCompactMachineTileEntity> TICKING_MACHINES = ConcurrentHashMap.newKeySet();

    /**
     * 待初始化的机器（服务端）：{@code setLevel()}/{@code onLoad()} 只把机器加入此队列，
     * 真正的 {@code initController()} 调用推迟到下一次 {@link ServerTickEvent.Post} 触发时
     * （见 {@link #handleServerTick}），确保不会在 chunk 反序列化的嵌套调用栈内同步执行。
     *
     * <p><b>为什么不能在 setLevel()/onLoad() 里直接同步调 initController()：</b>
     * 读档时 {@code setLevel()} 是从 {@code ChunkSerializer.postLoadChunk()} 内、经
     * {@code MinecraftServer.pollTask() → managedBlock()} 嵌套调用的。若在这条调用栈里
     * 同步执行 {@code initController()} 末尾的 {@code setChanged()}，会经
     * {@code Level.updateNeighbourForOutputSignal() → getBlockState() → getChunk()}
     * 反过来同步等待"当前正在加载的同一个 chunk"，形成自我阻塞。
     * {@code level.getServer().execute(...)} **不能**规避这个问题——{@code BlockableEventLoop.execute()}
     * 在调用线程就是被管理线程（这里就是 Server thread）时会直接内联执行任务，并不会真正推迟。
     * 只有 {@link ServerTickEvent.Post} 这种由 NeoForge 在 tick 循环顶层派发的事件，
     * 才保证不嵌套在 chunk 加载栈内——两次实测复现（jcmd Thread.print 确认卡在
     * "Preparing spawn area"）+ 两次验证修复方案。</p>
     */
    private static final Set<AbstractCompactMachineTileEntity> PENDING_INIT = ConcurrentHashMap.newKeySet();

    /** 控制器 NBT 顶层 key（用于 saveAdditional / loadAdditional 嵌套存根）。 */
    public static final String NBT_KEY_CONTROLLER = "controller";

    /** 模拟控制器（仅服务端创建；客户端为 null）。 */
    @Nullable
    protected volatile ICompactController _controller;

    /** 反序列化时控制器尚未创建时暂存的 NBT，待初始化完成后应用。
     * volatile 保证 setLevel（loadControllerData 写）→ initController（读）的内存可见性。 */
    @Nullable
    private volatile CompoundTag _pendingControllerTag;

    /** 能力对象缓存（NeoForge 1.21 能力系统直接返回对象，无需 LazyOptional）。 */
    @Nullable
    private CompactEnergyStorage _energyStorage;

    @Nullable
    private IFluidHandler _fluidHandler;

    /** 稳定的流体能力代理；控制器尚未初始化时只排队，不触发初始化副作用。 */
    @Nullable
    private IFluidHandler _fluidCapabilityHandler;

    /** 卸载、移除或失败清理期间阻止能力查询重新懒初始化控制器。 */
    private volatile boolean _runtimeUnavailable;

    public AbstractCompactMachineTileEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ------------------------------------------------------------------
    // 全局 tick 驱动（ServerTickEvent）
    // ------------------------------------------------------------------

    /** 注册 ServerTickEvent 处理器（由主类在 mod 构造时调用一次）。 */
    public static void registerServerTickHandler() {
        NeoForge.EVENT_BUS.addListener(AbstractCompactMachineTileEntity::handleServerTick);
    }

    /** 每个服务端游戏刻遍历所有已加载机器，驱动 serverTick()。 */
    private static void handleServerTick(ServerTickEvent.Post event) {
        // 先处理待初始化队列：此时已脱离 chunk 加载/setLevel 调用栈，可安全同步调 initController()。
        if (!PENDING_INIT.isEmpty()) {
            final var pendingIt = PENDING_INIT.iterator();
            while (pendingIt.hasNext()) {
                final AbstractCompactMachineTileEntity be = pendingIt.next();
                pendingIt.remove();
                final Level level = be.level;
                if (be.isRemoved() || level == null || level.isClientSide) {
                    continue;
                }
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
        // 单遍遍历：边清理失效/已卸载的机器，边驱动存活机器 tick。
        // 用迭代器而非 removeIf+for，避免对集合做两遍遍历。
        final var it = TICKING_MACHINES.iterator();
        while (it.hasNext()) {
            final AbstractCompactMachineTileEntity be = it.next();
            final Level level = be.level;
            // 清理已失效/已卸载的机器：isRemoved、level 为空、客户端、区块已卸载
            if (be.isRemoved() || level == null || level.isClientSide
                    || !level.isLoaded(be.worldPosition)) {
                it.remove();
                continue;
            }
            // 单机器 tick 异常隔离：单台机器崩溃（ER 内部 NPE/IOOB）不应导致
            // 本 tick 后续所有机器都停转；标记该机器 _initFailed 并从 set 移除
            try {
                be.serverTick();
            } catch (Throwable t) {
                CompactExtremeReactor.LOGGER.error("Compact {} serverTick 异常 @{}, 机器已被禁用",
                        be.getClass().getSimpleName(), be.worldPosition, t);
                be.markInitFailedAndUnregister();
            }
        }
    }

    // ------------------------------------------------------------------
    // 控制器生命周期
    // ------------------------------------------------------------------

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

    /**
     * 控制器初始化失败标志位：true 时 {@link #initController()} 立即返回，
     * 避免外部依赖（ER 类加载失败、配置越界等）导致每 tick 重试并 log spam。
     * {@code volatile} 保证 serverTick / saveAdditional / setChanged 等多路径的内存可见性。
     */
    private volatile boolean _initFailed;

    private synchronized void initController() {
        if (this.level == null || this.level.isClientSide || this._controller != null
                || this._runtimeUnavailable || this._initFailed) {
            return;
        }
        // 方法级 synchronized 持锁期间二次守卫：防御未来重入路径
        // （如 onControllerInitialized 回调）在半初始化状态下的穿透。
        if (this._controller != null || this._runtimeUnavailable || this._initFailed) {
            return;
        }
        try {
            this._controller = this.createController();
            // 流体脏标记接线：管道 fill/drain 落到 ER FluidContainer 后立即 setChanged，
            // 流体内容尽快落盘（否则依赖 serverTick 的 1 秒兜底保存）
            this._controller.setFluidDirtyCallback(this::setChanged);
            // 1. 模拟装配：用配置的模拟尺寸初始化能量/流体/燃料容量
            this._controller.simulateAssembly();
            this.onControllerInitialized(this._controller);
            // 2. 应用暂存的存档数据（能量内容、燃料、蒸汽等）
            if (this._pendingControllerTag != null) {
                this._controller.syncDataFrom(this._pendingControllerTag, this.level.registryAccess(),
                        ISyncableEntity.SyncReason.FullSync);
                this._pendingControllerTag = null;
            }
            // 3. 创建真实流体处理器；对外能力代理保持同一对象并自动开始转发
            this._fluidHandler = this.createFluidHandler();
            // 初始化完成后立即标记脏数据：新机器的激活状态/容量/初始控制器数据
            // 必须及时进入存档，而不是等到每 20 tick 或首个 GUI 操作才保存。
            // initController() 只从 handleServerTick() 的 PENDING_INIT 处理调用，
            // 不嵌套在 chunk 加载调用栈内，可以安全同步调用 setChanged()。
            this.setChanged();
        } catch (Throwable t) {
            // 初始化失败（ER API 不兼容、配置越界等）：标记失败并清理半成品状态。
            // 失败机器不会进入 TICKING_MACHINES（setLevel 守卫）、不会反复重试（_initFailed），
            // 玩家在 GUI 中也能看到"控制器不可用"的反馈。
            CompactExtremeReactor.LOGGER.error("Compact {} 初始化控制器失败 @{}, 机器将被禁用",
                    this.getClass().getSimpleName(), this.worldPosition, t);
            this.snapshotControllerData();
            this._runtimeUnavailable = true;
            this._initFailed = true;
            this.releaseEnergyStorage();
            if (this._controller != null) {
                this._controller.releaseFluidHandlers();
            }
            this._controller = null;
            this.releaseFluidHandlers();
            this.onControllerReleased();
            this.invalidateCapabilities();
            // 保留 snapshot/pending NBT，确保初始化异常不会把已恢复的机器状态写成空数据。
            this.markChangedSafely();
        }
    }

    /**
     * 将当前 controller 状态暂存到 pending NBT，再允许释放 controller。
     * 快照失败时保留已有 pending 数据，避免异常处理路径进一步扩大状态损失。
     */
    private void snapshotControllerData() {
        final ICompactController controller = this._controller;
        final Level level = this.level;
        if (controller == null || level == null || this._pendingControllerTag != null) {
            return;
        }
        final CompoundTag snapshot = new CompoundTag();
        try {
            controller.syncDataTo(snapshot, level.registryAccess(), ISyncableEntity.SyncReason.FullSync);
            if (!snapshot.isEmpty()) {
                this._pendingControllerTag = snapshot;
            }
        } catch (Throwable t) {
            CompactExtremeReactor.LOGGER.error("Compact {} 保存控制器异常 @{}，保留已有 pending 数据",
                    this.getClass().getSimpleName(), this.worldPosition, t);
        }
    }

    /** 释放能量包装，使外部已经缓存的旧能力引用立即 fail-closed。 */
    private void releaseEnergyStorage() {
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
        if (this._fluidCapabilityHandler instanceof DeferredFluidHandler deferredHandler) {
            deferredHandler.release();
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

    /**
     * 从 TICKING_MACHINES 注销并标记 init 失败（用于 serverTick 异常隔离）。
     * 公开以便 handleServerTick 异常 catch 块调用——单台机器崩溃不影响其他机器 tick。
     */
    public void markInitFailedAndUnregister() {
        TICKING_MACHINES.remove(this);
        PENDING_INIT.remove(this);
        this.snapshotControllerData();
        this._runtimeUnavailable = true;
        this._initFailed = true;
        this.releaseEnergyStorage();
        final ICompactController controller = this._controller;
        if (controller != null) {
            controller.releaseFluidHandlers();
        }
        this._controller = null;
        this.releaseFluidHandlers();
        this.onControllerReleased();
        // 保留 snapshot/pending NBT，防止 serverTick 异常后的下次保存丢失燃料、流体和能量。
        // 通知 NeoForge 能力监听方（如 BlockCapabilityCache）重新查询：
        // 防止"幽灵机器"——tick 已停但旧能力对象仍可被写入，能量/物品进入即消失
        this.invalidateCapabilities();
        this.markChangedSafely();
    }

    /** 控制器被释放（init 失败/方块移除）后的子类清理钩子：清空包装该控制器的派生对象（如物品处理器）。 */
    protected void onControllerReleased() {
    }

    /**
     * 控制器是否初始化失败（GUI 同步此标志给客户端以禁用按钮）。
     * 失败状态下 {@link #getController()} 永远返 null，机器不会进入 tick 列表。
     */
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

    @Override
    public void onLoad() {
        super.onLoad();
        // 只加入待初始化队列，不在此同步调用 initController()——onLoad 在读档路径上
        // 可能与 setLevel 一样嵌套在 ChunkSerializer.postLoadChunk 调用栈内。
        // 真正的初始化统一推迟到下一次 ServerTickEvent.Post（见 PENDING_INIT 字段注释）。
        if (this.level == null || this.level.isClientSide || this.isRemoved()) {
            return;
        }
        this._runtimeUnavailable = false;
        // Set.add 本身幂等：重复 onLoad 只会确保机器仍在全局 tick 集合中，
        // 不会重复 tick；关键是不能先 remove 已初始化的机器。
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
        controller.setFluidDirtyCallback(this::setChanged);
        if (this._fluidHandler == null) {
            this._fluidHandler = this.createFluidHandler();
        }
    }

    @Override
    public void setLevel(Level level) {
        // setLevel 可能在区块反序列化、复制或重新挂载路径中重复调用；这里只设置 Level，
        // 不修改 tick 集合，也不触发初始化，避免已初始化机器被静默移出全局驱动。
        super.setLevel(level);
    }

    /** 把机器加入待初始化队列（幂等：已初始化/已失败/已在队列中都不重复处理）。 */
    private void enqueuePendingInit() {
        if (this._controller == null && !this._runtimeUnavailable && !this._initFailed && !this.isRemoved()
                && this.level != null && !this.level.isClientSide) {
            PENDING_INIT.add(this);
        }
    }

    /** 创建流体能力包装（子类实现：反应堆=水进/蒸汽出，涡轮机=蒸汽进/水出）。
     * 无参：从已初始化的 {@code this._controller} 读取，避免重复传递参数。 */
    protected abstract IFluidHandler createFluidHandler();

    /** 控制器初始化前对外声明的逻辑流体槽数量。 */
    protected abstract int getPendingFluidTankCount();

    /** 控制器初始化前使用的静态流体过滤，不读取当前槽内容或容量。 */
    protected abstract boolean isPendingFluidValid(int tank, net.neoforged.neoforge.fluids.FluidStack stack);

    // ------------------------------------------------------------------
    // 游戏刻驱动
    // ------------------------------------------------------------------

    /** 每个服务端游戏刻调用一次（由 ServerTickEvent 驱动）：驱动控制器模拟并推送能量。 */
    public void serverTick() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        final ICompactController controller = this._controller;
        if (controller == null) {
            return;
        }
        // 驱动一游戏刻的机器逻辑（燃料消耗、热量、发电、流体循环等）
        controller.tick();
        // 模拟 PowerTap：向相邻方块主动推送能量
        this.pushPower(controller);
        // ER 控制器内部标记的是模拟 bounding box，不覆盖真实方块；控制器每 tick
        // 都可能改变燃料、热量、流体或能量，因此每 tick 标记真实方块，避免在区块
        // 卸载前落入“未 dirty 的尾部状态”而被保存系统跳过。
        this.setChanged();
    }

    /** 模拟 PowerTap 输出：向 6 个相邻方块的 IEnergyStorage 能力推送能量。 */
    protected void pushPower(ICompactController controller) {
        for (Direction dir : DIRS) {
            final BlockPos neighborPos = this.worldPosition.relative(dir);
            if (!this.level.isLoaded(neighborPos)) {
                continue;
            }
            // NeoForge 1.21 能力查询直接返回对象（null 表示无此能力）
            final IEnergyStorage neighbor =
                    this.level.getCapability(Capabilities.EnergyStorage.BLOCK, neighborPos, dir.getOpposite());
            if (neighbor == null) {
                continue;
            }
            // 先模拟提取，确认可输出量
            final long available = controller.extractEnergy(EnergySystem.ForgeEnergy,
                    POWER_TRANSFER_AMOUNT, OperationMode.Simulate).longValue();
            if (available <= 0) {
                continue;
            }
            // 推送到相邻方块并真实扣除
            final int accepted = neighbor.receiveEnergy((int) Math.min(available, Integer.MAX_VALUE), false);
            if (accepted > 0) {
                controller.extractEnergy(EnergySystem.ForgeEnergy,
                        WideAmount.from(accepted), OperationMode.Execute);
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
        this.releaseEnergyStorage();
        final ICompactController controller = this._controller;
        if (controller != null) {
            controller.releaseFluidHandlers();
        }
        this._controller = null;
        this.releaseFluidHandlers();
        this.onControllerReleased();
        this.invalidateCapabilities();
    }

    /** 方块被移除时释放控制器与能力缓存，并从全局 tick 列表注销（由方块类调用）。 */
    public void onBlockRemoved() {
        TICKING_MACHINES.remove(this);
        PENDING_INIT.remove(this);
        this._runtimeUnavailable = true;
        this.releaseEnergyStorage();
        final ICompactController controller = this._controller;
        if (controller != null) {
            controller.releaseFluidHandlers();
        }
        this._controller = null;
        this.releaseFluidHandlers();
        this._pendingControllerTag = null;
        this.onControllerReleased();
        this.invalidateCapabilities();
    }

    // ------------------------------------------------------------------
    // 能力暴露（能量输出 / 流体进料出料）
    // ------------------------------------------------------------------

    /**
     * 能量能力（由 RegisterCapabilitiesEvent 注册，见主类）。
     * 单方块机器对方向无差别，side 参数忽略。
     */
    @Nullable
    public IEnergyStorage getEnergyStorage(@Nullable Direction side) {
        if (!this.canExposeRuntimeCapabilities()) {
            return null;
        }
        this.enqueuePendingInit();
        if (this._energyStorage == null) {
            this._energyStorage = new CompactEnergyStorage(() -> this._controller);
        }
        return this._energyStorage;
    }

    /** 流体能力（由 RegisterCapabilitiesEvent 注册，见主类）。 */
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

    // ------------------------------------------------------------------
    // NBT 持久化：委托给控制器
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag controllerTag = new CompoundTag();
        saveControllerData(controllerTag, registries);
        // 控制器尚未初始化且无暂存数据时不写键，避免空数据覆盖旧存档
        if (!controllerTag.isEmpty()) {
            tag.put(NBT_KEY_CONTROLLER, controllerTag);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains(NBT_KEY_CONTROLLER)) {
            CompoundTag controllerTag = tag.getCompound(NBT_KEY_CONTROLLER);
            if (controllerTag != null && !controllerTag.isEmpty()) {
                loadControllerData(controllerTag, registries);
            }
        }
    }

    /** 控制器数据写入 NBT（委托给控制器 syncDataTo，全量存档）。 */
    protected void saveControllerData(CompoundTag tag, HolderLookup.Provider registries) {
        if (this._controller != null) {
            this._controller.syncDataTo(tag, registries, ISyncableEntity.SyncReason.FullSync);
        } else if (this._pendingControllerTag != null) {
            // 控制器尚未初始化（加载后未 tick 就被保存）：原样写回暂存数据，防止丢失
            tag.merge(this._pendingControllerTag);
        }
    }

    /** 从 NBT 恢复控制器数据；控制器未创建时暂存，待初始化后应用。 */
    protected void loadControllerData(CompoundTag tag, HolderLookup.Provider registries) {
        if (this._controller != null) {
            this._controller.syncDataFrom(tag, registries, ISyncableEntity.SyncReason.FullSync);
        } else {
            this._pendingControllerTag = tag;
        }
    }

    // ------------------------------------------------------------------
    // 网络同步：方块实体变更时向客户端发送完整 NBT
    // ------------------------------------------------------------------

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithFullMetadata(registries);
    }
}