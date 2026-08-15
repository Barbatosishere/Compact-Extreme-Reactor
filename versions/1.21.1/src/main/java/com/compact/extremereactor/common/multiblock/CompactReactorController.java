package com.compact.extremereactor.common.multiblock;

import it.zerono.mods.extremereactors.api.reactor.Reactant;
import it.zerono.mods.extremereactors.api.reactor.radiation.IRadiationModerator;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.FuelContainer;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.IIrradiationSource;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.IReactorPartType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.MultiblockReactor;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.ReactorPartType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.variant.ReactorVariant;
import it.zerono.mods.zerocore.lib.data.geometry.CuboidBoundingBox;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 压缩反应堆控制器：在单个方块内完整模拟一个 Extreme Reactors 反应堆多方块。
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
    // NBT 持久化：ER 存档不含自定义控制棒比例，必须覆写补充保存
    // ------------------------------------------------------------------

    @Override
    public CompoundTag syncDataTo(CompoundTag tag, HolderLookup.Provider registries, ISyncableEntity.SyncReason reason) {
        super.syncDataTo(tag, registries, reason);
        tag.putByte("ControlRodInsertionRatio", this._controlRodInsertionRatio);
        return tag;
    }

    @Override
    public void syncDataFrom(CompoundTag tag, HolderLookup.Provider registries, ISyncableEntity.SyncReason reason) {
        super.syncDataFrom(tag, registries, reason);
        if (tag.contains("ControlRodInsertionRatio", Tag.TAG_BYTE)) {
            this._controlRodInsertionRatio = tag.getByte("ControlRodInsertionRatio");
        }
    }

    /** 向燃料容器注入燃料（如黄钇矿铤），返回实际注入量。 */
    public int insertFuel(Reactant reactant, int amount, OperationMode mode) {
        if (this.getFuelContainer() instanceof FuelContainer fuel) {
            return fuel.insertFuel(reactant, amount, mode);
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

    @Override
    public int getFuelCapacity() {
        // 燃料总容量由模拟装配时的燃料棒数量决定
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
    public int getFuelRodsCount() {
        return this._fuelRods;
    }

    @Override
    public int getControlRodsCount() {
        return this._controlRods;
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
    public List<BlockPos> getControlRodLocations() {
        return List.of();
    }
}
