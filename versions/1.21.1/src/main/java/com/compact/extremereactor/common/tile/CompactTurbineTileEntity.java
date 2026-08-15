package com.compact.extremereactor.common.tile;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.capability.MachineFluidHandler;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.multiblock.CompactTurbineController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.EmptyFluidHandler;

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
        // 涡轮机就绪即可运行（有蒸汽才发电，无蒸汽自然停转）
        controller.setMachineActive(true);
    }

    @Override
    protected IFluidHandler createFluidHandler(ICompactController controller) {
        // 涡轮机流体端口：输入蒸汽 → 输出冷凝水
        final IFluidHandler input = controller.getFluidHandler(IoDirection.Input).orElse(EmptyFluidHandler.INSTANCE);
        final IFluidHandler output = controller.getFluidHandler(IoDirection.Output).orElse(EmptyFluidHandler.INSTANCE);
        return new MachineFluidHandler(input, output);
    }
}
