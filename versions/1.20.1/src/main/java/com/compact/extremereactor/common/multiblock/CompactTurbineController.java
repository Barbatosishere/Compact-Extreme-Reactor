package com.compact.extremereactor.common.multiblock;

import com.compact.extremereactor.CompactExtremeReactor;
import com.compact.extremereactor.common.capability.BypassFluidHandler;
import it.zerono.mods.extremereactors.api.IMapping;
import it.zerono.mods.extremereactors.api.coolant.Coolant;
import it.zerono.mods.extremereactors.api.coolant.TransitionsRegistry;
import it.zerono.mods.extremereactors.api.coolant.Vapor;
import it.zerono.mods.extremereactors.api.turbine.CoilMaterial;
import it.zerono.mods.extremereactors.api.turbine.CoilMaterialRegistry;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidContainer;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.turbine.MultiblockTurbine;
import it.zerono.mods.extremereactors.gamecontent.multiblock.turbine.VentSetting;
import it.zerono.mods.extremereactors.gamecontent.multiblock.turbine.rotor.RotorComponentType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.turbine.variant.TurbineVariant;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.geometry.CuboidBoundingBox;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/** 复用 ER 涡轮模拟，以配置尺寸提供虚拟转子和线圈。 */
public class CompactTurbineController extends MultiblockTurbine implements ICompactController {
    private java.util.function.BooleanSupplier structureReady = () -> true;

    @Override
    public void setStructureReady(java.util.function.BooleanSupplier ready) {
        this.structureReady = ready;
    }

    /** 模拟线圈使用的真实线圈材料（ER 内置注册的 `forge:storage_blocks/gold`）。 */
    private static final TagKey<Block> COIL_TAG = TagKey.create(Registries.BLOCK,
            new ResourceLocation("forge", "storage_blocks/gold"));

    /** 线圈材料缺失时只警告一次。 */
    private static final java.util.concurrent.atomic.AtomicBoolean _hasWarnedCoilMissing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final BlockPos _anchor;
    private final int fluidCapacity;
    private final int partCount;
    private final int _sizeX;
    private final int _sizeY;
    private final int _sizeZ;
    private final int _coilRadius;

    /** 模拟边界框缓存（尺寸固定不变，避免每次 getBoundingBox() 创建新对象）。 */
    private final CuboidBoundingBox _cachedBoundingBox;

    /** 缓存的流体旁路处理器（Input/Output 各一个），避免每次 getFluidHandler() 都 new。 */
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

    public CompactTurbineController(Level level, BlockPos anchor,
                                    int coilRadius,
                                    int sizeX, int sizeY, int sizeZ) {
        this(level, anchor, coilRadius, sizeX, sizeY, sizeZ, 1.0);
    }

    public CompactTurbineController(Level level, BlockPos anchor,
                                    int coilRadius,
                                    int sizeX, int sizeY, int sizeZ, double scale) {
        super(level, TurbineVariant.Basic);
        this._anchor = anchor.immutable();
        this._sizeX = sizeX;
        this._sizeY = (int) Math.ceil((sizeY - 2) * scale) + 2;
        this._sizeZ = sizeZ;
        this._coilRadius = coilRadius;
        this._cachedBoundingBox = new CuboidBoundingBox(this._anchor, this._anchor.offset(sizeX - 1, this._sizeY - 1, sizeZ - 1));
        int exterior = sizeX * sizeY * sizeZ - (sizeX - 2) * (sizeY - 2) * (sizeZ - 2);
        long baselineFluid = Math.min((long) exterior * this.getVariant().getPartFluidCapacity(), this.getVariant().getMaxFluidCapacity());
        this.fluidCapacity = (int) Math.min(Integer.MAX_VALUE, Math.ceil(baselineFluid * scale));
        this.partCount = (int) Math.ceil((double) sizeX * sizeY * sizeZ * scale);
    }


    /** 初始化容量，并从模拟转子布局计算叶片、质量和线圈参数。 */
    public void simulateAssembly() {
        this.onMachineAssembled();
        ((FluidContainer) this.getFluidContainer()).setCapacity(this.fluidCapacity);
        // 打开能量缓冲的插入限制，使 TurbineLogic 的发电量可以写入（与反应堆控制器一致）
        this.getEnergyBuffer().setMaxInsert(WideAmount.MAX_VALUE);
        // 接合感应线圈：压缩涡轮机无控制棒，模拟内建稳压装置，始终发电
        this.setInductorEngaged(true);
        // ZeroCore 2.4.21+ 的 updateMultiblockEntity() 在调用 updateServer() 前会检查
        // hasChunksAt(_boundingBox)（内部私有字段），单方块模拟无部件时 _boundingBox 恒为
        // EMPTY(0,0,0)，在非出生点世界必然检查失败，导致 updateServer() 永不执行。
        // 覆写 recalculateCoords() 使 _boundingBox 覆盖 anchor 单方块（见下方方法）。
        this.recalculateCoords();
    }

    /** 覆写重算坐标：反射写入私有 {@code _boundingBox} 字段为 anchor 单方块。 */
    @Override
    public void recalculateCoords() {
        CompactControllerBounds.setAnchor(this, this._anchor);
    }

    /** 每个服务端游戏刻驱动一次涡轮机逻辑。 */
    public void tick() {
        if (!this.structureReady.getAsBoolean()) return;
        // 满仓暂停模拟，抽出能量后自动恢复。
        if (this.isEnergyBufferFull()) {
            return;
        }
        // 冷凝丢失补偿需要在 ER 模拟前后夹读容器，见 compensateCondensationLoss
        final FluidContainer container = (FluidContainer) this.getFluidContainer();
        final int steamBefore = container.getGasAmount();
        if (steamBefore <= 0 || this.getVentSetting() == VentSetting.VentAll) {
            this.updateMultiblockEntity();
            return;
        }
        final int waterBefore = container.getLiquidAmount();
        // 冷凝映射必须在模拟前捕获：bug 触发后蒸汽槽已空，无法再从容器解析蒸汽类型
        final IMapping<Vapor, Coolant> condensation = container.mapVapor(
                vapor -> TransitionsRegistry.get(vapor).orElse(null), null);

        this.updateMultiblockEntity();

        this.compensateCondensationLoss(container, steamBefore, waterBefore, condensation);
    }

    /** ER 2.0.84 / 2.4.9 在蒸汽槽恰好抽空时可能跳过冷凝注水。仅在蒸汽耗尽且水位未变时重放 condensate；VentAll 不补偿。 */
    private void compensateCondensationLoss(final FluidContainer container,
                                            final int steamBefore, final int waterBefore,
                                            final IMapping<Vapor, Coolant> condensation) {
        final int consumed = steamBefore - container.getGasAmount();
        if (consumed <= 0 || container.getGasAmount() > 0
                || container.getLiquidAmount() != waterBefore
                || VentSetting.VentAll == this.getVentSetting()
                || null == condensation) {
            return;
        }
        container.condensate(consumed, condensation);
    }

    /** 恢复存档后保持当前容量，并重新接合压缩涡轮的内建线圈。 */
    @Override
    public void syncDataFrom(CompoundTag tag, ISyncableEntity.SyncReason reason) {
        super.syncDataFrom(GeneratorEnergyPersistence.withCapacity(tag,
                this.getEnergyBuffer().getCapacity(EnergySystem.REFERENCE),
                amount -> amount.serializeTo(new CompoundTag())), reason);
        this.getEnergyBuffer().setMaxInsert(WideAmount.MAX_VALUE);
        this.setInductorEngaged(true);
    }


    /** 无实体 FluidPort 时直接输入蒸汽、输出冷凝水，停机仍可访问。 */
    @Override
    public Optional<IFluidHandler> getFluidHandler(IoDirection direction) {
        // 缓存 BypassFluidHandler：FluidContainer 引用稳定，缓存后 fill/drain 内部状态可复用
        if (direction == IoDirection.Input) {
            if (this._cachedInputHandler == null) {
                final FluidContainer container = (FluidContainer) this.getFluidContainer();
                // 涡轮输入必须走蒸汽槽。
                this._cachedInputHandler = new BypassFluidHandler(container, true, container::getCapacity,
                        this._fluidDirtyCallback, FluidType.Gas);
            }
            return Optional.of(this._cachedInputHandler);
        }
        if (this._cachedOutputHandler == null) {
            final FluidContainer container = (FluidContainer) this.getFluidContainer();
            this._cachedOutputHandler = new BypassFluidHandler(container, false, container::getCapacity,
                        this._fluidDirtyCallback, FluidType.Liquid);
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

    /** 满仓暂停时显示 0，否则使用上游每 tick 发电量。 */
    @Override
    public double getEnergyGeneratedLastTick() {
        return !this.structureReady.getAsBoolean() || this.isEnergyBufferFull() ? 0.0d : super.getEnergyGeneratedLastTick();
    }

    @Override
    public double getRotorAngularSpeed() {
        return super.getRotorSpeed();
    }

    @Override
    public double getMaxRotorAngularSpeed() {
        return super.getMaxRotorSpeed();
    }


    @Override
    public boolean isInductorEngaged() {
        // TurbineLogic.update() 同时检查 TurbineData 字段和接口方法，两处都返回 true 确保接合
        return true;
    }

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
    public int getPartsCount() {
        // 上游容量公式使用模拟部件数。
        return this.partCount;
    }

    @Override
    public int getRotorBladesCount() {
        // 模拟布局中的叶片总数：每层 4 个 x 内部高度层数
        return this.getRotorLayers() * 4;
    }

    /** 内部转轴高度（层数）。 */
    private int getRotorLayers() {
        return Math.max(1, this._sizeY - 2);
    }

    @Override
    public RotorComponentType getRotorComponentTypeAt(BlockPos pos) {
        // 依据模拟布局判定位置类型：转轴/叶片/线圈/忽略
        final CuboidBoundingBox bb = this.getBoundingBox();

        // 排除 Y 轴面（顶部和底部墙壁），这些位置是涡轮机外壳，不属于转子/线圈区域
        if (pos.getY() == bb.getMinY() || pos.getY() == bb.getMaxY()) {
            return RotorComponentType.Ignore;
        }

        final int cx = bb.getMinX() + (bb.getMaxX() - bb.getMinX()) / 2;
        final int cz = bb.getMinZ() + (bb.getMaxZ() - bb.getMinZ()) / 2;

        if (pos.getX() == cx && pos.getZ() == cz) {
            return RotorComponentType.Shaft;
        }

        final int dx = Math.abs(pos.getX() - cx);
        final int dz = Math.abs(pos.getZ() - cz);

        // 紧邻转轴的水平十字 = 叶片
        if ((dx == 1 && dz == 0) || (dx == 0 && dz == 1)) {
            return RotorComponentType.Blade;
        }

        // 叶片外围、线圈半径内的方块 = 候选线圈
        if (dx <= this._coilRadius && dz <= this._coilRadius && dx + dz > 0) {
            return RotorComponentType.CandidateCoil;
        }

        return RotorComponentType.Ignore;
    }

    @Override
    public Optional<CoilMaterial> getCoilBlock(BlockPos pos) {
        // 在候选线圈区域返回真实线圈材料（金线圈），供 TurbineData 计算感应参数
        final Optional<CoilMaterial> result = CoilMaterialRegistry.get(COIL_TAG);
        if (result.isEmpty() && _hasWarnedCoilMissing.compareAndSet(false, true)) {
            // 去重：getCoilBlock 每次扫描都被调用，同一缺失原因不应刷屏
            // CAS 确保多线程并发时也只打一次 warn
            CompactExtremeReactor.LOGGER.warn("线圈材料未找到: tag={}, pos={} (后续同类错误将静默)",
                    COIL_TAG.location(), pos);
        }
        return result;
    }
}
