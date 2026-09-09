package com.compact.extremereactor.common.tile;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.capability.CompactReactorFluidHandler;
import com.compact.extremereactor.common.capability.CompactReactorItemHandler;
import com.compact.extremereactor.common.capability.MachineFluidHandler;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import it.zerono.mods.extremereactors.api.coolant.FluidMappingsRegistry;
import it.zerono.mods.extremereactors.api.reactor.ReactantMappingsRegistry;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.EmptyFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

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
        // 反应堆默认关闭，需要玩家手动点击 GUI 开关激活（匹配真实 ER 行为）
        // 若配置 autoStart=true，则放置后自动运行
        if (com.compact.extremereactor.common.config.CompactConfig.REACTOR_AUTO_START.get()) {
            controller.setMachineActive(true);
        }
    }

    @Override
    protected IFluidHandler createFluidHandler() {
        // 反应堆流体端口：输入水 → 输出蒸汽
        final ICompactController controller = this._controller;
        final IFluidHandler input = controller.getFluidHandler(IoDirection.Input).orElse(EmptyFluidHandler.INSTANCE);
        final IFluidHandler output = controller.getFluidHandler(IoDirection.Output).orElse(EmptyFluidHandler.INSTANCE);
        if (controller instanceof CompactReactorController reactor) {
            return new CompactReactorFluidHandler(reactor, input, output, this::setChanged);
        }
        return new MachineFluidHandler(input, output);
    }

    @Override
    protected int getPendingFluidTankCount() {
        return 3;
    }

    @Override
    protected boolean isPendingFluidValid(int tank, FluidStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return switch (tank) {
            case 0 -> FluidMappingsRegistry.hasCoolantFrom(stack.getFluid());
            case 2 -> ReactantMappingsRegistry.getFromFluid(stack)
                    .filter(mapping -> mapping.getProduct().getType().isFuel())
                    .filter(mapping -> mapping.getSourceAmount() > 0 && mapping.getProductAmount() > 0)
                    .isPresent();
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // 物品能力（供管道输入燃料 / 提取废物）
    // ------------------------------------------------------------------

    /** 物品处理器实例（惰性创建，见 {@link #getItemHandler}）。 */
    @Nullable
    private IItemHandler _itemHandler;

    /** 物品能力对象（管道用）：插入燃料物品、提取废物物品。 */
    @Nullable
    public IItemHandler getItemHandler(@Nullable net.minecraft.core.Direction side) {
        if (!this.canExposeRuntimeCapabilities()) {
            return null;
        }
        this.getController();
        if (this._itemHandler == null) {
            this._itemHandler = new CompactReactorItemHandler(this);
        }
        return this._itemHandler;
    }

    @Override
    protected void onControllerReleased() {
        // 控制器释放后使旧物品处理器 fail-closed，防止重新挂载时旧引用连接到新控制器
        if (this._itemHandler instanceof CompactReactorItemHandler reactorItemHandler) {
            reactorItemHandler.release();
        }
        this._itemHandler = null;
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

    /** 基于服务端当前值相对调节控制棒，避免多个客户端用陈旧绝对值互相覆盖。 */
    public int adjustControlRodInsertionRatio(int delta) {
        final ICompactController controller = this.getController();
        if (controller instanceof CompactReactorController reactor) {
            final int ratio = Math.clamp(reactor.getControlRodInsertionRatio() + delta, 0, 100);
            reactor.setControlRodInsertionRatio(ratio);
            this.setChanged();
            return ratio;
        }
        return 50;
    }
}
