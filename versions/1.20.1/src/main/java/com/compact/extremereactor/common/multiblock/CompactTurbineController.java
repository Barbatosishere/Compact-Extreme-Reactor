package com.compact.extremereactor.common.multiblock;

import it.zerono.mods.extremereactors.api.turbine.CoilMaterial;
import it.zerono.mods.extremereactors.api.turbine.CoilMaterialRegistry;
import it.zerono.mods.extremereactors.gamecontent.multiblock.turbine.MultiblockTurbine;
import it.zerono.mods.extremereactors.gamecontent.multiblock.turbine.rotor.RotorComponentType;
import it.zerono.mods.extremereactors.gamecontent.multiblock.turbine.variant.TurbineVariant;
import it.zerono.mods.zerocore.lib.data.geometry.CuboidBoundingBox;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

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

    /** 模拟线圈使用的真实线圈材料（ER 内置注册的 `forge:storage_blocks/gold`）。 */
    private static final TagKey<Block> COIL_TAG = TagKey.create(Registries.BLOCK,
            new ResourceLocation("forge", "storage_blocks/gold"));

    private final BlockPos _anchor;
    private final int _sizeX;
    private final int _sizeY;
    private final int _sizeZ;
    private final int _coilRadius;

    public CompactTurbineController(Level level, BlockPos anchor,
                                    int coilRadius,
                                    int sizeX, int sizeY, int sizeZ) {
        super(level, TurbineVariant.Basic);
        this._anchor = anchor.immutable();
        this._sizeX = sizeX;
        this._sizeY = sizeY;
        this._sizeZ = sizeZ;
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
    }

    /** 每个服务端游戏刻驱动一次涡轮机逻辑。 */
    public void tick() {
        this.updateMultiblockEntity();
    }

    // ------------------------------------------------------------------
    // 模拟"谎报"区
    // ------------------------------------------------------------------

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
        return new CuboidBoundingBox(this._anchor, this._anchor.offset(this._sizeX - 1, this._sizeY - 1, this._sizeZ - 1));
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
        final int cx = (bb.getMinX() + bb.getMaxX()) / 2;
        final int cz = (bb.getMinZ() + bb.getMaxZ()) / 2;

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
        return CoilMaterialRegistry.get(COIL_TAG);
    }
}
