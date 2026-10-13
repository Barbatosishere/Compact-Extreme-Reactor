package com.compact.extremereactor.common.multiblock;

import com.compact.extremereactor.common.capability.BypassFluidHandler;
import it.zerono.mods.extremereactors.api.reactor.IHeatEntity;
import it.zerono.mods.extremereactors.api.reactor.Reactant;
import it.zerono.mods.extremereactors.api.reactor.radiation.EnergyConversion;
import it.zerono.mods.extremereactors.api.reactor.radiation.IRadiationModerator;
import it.zerono.mods.extremereactors.config.Config;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidContainer;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.FuelContainer;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.IHeat;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.IIrradiationSource;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.IReactorPartType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.MultiblockReactor;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.OperationalMode;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.ReactorPartType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.variant.ReactorVariant;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.geometry.CuboidBoundingBox;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/** 复用 ER 反应堆模拟，以配置尺寸和虚拟燃料棒代替实体多方块部件。 */
public class CompactReactorController extends MultiblockReactor implements ICompactController {
    private java.util.function.BooleanSupplier structureReady = () -> true;

    @Override
    public void setStructureReady(java.util.function.BooleanSupplier ready) {
        this.structureReady = ready;
    }

    /** 无操作辐射调节器：模拟反应堆没有真实外壳方块需要调节辐射。 */
    private static final IRadiationModerator NOOP_MODERATOR = (data, packet) -> {
    };

    /** 控制棒插入比例 NBT key（用于存档持久化自定义字段）。加 {@code cer:} 前缀避免与 ER 内部 key 冲突。 */
    public static final String NBT_KEY_CONTROL_ROD_RATIO = "cer:controlRodInsertionRatio";

    /** 读档兼容旧的无前缀 key；写档只用新 key。 */
    private static final String LEGACY_NBT_KEY_CONTROL_ROD_RATIO = "ControlRodInsertionRatio";

    /** ReactorLogic 被动分支常量（被动冷却的传热/输出效率）。 */
    private static final double PASSIVE_COOLING_TRANSFER_EFFICIENCY = 0.2d;
    private static final double PASSIVE_COOLING_POWER_EFFICIENCY = 0.5d;

    private final BlockPos _anchor;
    private final int fluidCapacity;
    private final int partCount;
    private final int _fuelRods;
    private final int _controlRods;
    private final int _powerTaps;
    private final int _sizeX;
    private final int _sizeY;
    private final int _sizeZ;

    /** 模拟边界框缓存（尺寸固定不变，避免每次 getBoundingBox() 创建新对象）。 */
    private final CuboidBoundingBox _cachedBoundingBox;

    /** 输入、输出分别复用旁路处理器。 */
    private BypassFluidHandler _cachedInputHandler;
    private BypassFluidHandler _cachedOutputHandler;

    /** 流体脏标记回调（由 TileEntity 经 setFluidDirtyCallback 注册，fill/drain 后触发 setChanged）。 */
    private Runnable _fluidDirtyCallback;

    @Override
    public void setFluidDirtyCallback(Runnable callback) {
        this._fluidDirtyCallback = callback;
        if (this._cachedInputHandler != null) {
            this._cachedInputHandler.setDirtyCallback(callback);
        }
        if (this._cachedOutputHandler != null) {
            this._cachedOutputHandler.setDirtyCallback(callback);
        }
    }

    /** 模拟控制棒插入比例（0-100），由 GUI 调节。 */
    private byte _controlRodInsertionRatio = 50;

    /** 最近一游戏刻被动等效 FE 补偿量（供 GUI 发电量显示）。 */
    private double _feGeneratedLastTick;

    private final IIrradiationSource _irradiationSource;

    public CompactReactorController(Level level, BlockPos anchor,
                                    int fuelRods, int controlRods, int powerTaps,
                                    int sizeX, int sizeY, int sizeZ) {
        this(level, anchor, fuelRods, controlRods, powerTaps, sizeX, sizeY, sizeZ, 1.0);
    }

    public CompactReactorController(Level level, BlockPos anchor,
                                    int fuelRods, int controlRods, int powerTaps,
                                    int sizeX, int sizeY, int sizeZ, double scale) {
        // Basic 无流体容量；Reinforced 支持水输入和主动冷却。
        super(level, ReactorVariant.Reinforced);
        this._anchor = anchor.immutable();
        this._fuelRods = fuelRods;
        this._controlRods = controlRods;
        this._powerTaps = powerTaps;
        this._sizeX = sizeX;
        this._sizeY = (int) Math.ceil((sizeY - 2) * scale) + 2;
        this._sizeZ = sizeZ;
        this._cachedBoundingBox = new CuboidBoundingBox(this._anchor, this._anchor.offset(sizeX - 1, this._sizeY - 1, sizeZ - 1));
        int exterior = sizeX * sizeY * sizeZ - (sizeX - 2) * (sizeY - 2) * (sizeZ - 2);
        long baselineFluid = Math.min((long) exterior * this.getVariant().getPartFluidCapacity(), this.getVariant().getMaxFluidCapacity());
        this.fluidCapacity = (int) Math.min(Integer.MAX_VALUE, Math.ceil(baselineFluid * scale));
        this.partCount = (int) Math.ceil((double) sizeX * sizeY * sizeZ * scale);
        this._irradiationSource = new SimulatedIrradiationSource(() -> this._controlRodInsertionRatio, this._anchor);
    }


    /** 模拟多方块"装配"：触发真实装配回调，初始化能量/燃料/流体容量。 */
    public void simulateAssembly() {
        this.onMachineAssembled();
        ((FluidContainer) this.getFluidContainer()).setCapacity(this.fluidCapacity);
        // 上游缓冲默认禁止插入；允许写入被动等效 FE。
        this.getEnergyBuffer().setMaxInsert(WideAmount.MAX_VALUE);
        // 无实体部件时须设置真实锚点，供 ZeroCore 的区块加载检查使用。
        this.recalculateCoords();
    }


    /** 无实体 FluidPort 时经能力直接访问容器；停机也允许外部输入和抽取。 */
    @Override
    public Optional<IFluidHandler> getFluidHandler(IoDirection direction) {

        if (direction == IoDirection.Input) {
            if (this._cachedInputHandler == null) {
                final FluidContainer container = (FluidContainer) this.getFluidContainer();
                this._cachedInputHandler = new BypassFluidHandler(container, true, container::getCapacity,
                        this._fluidDirtyCallback, FluidType.Liquid);
            }
            return Optional.of(this._cachedInputHandler);
        }
        if (this._cachedOutputHandler == null) {
            final FluidContainer container = (FluidContainer) this.getFluidContainer();
            this._cachedOutputHandler = new BypassFluidHandler(container, false, container::getCapacity,
                        this._fluidDirtyCallback, FluidType.Gas);
        }
        return Optional.of(this._cachedOutputHandler);
    }

    /** 释放缓存的旁路 handler，阻止卸载后的外部引用继续访问旧容器。 */
    public void releaseFluidHandlers() {
        if (this._cachedInputHandler != null) {
            this._cachedInputHandler.release();
            this._cachedInputHandler = null;
        }
        if (this._cachedOutputHandler != null) {
            this._cachedOutputHandler.release();
            this._cachedOutputHandler = null;
        }
        this._fluidDirtyCallback = null;
    }

    /** 将 ZeroCore 私有边界框设为实际方块，供区块加载检查和保存标记使用。 */
    @Override
    public void recalculateCoords() {
        CompactControllerBounds.setAnchor(this, this._anchor);
    }

    /** 每个服务端游戏刻驱动一次反应堆逻辑。 */
    public void tick() {
        if (!this.structureReady.getAsBoolean()) return;
        this.updateMultiblockEntity();
    }

    /** 无实体端口时跳过补料和排废扫描；原料与废物由方块能力处理。 */
    @Override
    public boolean performRefuelingCycle() {
        return !this.getConnectedParts().isEmpty() && super.performRefuelingCycle();
    }

    /** 冷却剂由方块流体能力输入，无需遍历空的多方块输入端口。 */
    @Override
    public boolean performInputCycle() {
        return !this.getConnectedParts().isEmpty() && super.performInputCycle();
    }

    /** 蒸汽经方块流体能力输出，FE 推送由方块实体负责。 */
    @Override
    public void performOutputCycle() {
        if (!this.getConnectedParts().isEmpty()) {
            super.performOutputCycle();
        }
    }

    /** 获取模拟控制棒插入比例（0-100）。 */
    public byte getControlRodInsertionRatio() {
        return this._controlRodInsertionRatio;
    }

    /** 设置模拟控制棒插入比例（0-100），与真实控制棒语义一致。 */
    public void setControlRodInsertionRatio(int ratio) {
        this._controlRodInsertionRatio = (byte) Math.clamp(ratio, 0, 100);
    }

    // 主动冷却模式 + 被动等效 FE 补偿

    /** 固定主动冷却模式，让无实体 FluidPort 的压缩堆仍能容纳水和蒸汽。 */
    @Override
    public OperationalMode getOperationalMode() {
        return OperationalMode.Active;
    }

    /** 主动模式产蒸汽，同时按被动传热公式补记 FE；同一热量双产出是本模组的既定行为。 */
    @Override
    protected boolean updateServer() {
        // 关闭或满仓时仅散去残余热量；抽出 FE 后自动恢复。
        if (!this.isMachineActive() || this.isEnergyBufferFull()) {
            this._feGeneratedLastTick = 0;
            this.performPassiveHeatLoss();
            return false;
        }
        // 补记被动等效 FE，再运行上游蒸汽、辐射和热量模拟。
        this._feGeneratedLastTick = 0;
        final double reactorHeat = this.getReactorHeat().getAsDouble();
        final double dT = reactorHeat - IHeatEntity.AMBIENT_HEAT;
        // 控制棒插入比例因子：0%=全功率(1.0)，100%=停堆(0.0)，匹配 radiate() 中的计算
        final double controlRodFactor = (100.0 - this._controlRodInsertionRatio) / 100.0;
        if (dT > 0.01d && controlRodFactor > 0.001d) {
            final double fe = dT * this.getReactorToCoolantSystemHeatTransferCoefficient()
                    * PASSIVE_COOLING_TRANSFER_EFFICIENCY * PASSIVE_COOLING_POWER_EFFICIENCY
                    * Config.COMMON.general.powerProductionMultiplier.get()
                    * Config.COMMON.reactor.reactorPowerProductionMultiplier.get()
                    * this.getVariant().getEnergyGenerationEfficiency()
                    * controlRodFactor;
            if (fe > 0.0d) {
                this.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.from(fe), OperationMode.Execute);
                this._feGeneratedLastTick = fe;
            }
        }
        return super.updateServer();
    }

    /** 最近一游戏刻被动等效 FE 补偿量（供 GUI 发电量显示）。 */
    @Override
    public double getEnergyGeneratedLastTick() {
        return this.structureReady.getAsBoolean() ? this._feGeneratedLastTick : 0;
    }

    /**
     * 复刻 ER {@link it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.ReactorLogic#performPassiveHeatLoss()}：
     * 机器关闭后不再产生新热，残余热量按"散热系数 × 温差"逐 tick 向环境温度(20°C)消散。
     * 散热系数 = 0.001 × 外壳表面积，在 simulateAssembly() → onMachineAssembled() 时初始化。
     */
    private void performPassiveHeatLoss() {
        final IHeat reactorHeat = this.getReactorHeat();
        final double dT = reactorHeat.getAsDouble() - IHeatEntity.AMBIENT_HEAT;
        if (dT > 1e-6d) {
            final double heatToRemove = Math.max(1.0d, dT * this.getReactorHeatLossCoefficient());
            final double energy = Math.max(0.0d, EnergyConversion.getEnergyFromVolumeAndTemperature(
                    this.getReactorVolume(), reactorHeat.getAsDouble()) - heatToRemove);
            reactorHeat.set(EnergyConversion.getTemperatureFromVolumeAndEnergy(
                    this.getReactorVolume(), energy));
        }
    }

    // NBT 持久化：ER 存档不含自定义控制棒比例，必须覆写补充保存

    @Override
    public CompoundTag syncDataTo(CompoundTag tag, HolderLookup.Provider registries, ISyncableEntity.SyncReason reason) {
        super.syncDataTo(tag, registries, reason);
        tag.putByte(NBT_KEY_CONTROL_ROD_RATIO, this._controlRodInsertionRatio);
        return tag;
    }

    @Override
    public void syncDataFrom(CompoundTag tag, HolderLookup.Provider registries, ISyncableEntity.SyncReason reason) {
        super.syncDataFrom(GeneratorEnergyPersistence.withCapacity(tag,
                this.getEnergyBuffer().getCapacity(EnergySystem.REFERENCE), WideAmount::serializeToNBT), registries, reason);
        // 兼容旧 key，并将损坏存档的控制棒比例限制到 [0, 100]。
        if (tag.contains(NBT_KEY_CONTROL_ROD_RATIO, Tag.TAG_BYTE)) {
            this._controlRodInsertionRatio = (byte) Math.max(0, Math.min(100, tag.getByte(NBT_KEY_CONTROL_ROD_RATIO)));
        } else if (tag.contains(LEGACY_NBT_KEY_CONTROL_ROD_RATIO, Tag.TAG_BYTE)) {
            this._controlRodInsertionRatio = (byte) Math.max(0, Math.min(100, tag.getByte(LEGACY_NBT_KEY_CONTROL_ROD_RATIO)));
        }
        // 旧存档会带出 ER2 发电机的 maxInsert=0（原生从不插入能量），
        // 补偿路径需要插入权限，恢复后强制打开
        this.getEnergyBuffer().setMaxInsert(WideAmount.MAX_VALUE);
    }

    /** 向燃料容器注入燃料（如黄钇矿铤），返回实际注入量。 */
    public int insertFuel(Reactant reactant, int amount, OperationMode mode) {
        if (this.getFuelContainer() instanceof FuelContainer fuel) {
            return fuel.insertFuel(reactant, amount, mode);
        }
        return 0;
    }

    /** 直接注入核废料（诊断命令构造测试态用），返回实际注入量。 */
    public int insertWaste(Reactant reactant, int amount) {
        if (amount > 0 && this.getFuelContainer() instanceof FuelContainer fuel) {
            return fuel.insertWaste(reactant, amount, OperationMode.Execute);
        }
        return 0;
    }

    /** 清除全部核废料，返回清除量（GUI“清除废料”按钮）。 */
    public int voidWaste() {
        if (this.getFuelContainer() instanceof FuelContainer fuel) {
            return fuel.voidWaste();
        }
        return 0;
    }

    /** 清除指定量的核废料（物品管道提取废物用），返回实际清除量。 */
    public int voidWaste(int amount) {
        if (amount > 0 && this.getFuelContainer() instanceof FuelContainer fuel) {
            return fuel.voidWaste(amount);
        }
        return 0;
    }

    /** 当前核废料的 Reactant 类型；无废物时返回 null。 */
    public Reactant getWasteReactant() {
        return this.getFuelContainer() instanceof FuelContainer fuel
                ? fuel.getWaste().orElse(null)
                : null;
    }

    @Override
    public int getFuelCapacity() {
        // GUI 使用 int，限制大容量以防负数显示。
        return (int) Math.min(this.getCapacity(), Integer.MAX_VALUE);
    }

    @Override
    public int getFuelAmount() {
        // 当前燃料量（从 ER 燃料容器读取）
        return this.getFuelContainer() instanceof FuelContainer fuel ? fuel.getFuelAmount() : 0;
    }

    @Override
    public int getWasteAmount() {
        // 当前核废料量
        return this.getFuelContainer() instanceof FuelContainer fuel ? fuel.getWasteAmount() : 0;
    }

    @Override
    public double getReactorTemperatureCelsius() {
        // 反应堆堆芯温度：ER 内部 getReactorHeatValue() 返回 DoubleSupplier（开尔文 → 摄氏度 = K - 273.15）
        return this.getReactorHeatValue().getAsDouble() - 273.15;
    }

    // 模拟"谎报"区：以下覆写让基类/逻辑层认为这是一个真实的多方块

    @Override
    public boolean isSimulator() {
        return true;
    }

    @Override
    public boolean isEmpty() {

        return false;
    }

    @Override
    public boolean isAssembled() {

        return true;
    }

    @Override
    public boolean isDisassembled() {
        return false;
    }

    @Override
    public Optional<BlockPos> getReferenceCoord() {

        return Optional.of(this._anchor);
    }

    @Override
    public CuboidBoundingBox getBoundingBox() {
        return this._cachedBoundingBox;
    }

    @Override
    public void forBoundingBoxCoordinates(BiConsumer<BlockPos, BlockPos> consumer) {
        // 几何计算使用模拟尺寸；私有边界框只覆盖实际方块。
        consumer.accept(this.getBoundingBox().getMin(), this.getBoundingBox().getMax());
    }

    @Override
    public void forBoundingBoxCoordinates(BiConsumer<BlockPos, BlockPos> consumer,
                                          Function<BlockPos, BlockPos> minRemapper,
                                          Function<BlockPos, BlockPos> maxRemapper) {
        consumer.accept(minRemapper.apply(this.getBoundingBox().getMin()),
                maxRemapper.apply(this.getBoundingBox().getMax()));
    }

    @Override
    public <T> T mapBoundingBoxCoordinates(BiFunction<BlockPos, BlockPos, T> mapper, T defaultValue) {
        return mapper.apply(this.getBoundingBox().getMin(), this.getBoundingBox().getMax());
    }

    @Override
    public <T> T mapBoundingBoxCoordinates(BiFunction<BlockPos, BlockPos, T> mapper, T defaultValue,
                                           Function<BlockPos, BlockPos> minRemapper,
                                           Function<BlockPos, BlockPos> maxRemapper) {
        return mapper.apply(minRemapper.apply(this.getBoundingBox().getMin()),
                maxRemapper.apply(this.getBoundingBox().getMax()));
    }

    @Override
    public int getPartsCount(IReactorPartType type) {

        if (type instanceof ReactorPartType partType) {
            return switch (partType) {
                case FuelRod -> this._fuelRods;
                case ControlRod -> this._controlRods;
                case ActivePowerTapFE, PassivePowerTapFE, ChargingPortFE -> this._powerTaps;
                default -> 0;
            };
        }
        return 0;
    }

    @Override
    public int getPartsCount() {
        // 上游容量公式使用模拟部件数。
        return this.partCount;
    }

    @Override
    public int getFuelRodsCount() {
        return this._fuelRods;
    }

    @Override
    public int getControlRodsCount() {
        return this._controlRods;
    }

    /** 虚拟控制棒没有实体部件，返回空值以避免上游索引空部件列表。 */
    @Override
    public Optional<it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.part.ReactorControlRodEntity> getControlRodByIndex(int index) {
        return Optional.empty();
    }

    @Override
    public int getPowerTapsCount() {
        return this._powerTaps;
    }

    @Override
    public int getReactorVolume() {
        // 模拟内部体积 = (size - 2)^3，与真实"内腔"一致
        return Math.max(1, this._sizeX - 2) * Math.max(1, this._sizeY - 2) * Math.max(1, this._sizeZ - 2);
    }

    @Override
    public IIrradiationSource getNextIrradiationSource() {
        // 提供模拟辐射源替代真实燃料棒
        return this._irradiationSource;
    }

    @Override
    public IRadiationModerator getModerator(BlockPos pos) {
        return NOOP_MODERATOR;
    }

    @Override
    public float getFuelToReactorHeatTransferCoefficient() {
        // 无实体燃料棒时，按每棒四个水平面与空气导热率计算传热。
        return 4.0f * IHeatEntity.CONDUCTIVITY_AIR * this._fuelRods;
    }

    @Override
    public List<BlockPos> getControlRodLocations() {
        return List.of();
    }
}
