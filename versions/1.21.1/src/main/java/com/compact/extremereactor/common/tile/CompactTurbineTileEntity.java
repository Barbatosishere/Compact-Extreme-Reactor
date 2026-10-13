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
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.EmptyFluidHandler;

/** 涡轮方块实体：蒸汽输入，冷凝水和 FE 输出。 */
public class CompactTurbineTileEntity extends AbstractCompactMachineTileEntity {

    public CompactTurbineTileEntity(BlockPos pos, BlockState state) {
        super(Content.COMPACT_TURBINE_ENTITY.get(), pos, state);
    }

    @Override
    protected ICompactController createController() {
        var structure = this.getStructureState();
        // 从配置读取模拟参数：线圈半径与内部尺寸
        return new CompactTurbineController(this.level,
                this.worldPosition,
                CompactConfig.TURBINE_COIL_RADIUS.get(),
                CompactConfig.TURBINE_SIZE_X.get(),
                CompactConfig.TURBINE_SIZE_Y.get(),
                CompactConfig.TURBINE_SIZE_Z.get(), structure.simulationScale());
    }

    @Override
    protected void onControllerInitialized(ICompactController controller) {
        // 新放置默认开启；读档和结构重建保留玩家开关状态。
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
