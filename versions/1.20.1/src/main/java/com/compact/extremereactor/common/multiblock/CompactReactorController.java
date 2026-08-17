package com.compact.extremereactor.common.multiblock;

import it.zerono.mods.extremereactors.api.reactor.IHeatEntity;
import it.zerono.mods.extremereactors.api.reactor.Reactant;
import it.zerono.mods.extremereactors.api.reactor.radiation.IRadiationModerator;
import it.zerono.mods.extremereactors.config.Config;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.FuelContainer;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.IIrradiationSource;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.IReactorPartType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.MultiblockReactor;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.OperationalMode;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.ReactorPartType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.variant.ReactorVariant;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.geometry.CuboidBoundingBox;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

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

    /** 模拟控制棒插入比例（0-100），由 GUI 调节。 */
    private byte _controlRodInsertionRatio = 50;

    private final IIrradiationSource _irradiationSource;

    public CompactReactorController(Level level, BlockPos anchor,
                                    int fuelRods, int controlRods, int powerTaps,
                                    int sizeX, int sizeY, int sizeZ) {
        super(level, ReactorVariant.Basic);
        this._anchor = anchor.immutable();
        this._fuelRods = fuelRods;
        this._controlRods = controlRods;
        this._powerTaps = powerTaps;
        this._sizeX = sizeX;
        this._sizeY = sizeY;
        this._sizeZ = sizeZ;
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
        this._controlRodInsertionRatio = (byte) Math.max(0, Math.min(100, ratio));
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
        if (this.isMachineActive()) {
            final double reactorHeat = this.getReactorHeat().getAsDouble();
            final double dT = reactorHeat - IHeatEntity.AMBIENT_HEAT;
            if (dT > 0.01d) {
                final double fe = dT * this.getReactorToCoolantSystemHeatTransferCoefficient()
                        * PASSIVE_COOLING_TRANSFER_EFFICIENCY * PASSIVE_COOLING_POWER_EFFICIENCY
                        * Config.COMMON.general.powerProductionMultiplier.get()
                        * Config.COMMON.reactor.reactorPowerProductionMultiplier.get()
                        * this.getVariant().getEnergyGenerationEfficiency();
                if (fe > 0.0d) {
                    this.insertEnergy(EnergySystem.ForgeEnergy, WideAmount.from(fe), OperationMode.Execute);
                }
            }
        }
        return super.updateServer();
    }

    // ------------------------------------------------------------------
    // NBT 持久化：ER 存档不含自定义控制棒比例，必须覆写补充保存
    // ------------------------------------------------------------------

    @Override
    public CompoundTag syncDataTo(CompoundTag tag, ISyncableEntity.SyncReason reason) {
        super.syncDataTo(tag, reason);
        tag.putByte("ControlRodInsertionRatio", this._controlRodInsertionRatio);
        return tag;
    }

    @Override
    public void syncDataFrom(CompoundTag tag, ISyncableEntity.SyncReason reason) {
        super.syncDataFrom(tag, reason);
        if (tag.contains("ControlRodInsertionRatio", Tag.TAG_BYTE)) {
            this._controlRodInsertionRatio = tag.getByte("ControlRodInsertionRatio");
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

    /** 清除全部核废料，返回清除量（GUI"清除废料"按钮）。 */
    public int voidWaste() {
        if (this.getFuelContainer() instanceof FuelContainer fuel) {
            return fuel.voidWaste();
        }
        return 0;
    }

    @Override
    public int getFuelCapacity() {
        // 燃料容器容量：MultiblockReactor 的无参 getCapacity() 即燃料容量
        //（getCapacity(EnergySystem) 是能量缓冲容量，与燃料容量是两回事）
        return this.getCapacity();
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
        return new CuboidBoundingBox(this._anchor, this._anchor.offset(this._sizeX - 1, this._sizeY - 1, this._sizeZ - 1));
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
     * 部件链表（IndexOutOfBoundsException）。返回空 Optional 与
     * "该部件不存在"的 API 语义一致，createFuelRodsLayout 会安全走
     * orElse(Direction.UP) 分支。
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
