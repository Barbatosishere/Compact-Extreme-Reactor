package com.compact.extremereactor.common.tile;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.capability.CompactEnergySink;
import com.compact.extremereactor.common.capability.CompactEnergyStorage;
import com.compact.extremereactor.common.capability.CompactFluidizerItemHandler;
import com.compact.extremereactor.common.capability.MachineFluidHandler;
import com.compact.extremereactor.common.config.CompactConfig;
import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.multiblock.ICompactController;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.EmptyFluidHandler;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/** 流化器方块实体：原料与 FE 输入，产物流体输出。 */
public class CompactFluidizerTileEntity extends AbstractCompactMachineTileEntity {

    public CompactFluidizerTileEntity(BlockPos pos, BlockState state) {
        super(Content.COMPACT_FLUIDIZER_ENTITY.get(), pos, state);
    }

    @Override
    protected ICompactController createController() {
        var structure = this.getStructureState();
        // 从配置读取模拟多方块尺寸（决定输出罐容量 = 内部体积 × 4000 mB）
        return new CompactFluidizerController(this.level,
                this.worldPosition,
                CompactConfig.FLUIDIZER_SIZE_X.get(),
                structure.height(CompactConfig.FLUIDIZER_SIZE_Y.get()),
                CompactConfig.FLUIDIZER_SIZE_Z.get(), structure.simulationScale());
    }

    @Override
    protected void onControllerInitialized(ICompactController controller) {
        // 新放置默认开启，读档保留存档开关状态。
        if (!this.hasPendingControllerTag()) {
            controller.setMachineActive(true);
        }
    }

    @Override
    public void serverTick() {
        if (!this.canExposeRuntimeCapabilities()) {
            return;
        }
        if (!this.prepareStructureTick()) return;
        final ICompactController controller = this._controller;
        if (controller != null) {
            this.tickController(controller);
        }
    }

    @Override
    protected CompactEnergyStorage createEnergyStorage() {
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


    @Nullable
    private CompactFluidizerItemHandler _itemHandler;

    /** 物品能力 LazyOptional 缓存：同一能力必须返回同一实例并在失效时通知缓存方（Forge 规范）。 */
    @Nullable
    private LazyOptional<IItemHandler> _itemCapability;

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

    /** 物品能力的 LazyOptional 视图（缓存单实例，失效后自动重建），由主类 AttachCapabilitiesEvent 注册。 */
    public LazyOptional<IItemHandler> getItemCapability(@Nullable net.minecraft.core.Direction side) {
        final IItemHandler handler = this.getItemHandler(side);
        if (handler == null) {
            return LazyOptional.empty();
        }
        if (this._itemCapability == null || !this._itemCapability.isPresent()) {
            this._itemCapability = LazyOptional.of(() -> handler);
        }
        return this._itemCapability.cast();
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        if (this._itemCapability != null) {
            this._itemCapability.invalidate();
            this._itemCapability = null;
        }
    }

    @Override
    protected void onControllerReleased() {
        // 释放旧能力，防止重载后访问新控制器。
        if (this._itemHandler != null) {
            this._itemHandler.release();
        }
        this._itemHandler = null;
    }
}
