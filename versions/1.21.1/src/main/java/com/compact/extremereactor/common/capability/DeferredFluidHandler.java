package com.compact.extremereactor.common.capability;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiPredicate;
import java.util.function.Supplier;

/**
 * 稳定的流体能力代理：在控制器尚未于顶层服务端 tick 初始化时，不触发初始化，
 * 但仍保留正确的逻辑槽数量和静态输入过滤；控制器准备好后自动转发到真实 handler。
 */
public final class DeferredFluidHandler implements IFluidHandler {

    private final Supplier<? extends IFluidHandler> _delegate;
    private final int _pendingTanks;
    private final BiPredicate<Integer, FluidStack> _pendingValidator;
    private volatile boolean _released;

    public DeferredFluidHandler(Supplier<? extends IFluidHandler> delegate,
                                int pendingTanks,
                                BiPredicate<Integer, FluidStack> pendingValidator) {
        this._delegate = delegate;
        this._pendingTanks = Math.max(0, pendingTanks);
        this._pendingValidator = pendingValidator;
    }

    /** 使区块卸载或方块移除前已分发的旧能力引用 fail-closed。 */
    public void release() {
        this._released = true;
    }

    @Nullable
    private IFluidHandler delegate() {
        return this._released ? null : this._delegate.get();
    }

    @Override
    public int getTanks() {
        final IFluidHandler delegate = this.delegate();
        return delegate == null ? (this._released ? 0 : this._pendingTanks) : delegate.getTanks();
    }

    @Override
    public @NotNull FluidStack getFluidInTank(int tank) {
        final IFluidHandler delegate = this.delegate();
        return delegate == null ? FluidStack.EMPTY : delegate.getFluidInTank(tank);
    }

    @Override
    public int getTankCapacity(int tank) {
        final IFluidHandler delegate = this.delegate();
        return delegate == null ? 0 : delegate.getTankCapacity(tank);
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        final IFluidHandler delegate = this.delegate();
        return delegate == null
                ? !this._released && tank >= 0 && tank < this._pendingTanks
                        && this._pendingValidator.test(tank, stack)
                : delegate.isFluidValid(tank, stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        final IFluidHandler delegate = this.delegate();
        return delegate == null ? 0 : delegate.fill(resource, action);
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        final IFluidHandler delegate = this.delegate();
        return delegate == null ? FluidStack.EMPTY : delegate.drain(resource, action);
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        final IFluidHandler delegate = this.delegate();
        return delegate == null ? FluidStack.EMPTY : delegate.drain(maxDrain, action);
    }
}
