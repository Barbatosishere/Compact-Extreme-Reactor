package com.compact.extremereactor.common.multiblock;

import com.compact.extremereactor.CompactExtremeReactor;
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

/**
 * 压缩极限反应堆控制器：在单个方块内完整模拟一个 Extreme Reactors 反应堆多方块。
 *
 * 实现原理（重要）：
 * 1. 直接继承 ER 的 {@link MultiblockReactor}，复用其全部反应堆逻辑（辐射、
 *    燃料消耗、热量传递、功率产出），只"谎报"多方块的形状数据：
 *      - isEmpty()/isAssembled() 固定为 false/true，绕过真实部件的装配检查；
 *      - getReferenceCoord() 指向压缩方块自身，使 markReferenceCoordForUpdate()
 *        与 ZeroCore 网络同步机制正常工作；
 *      - getPartsCount()/getFuelRodsCount()/getBoundingBox()/getReactorVolume()
 *        返回配置的模拟值，决定燃料容量、能量缓冲与流体容量；
 *      - getNextIrradiationSource() 返回模拟辐射源，替代真实燃料棒。
 * 2. {@link #simulateAssembly()} 调用受保护的 onMachineAssembled()（真实装配
 *    回调），其内部的私有初始化方法会使用上面覆写的模拟值完成容量设置。
 * 3. 每个游戏刻调用 {@link #tick()}，即 ZeroCore 的 updateMultiblockEntity()，
 *    它会驱动 ReactorLogic.update() 完成整个反应堆模拟。
 *
 * 注意：updateMultiblockEntity() 在数据变化时会以内部 bounding box 标记区块
 * 需要保存；单方块模拟下该框为空（0,0,0），此标记无害——TileEntity 自身会
 * 通过 setChanged() 保证保存。
 */
public class CompactReactorController extends MultiblockReactor implements ICompactController {

    /** 无操作辐射调节器：模拟反应堆没有真实外壳方块需要调节辐射。 */
    private static final IRadiationModerator NOOP_MODERATOR = (data, packet) -> {
    };

    /** 控制棒插入比例 NBT key（用于存档持久化自定义字段）。加 {@code cer:} 前缀避免与 ER 内部 key 冲突。 */
    public static final String NBT_KEY_CONTROL_ROD_RATIO = "cer:controlRodInsertionRatio";

    /**
     * 旧版本（beta16 之前）使用的无前缀 NBT key。读档时同时识别旧/新 key，
     * 写时只用新 key。玩家升级 mod 后旧存档可平滑迁移，无需 NBT 编辑。
     */
    private static final String LEGACY_NBT_KEY_CONTROL_ROD_RATIO = "ControlRodInsertionRatio";

    /**
     * 基类私有字段 {@code _boundingBox} 的反射引用（静态缓存，避免每次
     * recalculateCoords() 都执行 getDeclaredField）。ZeroCore 升级若改名会在此
     * 抛异常并中止 mod 加载（fail-fast），比运行时静默失败更易发现。
     */
    private static final java.lang.reflect.Field BOUNDING_BOX_FIELD;

    static {
        try {
            BOUNDING_BOX_FIELD = it.zerono.mods.zerocore.lib.multiblock.AbstractMultiblockController.class
                    .getDeclaredField("_boundingBox");
            BOUNDING_BOX_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("无法找到 AbstractMultiblockController._boundingBox 字段（ZeroCore 升级？）", e);
        }
    }

    /** ReactorLogic 被动分支常量（被动冷却的传热/输出效率）。 */
    private static final double PASSIVE_COOLING_TRANSFER_EFFICIENCY = 0.2d;
    private static final double PASSIVE_COOLING_POWER_EFFICIENCY = 0.5d;

    private final BlockPos _anchor;
    private final int _fuelRods;
    private final int _controlRods;
    private final int _powerTaps;
    private final int _sizeX;
    private final int _sizeY;
    private final int _sizeZ;

    /** 模拟边界框缓存（尺寸固定不变，避免每次 getBoundingBox() 创建新对象）。 */
    private final CuboidBoundingBox _cachedBoundingBox;

    /** 缓存的流体旁路处理器（Input/Output 各一个），避免每次 getFluidHandler() 都 new。 */
    /** 缓存的流体旁路处理器（Input/Output 各一个），构造时传入流体脏标记回调（见下）。 */
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
        // 必须用 Reinforced variant：ER2 的 Basic variant 未设置流体参数
        // （partFluidCapacity=0、maxFluidCapacity=0、vaporGenerationEfficiency=0），
        // resizeFluidContainer() 会算出流体容量 0 → 水无法注入、汽化量恒为 0，
        // 即 Basic 是纯被动堆。Reinforced（1000 mB/外壳块，汽化效率 0.85）才有主动冷却。
        super(level, ReactorVariant.Reinforced);
        this._anchor = anchor.immutable();
        this._fuelRods = fuelRods;
        this._controlRods = controlRods;
        this._powerTaps = powerTaps;
        this._sizeX = sizeX;
        this._sizeY = sizeY;
        this._sizeZ = sizeZ;
        this._cachedBoundingBox = new CuboidBoundingBox(this._anchor, this._anchor.offset(sizeX - 1, sizeY - 1, sizeZ - 1));
        this._irradiationSource = new SimulatedIrradiationSource(() -> this._controlRodInsertionRatio, this._anchor);
    }

    // ------------------------------------------------------------------
    // 模拟装配与 tick
    // ------------------------------------------------------------------

    /** 模拟多方块"装配"：触发真实装配回调，初始化能量/燃料/流体容量。 */
    public void simulateAssembly() {
        this.onMachineAssembled();
        // ER2 发电机缓冲默认 maxInsert=0（原生逻辑只提取不插入），
        // 打开插入限制，使 updateServer() 的被动等效 FE 补偿可以写入
        this.getEnergyBuffer().setMaxInsert(WideAmount.MAX_VALUE);
        // ZeroCore 2.4.21+ 的 updateMultiblockEntity() 在调用 updateServer() 前会检查
        // hasChunksAt(_boundingBox)（内部私有字段），而单方块模拟无部件，_boundingBox
        // 恒为 EMPTY(0,0,0)，在非出生点世界必然检查失败，导致 updateServer() 永不执行。
        // 覆写 recalculateCoords() 使 _boundingBox 覆盖 anchor 单方块（见下方方法）。
        this.recalculateCoords();
    }

    // ------------------------------------------------------------------
    // 流体端口旁路：绕开 _accessGovernor 与 isActive 前置检查
    // ------------------------------------------------------------------

    /**
     * 压缩反应堆无真实 FluidPort 部件。ER 基类 {@code getFluidHandler(IoDirection)}
     * 会经过 {@code IFluidContainerAccess.getAllowedActionFor()} 检查与（反应堆特有的）
     * {@code getOperationalMode().isActive()} 前置，无部件时返回受限甚至空的 handler。
     * 这里直接返回 {@link BypassFluidHandler}，以 Input/Output 方向直读直写 FluidContainer，
     * 外部管道 fill/drain 立即生效——这是水→蒸汽贯通的唯一修复点。
     *
     * 机器关闭时 fill/drain 仍允许（与真实 ER FluidPort 行为一致——水可提前灌入等待启动）；
     * 关闭状态下灌入的水在启动时由 ReactorLogic 自然蒸发。
     */
    @Override
    public Optional<IFluidHandler> getFluidHandler(IoDirection direction) {
        // 缓存 BypassFluidHandler：FluidContainer 引用稳定（基类持有的字段在 simulateAssembly 后不变），
        // 缓存后 fill/drain 状态（_cachedLiquid/_cachedGas）也可复用，避免每次 new。
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

    /**
     * 覆写重算坐标：强制使基类私有字段 {@code _boundingBox} 覆盖压缩方块自身。
     * 基类实现仅在 {@code _needBuildingBoxRebuild} 为 true 时重建，而该标记只在
     * 部件变动时置位（单方块模拟无部件，恒为 false）。这里直接反射写入私有字段
     * 为 anchor 单方块（不走 buildBoundingBox()：单方块模拟无部件可遍历），
     * 确保 hasChunksAt 检查通过。
     */
    @Override
    public void recalculateCoords() {
        try {
            BOUNDING_BOX_FIELD.set(this, new CuboidBoundingBox(this._anchor, this._anchor));
        } catch (ReflectiveOperationException e) {
            CompactExtremeReactor.LOGGER.error("无法设置反应堆 _boundingBox @{}", this._anchor, e);
        }
    }

    /** 每个服务端游戏刻驱动一次反应堆逻辑。 */
    public void tick() {
        this.updateMultiblockEntity();
    }

    /** 获取模拟控制棒插入比例（0-100）。 */
    public byte getControlRodInsertionRatio() {
        return this._controlRodInsertionRatio;
    }

    /** 设置模拟控制棒插入比例（0-100），与真实控制棒语义一致。 */
    public void setControlRodInsertionRatio(int ratio) {
        this._controlRodInsertionRatio = (byte) Math.clamp(ratio, 0, 100);
    }

    // ------------------------------------------------------------------
    // 主动冷却模式 + 被动等效 FE 补偿
    // ------------------------------------------------------------------

    /**
     * 压缩极限反应堆恒为 Active 模式：真实反应堆按是否挂载 FluidPort 部件决定模式，
     * 压缩机无部件会被判为 Passive，导致水/蒸汽能力完全不工作（容量 0、处理器为空）。
     * 固定为 Active 后：流体容器按模拟体积计算容量、水进/蒸汽出正常、热量原生转化为蒸汽。
     */
    @Override
    public OperationalMode getOperationalMode() {
        return OperationalMode.Active;
    }

    /**
     * Active 模式下 ReactorLogic 只产蒸汽不产 FE；此处按被动分支同款公式
     * （温差 × 传热系数 × 0.2 × 0.5 × 配置倍率 × 变体效率）向能量缓冲补记 FE，
     * 使压缩机同时输出蒸汽与 FE（同一热量双产出，README 已注明）。
     */
    @Override
    protected boolean updateServer() {
        // 非激活状态时：不消耗燃料/不产热/不产蒸汽/不发电，
        // 但残余堆温继续向环境温度(20°C)自然消散（复刻 ER performPassiveHeatLoss）
        if (!this.isMachineActive()) {
            this._feGeneratedLastTick = 0;
            this.performPassiveHeatLoss();
            return false;
        }
        // 激活时：先按被动分支同款公式补记等效 FE（温差 × 传热 × 0.2 × 0.5 × 倍率 × 变体效率），
        // 再让 super.updateServer() 驱动 ReactorLogic 正常运行（产蒸汽 + 辐射 + 热量）
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
        return this._feGeneratedLastTick;
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

    // ------------------------------------------------------------------
    // NBT 持久化：ER 存档不含自定义控制棒比例，必须覆写补充保存
    // ------------------------------------------------------------------

    @Override
    public CompoundTag syncDataTo(CompoundTag tag, HolderLookup.Provider registries, ISyncableEntity.SyncReason reason) {
        super.syncDataTo(tag, registries, reason);
        tag.putByte(NBT_KEY_CONTROL_ROD_RATIO, this._controlRodInsertionRatio);
        return tag;
    }

    @Override
    public void syncDataFrom(CompoundTag tag, HolderLookup.Provider registries, ISyncableEntity.SyncReason reason) {
        super.syncDataFrom(tag, registries, reason);
        // 优先读新 key（cer: 前缀），兼容旧 beta16 之前的无前缀存档
        // 防御：恶意 NBT 可能写入 -50（byte 范围 -128~127），必须 clamp 到 [0, 100]
        // 否则 controlRodFactor = (100-(-50))/100 = 1.5，反应堆产生 1.5x 能量，破坏平衡
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
        // 燃料总容量由模拟装配时的燃料棒数量决定
        // 截断到 int 上限，防止 GUI 槽位（DATA_FUEL_CAPACITY 是 int）在极端配置下
        // （如 fuelRods=200 + sizeX*Y*Z=32³）容量超过 2^31-1 导致负数显示
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

    // ------------------------------------------------------------------
    // 模拟"谎报"区：以下覆写让基类/逻辑层认为这是一个真实的多方块
    // ------------------------------------------------------------------

    @Override
    public boolean isSimulator() {
        return true;
    }

    @Override
    public boolean isEmpty() {
        // 永远"非空"，避免 ZeroCore 将本控制器当作死控制器处理
        return false;
    }

    @Override
    public boolean isAssembled() {
        // 永远"已装配"，保证 updateMultiblockEntity() 会执行游戏逻辑
        return true;
    }

    @Override
    public boolean isDisassembled() {
        return false;
    }

    @Override
    public Optional<BlockPos> getReferenceCoord() {
        // 引用坐标 = 压缩方块自身，让 ZeroCore 的更新/网络同步找到我们
        return Optional.of(this._anchor);
    }

    @Override
    public CuboidBoundingBox getBoundingBox() {
        return this._cachedBoundingBox;
    }

    @Override
    public void forBoundingBoxCoordinates(BiConsumer<BlockPos, BlockPos> consumer) {
        // 基类直接读取私有 _boundingBox（空），必须覆写为模拟框
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
        // 向基类"谎报"部件数量：燃料棒/控制棒/功率接口由配置决定
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
        // 能量缓冲容量 = 每部件容量 × 部件总数 × 倍率（onMachineAssembled 使用无参版本），
        // 基类返回已连接部件数（恒 0），必须覆写为模拟结构方块数
        return this._sizeX * this._sizeY * this._sizeZ;
    }

    @Override
    public int getFuelRodsCount() {
        return this._fuelRods;
    }

    @Override
    public int getControlRodsCount() {
        return this._controlRods;
    }

    /**
     * 压缩机器没有真实控制棒部件。基类的边界检查使用被覆写的
     * {@link #getControlRodsCount()}（返回模拟值），通过检查后会直接索引空的
     * 部件链表，在 ER2 2.4.21+ 的 onMachineAssembled → createFuelRodsLayout
     * 路径上必然抛出 IndexOutOfBoundsException（2.4.28 实测崩溃）。
     * 返回空 Optional 与"该部件不存在"的 API 语义一致，调用方均安全处理。
     */
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
        // 基类系数 = 真实燃料棒导热率之和（压缩机无部件 → 0，燃料热量无法传入堆体，
        // 堆温恒为环境温度）。模拟"燃料棒立于反应堆内腔空气中"的真实近似：
        // 每棒 4 个水平暴露面 × 空气导热率 × 模拟棒数
        return 4.0f * IHeatEntity.CONDUCTIVITY_AIR * this._fuelRods;
    }

    @Override
    public List<BlockPos> getControlRodLocations() {
        return List.of();
    }
}
