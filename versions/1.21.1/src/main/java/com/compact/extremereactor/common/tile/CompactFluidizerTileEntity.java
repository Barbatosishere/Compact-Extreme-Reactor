package com.compact.extremereactor.common.tile;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.capability.CompactEnergySink;
import com.compact.extremereactor.common.capability.CompactFluidizerItemHandler;
import com.compact.extremereactor.common.capability.MachineFluidHandler;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.EmptyFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 压缩流化器 TileEntity：持有 {@link CompactFluidizerController} 并驱动其模拟。
 *
 * 一个方块 = 一个完整的流化器多方块：
 *   - 固体进料（2 槽）/ 流体进料（2 罐）经物品 / 流体能力输入；
 *   - 产物流体经流体能力输出（容量 = 内部体积 × 4000 mB）；
 *   - 能量通过能量能力<b>输入</b>（消费型机器，与反应堆/涡轮机相反）；
 *   - 不推送能量：{@code pushPower} 因控制器 {@code extractEnergy} 恒为 0 而自然跳过。
 */
public class CompactFluidizerTileEntity extends AbstractCompactMachineTileEntity {

    public CompactFluidizerTileEntity(BlockPos pos, BlockState state) {
        super(Content.COMPACT_FLUIDIZER_ENTITY.get(), pos, state);
    }

    @Override
    protected ICompactController createController() {
        // 从配置读取模拟多方块尺寸（决定输出罐容量 = 内部体积 × 4000 mB）
        return new CompactFluidizerController(this.level,
                this.worldPosition,
                CompactConfig.FLUIDIZER_SIZE_X.get(),
                CompactConfig.FLUIDIZER_SIZE_Y.get(),
                CompactConfig.FLUIDIZER_SIZE_Z.get());
    }

    @Override
    protected void onControllerInitialized(ICompactController controller) {
        // 激活策略与涡轮机一致：读档保留存档状态；新放置默认开启
        // （流化器无原料/无能量时配方处理自然暂停，默认开启对玩家最省事）。
        if (!this.hasPendingControllerTag()) {
            controller.setMachineActive(true);
        }
    }

    @Override
    protected com.compact.extremereactor.common.capability.CompactEnergyStorage createEnergyStorage() {
        // 消费型机器：外部 FE 注入控制器的能量缓冲（50k），只进不出
        return new CompactEnergySink(() -> this._controller);
    }

    @Override
    protected IFluidHandler createFluidHandler() {
        // 流化器流体端口：输入配方流体（两罐）→ 输出产物流体
        final ICompactController controller = this._controller;
        final IFluidHandler input = controller.getFluidHandler(IoDirection.Input).orElse(EmptyFluidHandler.INSTANCE);
        final IFluidHandler output = controller.getFluidHandler(IoDirection.Output).orElse(EmptyFluidHandler.INSTANCE);
        return new MachineFluidHandler(input, output);
    }

    @Override
    protected int getPendingFluidTankCount() {
        // 2 个进料罐 + 1 个产物罐
        return 3;
    }

    @Override
    protected boolean isPendingFluidValid(int tank, FluidStack stack) {
        return tank < 2 && !stack.isEmpty() && CompactFluidizerController.isValidFluidIngredient(stack);
    }

    // ------------------------------------------------------------------
    // 物品能力（供管道/玩家输入固体原料）
    // ------------------------------------------------------------------

    /** 物品处理器实例（惰性创建）。 */
    @Nullable
    private IItemHandler _itemHandler;

    /** 物品能力对象：插入/提取两个固体进料槽的原料。 */
    @Nullable
    public IItemHandler getItemHandler(@Nullable net.minecraft.core.Direction side) {
        if (!this.canExposeRuntimeCapabilities()) {
            return null;
        }
        this.getController();
        if (this._itemHandler == null) {
            this._itemHandler = new CompactFluidizerItemHandler(this);
        }
        return this._itemHandler;
    }

    @Override
    protected void onControllerReleased() {
        // 控制器释放后使旧物品处理器 fail-closed，防止重新挂载时旧引用连接到新控制器
        if (this._itemHandler instanceof CompactFluidizerItemHandler fluidizerItemHandler) {
            fluidizerItemHandler.release();
        }
        this._itemHandler = null;
    }
}
