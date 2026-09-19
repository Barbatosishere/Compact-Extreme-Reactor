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
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 压缩涡轮机控制器：在单个方块内完整模拟一个 Extreme Reactors 涡轮机多方块。
 *
 * 实现原理与 {@link CompactReactorController} 相同：继承 {@link MultiblockTurbine}
 * 复用其全部涡轮逻辑，只"谎报"多方块形状数据。
 *
 * 转子/线圈模拟布局（以压缩方块为中心的虚构内腔，Y 轴为转轴方向）：
 *   - 中心竖列             → 转轴 (Shaft)
 *   - 每层转轴四周 4 个方块 → 叶片 (Blade)
 *   - 叶片外围半径内方块    → 感应线圈 (CandidateCoil)
 *   - 其余位置             → 忽略 (Ignore)
 * TurbineData.update() 会扫描该布局并计算叶片面积、转子质量、线圈尺寸等参数，
 * 从而驱动 TurbineLogic 按真实公式发电。
 */
public class CompactTurbineController extends MultiblockTurbine implements ICompactController {

    /** 模拟线圈使用的真实线圈材料（ER2 1.21.x 以 `c:` 惯例命名空间注册 `c:storage_blocks/gold`）。 */
    private static final TagKey<Block> COIL_TAG = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath("c", "storage_blocks/gold"));

    /**
     * 线圈材料缺失警告已发出标志：getCoilBlock 每次扫描转子布局都被调用（每 tick 数十次），
     * 同一缺失原因不应刷屏。true 后该警告静默（玩家日志只看到第一条）。
     * AtomicBoolean 防御未来 ER 引入异步调用的并发 warn 竞态。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean _hasWarnedCoilMissing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

    private final BlockPos _anchor;
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
        super(level, TurbineVariant.Basic);
        this._anchor = anchor.immutable();
        this._sizeX = sizeX;
        this._sizeY = sizeY;
        this._sizeZ = sizeZ;
        this._cachedBoundingBox = new CuboidBoundingBox(this._anchor, this._anchor.offset(sizeX - 1, sizeY - 1, sizeZ - 1));
        this._coilRadius = coilRadius;
    }

    // ------------------------------------------------------------------
    // 模拟装配与 tick
    // ------------------------------------------------------------------

    /**
     * 模拟多方块"装配"：触发真实装配回调，初始化能量/流体容量，并让
     * TurbineData 扫描模拟转子布局计算出叶片面积/转子质量/线圈参数。
     */
    public void simulateAssembly() {
        this.onMachineAssembled();
        // 打开能量缓冲的插入限制，使 TurbineLogic 的发电量可以写入（与反应堆控制器一致）
        this.getEnergyBuffer().setMaxInsert(WideAmount.MAX_VALUE);
        // 接合感应线圈：压缩涡轮机无控制棒，模拟内建稳压装置，始终发电
        this.setInductorEngaged(true);
        // ZeroCore 2.4.21+ 的 updateMultiblockEntity() 会检查 hasChunksAt(_boundingBox)，
        // 单方块模拟无部件时 _boundingBox 恒为 EMPTY，需强制覆盖为 anchor 单方块。
        this.recalculateCoords();
    }

    /**
     * 覆写重算坐标：强制使基类私有字段 {@code _boundingBox} 覆盖压缩方块自身，
     * 确保 ZeroCore 2.4.21+ 的 hasChunksAt(_boundingBox) 检查通过。
     */
    @Override
    public void recalculateCoords() {
        try {
            BOUNDING_BOX_FIELD.set(this, new CuboidBoundingBox(this._anchor, this._anchor));
        } catch (ReflectiveOperationException e) {
            CompactExtremeReactor.LOGGER.error("无法设置涡轮机 _boundingBox @{}", this._anchor, e);
        }
    }

    /** 每个服务端游戏刻驱动一次涡轮机逻辑。 */
    public void tick() {
        // FE 缓存已满：跳过进汽/冷凝/发电，抽出能量后自动恢复。
        // 不改 isMachineActive()：涡轮机没有 GUI 开关，玩家开关状态必须保留。
        if (this.isEnergyBufferFull()) {
            return;
        }
        // 冷凝丢失补偿需要在 ER 模拟前后夹读容器，见 compensateCondensationLoss
        final FluidContainer container = (FluidContainer) this.getFluidContainer();
        final int steamBefore = container.getGasAmount();
        final int waterBefore = container.getLiquidAmount();
        // 冷凝映射必须在模拟前捕获：bug 触发后蒸汽槽已空，无法再从容器解析蒸汽类型
        final IMapping<Vapor, Coolant> condensation = container.getVapor()
                .flatMap(TransitionsRegistry::get)
                .orElse(null);

        this.updateMultiblockEntity();

        this.compensateCondensationLoss(container, steamBefore, waterBefore, condensation);
    }

    /**
     * 补偿上游 ER2 的冷凝丢失缺陷：{@code FluidContainer.onCondensation()} 先从蒸汽槽
     * 扣除进汽量、再经 {@code mapGasAmount} 驱动注水，而后者在蒸汽槽被该 tick 进汽恰好
     * 抽干时对空栈短路返回默认值，注水 lambda 被整体跳过，返回值又被 TurbineLogic
     * pop 丢弃——该 tick 消耗的蒸汽不产生任何冷凝水。进汽量小于单 tick 上限的涓流工况
     * 每 tick 都会触发（冷凝水近乎全损）；整批蒸汽一次耗尽时损失最后一个进汽批次。
     * ER2 1.20.1（2.0.84）与 1.21.1（2.4.9）存在同样缺陷，原版多方块涡轮同样继承。
     *
     * <p>触发特征可在模拟前后精确判定：蒸汽有消耗、蒸汽槽已排空、水位未变——注水
     * lambda 要么整批执行、要么完全跳过，不存在部分注水；夹读区间内水位只增不减，
     * 水位未变即整批未注。
     * 命中特征时调用 ER 公开的 {@link FluidContainer#condensate(int, IMapping)} 重放被
     * 跳过的注水——冷凝比例、冷却剂流体解析、容量钳制与"液罐冷却剂不匹配时拒绝"等
     * 语义全部复用上游实现；水箱满时注不进去的部分按 VentOverflow 排溢语义丢弃，
     * 与上游行为一致。VentAll（全排）模式下上游本就不存冷凝水，不补偿。</p>
     */
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

    /**
     * 覆写 NBT 恢复：强制接合感应线圈。
     * 基类 {@code MultiblockTurbine.syncDataFrom()} 会从 NBT 恢复 {@code TurbineData}，
     * 包括 {@code _inductorEngaged} 字段。如果存档中该字段为 false
     *（旧存档/外部操作/未来 ER 版本改变默认值），涡轮机将停止发电。
     * 压缩涡轮机始终使用内建稳压装置，必须确保线圈接合。
     */
    @Override
    public void syncDataFrom(CompoundTag tag, HolderLookup.Provider registries, ISyncableEntity.SyncReason reason) {
        super.syncDataFrom(tag, registries, reason);
        this.getEnergyBuffer().setMaxInsert(WideAmount.MAX_VALUE);
        this.setInductorEngaged(true);
    }

    // ------------------------------------------------------------------
    // 流体端口旁路：绕开 _accessGovernor 检查（无 FluidPort 部件时拒绝 fill/drain）
    // ------------------------------------------------------------------

    /**
     * 压缩涡轮机无真实 FluidPort 部件，ER 基类 {@code getFluidHandler(IoDirection)}
     * 经过 {@code IFluidContainerAccess.getAllowedActionFor()} 检查，部件为空时
     * 返回的 wrapper 行为受限（无法 fill/drain）。直接返回 {@link BypassFluidHandler}
     * 以 Input/Output 直读直写 FluidContainer，使外部管道能灌入蒸汽、排出冷凝水。
     *
     * 机器关闭时 fill/drain 仍允许（与真实 ER FluidPort 行为一致）。
     */
    @Override
    public Optional<IFluidHandler> getFluidHandler(IoDirection direction) {
        // 缓存 BypassFluidHandler：FluidContainer 引用稳定，缓存后 fill/drain 内部状态可复用
        if (direction == IoDirection.Input) {
            if (this._cachedInputHandler == null) {
                final FluidContainer container = (FluidContainer) this.getFluidContainer();
                // 涡轮机语义：进蒸汽（Gas 槽）、出冷凝水（优先 Liquid 槽）——BypassFluidHandler 默认
                // fill 走 insertLiquid（反应堆进水语义），蒸汽是 Gas 必须显式指定槽位，
                // 否则 fill 恒为 0（2026-08-28 运行时实测复现）
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

    /**
     * 最近一游戏刻发电量（FE），供 GUI / Jade 显示。
     * 直接代理到 ER 的 {@link MultiblockTurbine#getEnergyGeneratedLastTick()}：
     * TurbineLogic 每 tick 在 generateEnergy(...) 末尾写入，ICompactController
     * 默认返回 0 会导致涡轮机 GUI Power 字段恒为 0。继承后该字段正常返回
     * 当前转子转速下的实际发电量。
     */
    @Override
    public double getEnergyGeneratedLastTick() {
        return this.isEnergyBufferFull() ? 0.0d : super.getEnergyGeneratedLastTick();
    }

    @Override
    public double getRotorAngularSpeed() {
        // 涡轮机转子实际转速（弧度/秒）——ER 基类同名方法直接返回
        return super.getRotorSpeed();
    }

    @Override
    public double getMaxRotorAngularSpeed() {
        // 涡轮机转子最大安全转速
        return super.getMaxRotorSpeed();
    }

    // ------------------------------------------------------------------
    // 模拟"谎报"区
    // ------------------------------------------------------------------

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
        // 能量缓冲容量 = 每部件容量 × 部件总数 × 倍率（onMachineAssembled 使用无参版本），
        // 基类返回已连接部件数（恒 0），必须覆写为模拟结构方块数
        return this._sizeX * this._sizeY * this._sizeZ;
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
