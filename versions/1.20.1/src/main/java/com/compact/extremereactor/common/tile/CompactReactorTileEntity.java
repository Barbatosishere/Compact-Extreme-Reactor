package com.compact.extremereactor.common.tile;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.capability.MachineFluidHandler;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.EmptyFluidHandler;

/**
 * 压缩极限反应堆 TileEntity：持有 {@link CompactReactorController} 并驱动其模拟。
 *
 * 一个方块 = 一个完整的反应堆多方块：
 *   - 燃料棒/控制棒/功率接口数量与内部尺寸由 {@link CompactConfig} 决定；
 *   - 水经流体能力输入，蒸汽经流体能力输出（与真实 FluidPort 语义一致）；
 *   - 功率通过能量能力 / 相邻推送输出；
 *   - 控制棒插入比例可通过 {@link #setControlRodInsertionRatio(int)} 调节（GUI 用）。
 */
public class CompactReactorTileEntity extends AbstractCompactMachineTileEntity {

    public CompactReactorTileEntity(BlockPos pos, BlockState state) {
        super(Content.COMPACT_REACTOR_ENTITY.get(), pos, state);
    }

    @Override
    protected ICompactController createController() {
        // 从配置读取模拟参数，向 ER 控制器"谎报"多方块规模
        return new CompactReactorController(this.level,
                this.worldPosition,
                CompactConfig.REACTOR_FUEL_RODS.get(),
                CompactConfig.REACTOR_CONTROL_RODS.get(),
                CompactConfig.REACTOR_POWER_TAPS.get(),
                CompactConfig.REACTOR_SIZE_X.get(),
                CompactConfig.REACTOR_SIZE_Y.get(),
                CompactConfig.REACTOR_SIZE_Z.get());
    }

    @Override
    protected void onControllerInitialized(ICompactController controller) {
        // 反应堆放置后直接运行（真实 ER 行为：装配即启动）
        controller.setMachineActive(true);
    }

    @Override
    protected IFluidHandler createFluidHandler(ICompactController controller) {
        // 反应堆流体端口：输入水 → 输出蒸汽
        final IFluidHandler input = controller.getFluidHandler(IoDirection.Input).orElse(EmptyFluidHandler.INSTANCE);
        final IFluidHandler output = controller.getFluidHandler(IoDirection.Output).orElse(EmptyFluidHandler.INSTANCE);
        return new MachineFluidHandler(input, output);
    }

    // ------------------------------------------------------------------
    // 控制棒调节（供 GUI 使用）
    // ------------------------------------------------------------------

    /** 当前模拟控制棒插入比例（0-100）。 */
    public byte getControlRodInsertionRatio() {
        final ICompactController controller = this.getController();
        return controller instanceof CompactReactorController reactor
                ? reactor.getControlRodInsertionRatio()
                : 50;
    }

    /** 设置模拟控制棒插入比例（0-100），并标记方块需要保存。 */
    public void setControlRodInsertionRatio(int ratio) {
        final ICompactController controller = this.getController();
        if (controller instanceof CompactReactorController reactor) {
            reactor.setControlRodInsertionRatio(ratio);
            this.setChanged();
        }
    }
}
