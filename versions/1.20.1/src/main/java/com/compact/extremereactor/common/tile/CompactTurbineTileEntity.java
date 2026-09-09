package com.compact.extremereactor.common.tile;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.capability.MachineFluidHandler;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.multiblock.CompactTurbineController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import it.zerono.mods.extremereactors.api.coolant.FluidMappingsRegistry;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.EmptyFluidHandler;

/**
 * 压缩涡轮机 TileEntity：持有 {@link CompactTurbineController} 并驱动其模拟。
 *
 * 一个方块 = 一个完整的涡轮机多方块：
 *   - 转轴/叶片/线圈规模由模拟布局与 {@link CompactConfig} 决定；
 *   - 蒸汽经流体能力输入，冷凝水经流体能力输出（与真实 FluidPort 语义一致）；
 *   - 功率通过能量能力 / 相邻推送输出；
 *   - 没有蒸汽输入时涡轮机不转动（真实 ER 行为）。
 */
public class CompactTurbineTileEntity extends AbstractCompactMachineTileEntity {

    public CompactTurbineTileEntity(BlockPos pos, BlockState state) {
        super(Content.COMPACT_TURBINE_ENTITY.get(), pos, state);
    }

    @Override
    protected ICompactController createController() {
        // 从配置读取模拟参数：线圈半径与内部尺寸
        return new CompactTurbineController(this.level,
                this.worldPosition,
                CompactConfig.TURBINE_COIL_RADIUS.get(),
                CompactConfig.TURBINE_SIZE_X.get(),
                CompactConfig.TURBINE_SIZE_Y.get(),
                CompactConfig.TURBINE_SIZE_Z.get());
    }

    @Override
    protected void onControllerInitialized(ICompactController controller) {
        // 涡轮机激活策略：
        //   1. 读档：_pendingControllerTag != null → ER syncDataFrom 已在 initController 中先于本钩子
        //      执行，已恢复存档的 _isActive 状态（玩家之前的开关选择保留）→ 不再覆盖。
        //   2. 新放置：_pendingControllerTag == null → 没有存档数据 → 显式 setMachineActive(true)
        //      确保新放置涡轮机立即运行（不依赖 ER 内部默认值，未来 ER 版本默认 false 也不会受影响）。
        if (!this.hasPendingControllerTag()) {
            controller.setMachineActive(true);
        }
    }

    @Override
    protected IFluidHandler createFluidHandler() {
        // 涡轮机流体端口：输入蒸汽 → 输出冷凝水
        final ICompactController controller = this._controller;
        final IFluidHandler input = controller.getFluidHandler(IoDirection.Input).orElse(EmptyFluidHandler.INSTANCE);
        final IFluidHandler output = controller.getFluidHandler(IoDirection.Output).orElse(EmptyFluidHandler.INSTANCE);
        return new MachineFluidHandler(input, output);
    }

    @Override
    protected int getPendingFluidTankCount() {
        return 2;
    }

    @Override
    protected boolean isPendingFluidValid(int tank, FluidStack stack) {
        return tank == 0 && !stack.isEmpty() && FluidMappingsRegistry.hasVaporFrom(stack.getFluid());
    }
}
