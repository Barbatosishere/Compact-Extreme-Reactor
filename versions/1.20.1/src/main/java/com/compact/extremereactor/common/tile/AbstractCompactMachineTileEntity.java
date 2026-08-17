package com.compact.extremereactor.common.tile;

import com.compact.extremereactor.common.capability.CompactEnergyStorage;
import com.compact.extremereactor.common.multiblock.ICompactController;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 压缩机器 TileEntity 基类。
 *
 * 职责：
 * 1. 懒初始化并驱动 ER 多方块控制器模拟（见 {@link ICompactController}）。
 *    由于 TileEntity 构造时拿不到 Level，控制器延迟到首次 serverTick / 首次
 *    能力查询时创建；存档中的控制器 NBT 先暂存，初始化完成后再恢复。
 * 2. NBT 存取：委托给控制器的 syncDataFrom / syncDataTo（与 ER 原生存档格式一致，
 *    存的是燃料/蒸汽/能量/转子状态，容量由模拟装配重新计算）。
 * 3. 能力暴露：IEnergyStorage（提取功率）+ IFluidHandler（进料/出料），
 *    由主类的 AttachCapabilitiesEvent 以 ICapabilityProvider 注册。
 * 4. 模拟 PowerTap：每 tick 主动向 6 个相邻方块的能源接口推送能量，
 *    模拟真实多方块中 ActivePowerTapFE 的输出行为。
 */
public abstract class AbstractCompactMachineTileEntity extends BlockEntity {

    /** 每游戏刻向相邻方块推送的最大能量（FE/t），模拟 PowerTap 的输出上限。 */
    protected static final long POWER_TRANSFER_RATE = 1_000_000L;

    /** 模拟控制器（仅服务端创建；客户端为 null）。 */
    @Nullable
    protected ICompactController _controller;

    /** 反序列化时控制器尚未创建时暂存的 NBT，待初始化完成后应用。 */
    @Nullable
    private CompoundTag _pendingControllerTag;

    private boolean _initialized;

    /** 能力对象缓存（Forge 1.20 能力系统经 LazyOptional 暴露，此处缓存实际对象）。 */
    @Nullable
    private IEnergyStorage _energyStorage;

    @Nullable
    private IFluidHandler _fluidHandler;

    /** LazyOptional 缓存：同一能力必须返回同一实例并在失效时通知缓存方（Forge 规范）。 */
    @Nullable
    private LazyOptional<IEnergyStorage> _energyCapability;

    @Nullable
    private LazyOptional<IFluidHandler> _fluidCapability;

    public AbstractCompactMachineTileEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ------------------------------------------------------------------
    // 控制器生命周期
    // ------------------------------------------------------------------

    /** 创建模拟控制器（子类实现，从配置读取模拟参数）。 */
    protected abstract ICompactController createController();

    /** 控制器初始化完成钩子（子类可在此设置机器激活状态等）。 */
    protected void onControllerInitialized(ICompactController controller) {
    }

    /** 获取控制器；首次调用时在服务端执行初始化。客户端返回 null。 */
    @Nullable
    public ICompactController getController() {
        if (this._controller == null && this.level != null && !this.level.isClientSide) {
            this.initController();
        }
        return this._controller;
    }

    private void initController() {
        if (this.level == null || this.level.isClientSide || this._controller != null) {
            return;
        }
        this._controller = this.createController();
        // 1. 模拟装配：用配置的模拟尺寸初始化能量/流体/燃料容量
        this._controller.simulateAssembly();
        this.onControllerInitialized(this._controller);
        // 2. 应用暂存的存档数据（能量内容、燃料、蒸汽等）
        if (this._pendingControllerTag != null) {
            this._controller.syncDataFrom(this._pendingControllerTag, ISyncableEntity.SyncReason.FullSync);
            this._pendingControllerTag = null;
        }
        // 3. 创建能力对象（能量/流体）
        this._energyStorage = new CompactEnergyStorage(this._controller);
        this._fluidHandler = this.createFluidHandler(this._controller);
        this._initialized = true;
        // 初始化完成后立即标记脏数据：新机器的激活状态/容量/初始控制器数据
        // 必须及时进入存档，而不是等到每 20 tick 或首个 GUI 操作才保存
        this.setChanged();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        // 方块实体加入世界时即初始化控制器，新建机器不必等待首个 serverTick
        if (this.level != null && !this.level.isClientSide) {
            this.initController();
        }
    }

    @Override
    public void setLevel(net.minecraft.world.level.Level level) {
        super.setLevel(level);
        // setLevel 在方块实体挂载到世界时必定调用（含 setblock/玩家放置/加载存档），
        // 比 onLoad 更可靠：确保控制器在首个 tick 前就已初始化，GUI 开关/调节立即可用
        if (this.level != null && !this.level.isClientSide) {
            this.initController();
        }
    }

    /** 创建流体能力包装（子类实现：反应堆=水进/蒸汽出，涡轮机=蒸汽进/水出）。 */
    protected abstract IFluidHandler createFluidHandler(ICompactController controller);

    // ------------------------------------------------------------------
    // 游戏刻驱动
    // ------------------------------------------------------------------

    /** 每个服务端游戏刻调用一次：驱动控制器模拟并推送能量。 */
    public void serverTick() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        final ICompactController controller = this.getController();
        if (controller == null) {
            return;
        }
        // 驱动一游戏刻的机器逻辑（燃料消耗、热量、发电、流体循环等）
        controller.tick();
        // 模拟 PowerTap：向相邻方块主动推送能量
        this.pushPower(controller);
        // 周期标记本方块需要保存。ER 控制器内部标记的是模拟 bounding box，
        // 不覆盖真实方块，因此这里必须自己触发 setChanged()。
        if (this.level.getGameTime() % 20 == 0) {
            this.setChanged();
        }
    }

    /** 模拟 PowerTap 输出：向 6 个相邻方块的 IEnergyStorage 能力推送能量。 */
    protected void pushPower(ICompactController controller) {
        for (Direction dir : Direction.values()) {
            final BlockPos neighborPos = this.worldPosition.relative(dir);
            // 47.1.x 分支移除了 Level.getCapability(cap, pos, dir)，改为经方块实体查询
            final BlockEntity neighborEntity = this.level.getBlockEntity(neighborPos);
            final LazyOptional<IEnergyStorage> neighborOpt = neighborEntity == null
                    ? LazyOptional.empty()
                    : neighborEntity.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite());
            final IEnergyStorage neighbor = neighborOpt.orElse(null);
            if (neighbor == null) {
                continue;
            }
            // 先模拟提取，确认可输出量
            final long available = controller.extractEnergy(EnergySystem.ForgeEnergy,
                    WideAmount.from(POWER_TRANSFER_RATE), OperationMode.Simulate).longValue();
            if (available <= 0) {
                continue;
            }
            // 推送到相邻方块并真实扣除
            final int accepted = neighbor.receiveEnergy((int) Math.min(available, Integer.MAX_VALUE), false);
            if (accepted > 0) {
                controller.extractEnergy(EnergySystem.ForgeEnergy,
                        WideAmount.from(accepted), OperationMode.Execute);
                this.setChanged();
            }
        }
    }

    /** 方块被移除时释放控制器与能力缓存（由方块类调用）。 */
    public void onBlockRemoved() {
        this._controller = null;
        this._initialized = false;
        this._energyStorage = null;
        this._fluidHandler = null;
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

    // ------------------------------------------------------------------
    // 能力暴露（能量输出 / 流体进料出料）
    // ------------------------------------------------------------------

    /**
     * 能量能力对象（由主类 AttachCapabilitiesEvent 注册）。
     * 单方块机器对方向无差别，side 参数忽略。
     */
    @Nullable
    public IEnergyStorage getEnergyStorage(@Nullable Direction side) {
        this.getController(); // 触发懒初始化（服务端）
        return this._energyStorage;
    }

    /** 流体能力对象（由主类 AttachCapabilitiesEvent 注册）。 */
    @Nullable
    public IFluidHandler getFluidHandler(@Nullable Direction side) {
        this.getController(); // 触发懒初始化（服务端）
        return this._fluidHandler;
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

    // ------------------------------------------------------------------
    // NBT 持久化：委托给控制器
    // ------------------------------------------------------------------

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        CompoundTag controllerTag = new CompoundTag();
        saveControllerData(controllerTag);
        // 控制器尚未初始化且无暂存数据时不写键，避免空数据覆盖旧存档
        if (!controllerTag.isEmpty()) {
            tag.put("controller", controllerTag);
        }
    }

    // 47.1.x 分支的 BlockEntity 只暴露 load(CompoundTag)（无 loadAdditional），直接覆写 load
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("controller")) {
            loadControllerData(tag.getCompound("controller"));
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

    // ------------------------------------------------------------------
    // 网络同步：方块实体变更时向客户端发送完整 NBT
    // ------------------------------------------------------------------

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
