package com.compact.extremereactor.common.capability;

import it.zerono.mods.extremereactors.api.coolant.FluidMappingsRegistry;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidContainer;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidType;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;

/** 无实体 FluidPort 时直接访问容器；每个实例只处理指定的 Liquid 或 Gas 输入/输出端点。 */
public class BypassFluidHandler implements IFluidHandler {

    private final FluidContainer _container;
    private final boolean _isInput;
    private final IntSupplier _capacity;
    private final FluidType _target;
    @Nullable
    private volatile Runnable _dirtyCallback;
    private volatile boolean _released;

    /** 创建严格绑定到指定 Liquid/Gas 端点的 handler。 */
    public BypassFluidHandler(FluidContainer container, boolean isInput, IntSupplier capacity,
                              @Nullable Runnable dirtyCallback, FluidType target) {
        this._container = container;
        this._isInput = isInput;
        this._capacity = capacity;
        this._dirtyCallback = dirtyCallback;
        this._target = target;
    }

    /** 更新流体变化回调，避免缓存 handler 捕获失效的 TileEntity 回调。 */
    public void setDirtyCallback(@Nullable Runnable callback) {
        this._dirtyCallback = callback;
    }

    /** 使已被卸载/移除的旧 handler 失效，阻止外部缓存引用继续写入 detached controller。 */
    public void release() {
        this._released = true;
        this._dirtyCallback = null;
    }

    private void markDirty() {
        final Runnable callback = this._dirtyCallback;
        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public int getTanks() {
        return this._released ? 0 : 1;
    }

    @Override
    public @NotNull FluidStack getFluidInTank(int tank) {
        if (this._released || tank != 0) {
            return FluidStack.EMPTY;
        }
        return this._container.getStackCopy(this._target);
    }

    @Override
    public int getTankCapacity(int tank) {
        if (this._released || tank != 0) {
            return 0;
        }
        return Math.max(0, this._capacity.getAsInt());
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        if (this._released || !this._isInput || tank != 0 || stack.isEmpty()) {
            return false;
        }
        return this._target.isGas()
                ? FluidMappingsRegistry.hasVaporFrom(stack.getFluid())
                : FluidMappingsRegistry.hasCoolantFrom(stack.getFluid());
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (!this.isFluidValid(0, resource)) {
            return 0;
        }
        final int inserted = this._container.insert(this._target, resource, OperationMode.from(action));
        if (inserted > 0 && action == FluidAction.EXECUTE) {
            this.markDirty();
        }
        return inserted;
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        if (this._released || this._isInput || resource.isEmpty()) {
            return FluidStack.EMPTY;
        }
        final FluidStack result = this._container.extract(this._target, resource, OperationMode.from(action));
        if (!result.isEmpty() && action == FluidAction.EXECUTE) {
            this.markDirty();
        }
        return result;
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        if (this._released || this._isInput || maxDrain <= 0) {
            return FluidStack.EMPTY;
        }
        final FluidStack result = this._container.extract(this._target, maxDrain, OperationMode.from(action));
        if (!result.isEmpty() && action == FluidAction.EXECUTE) {
            this.markDirty();
        }
        return result;
    }
}
