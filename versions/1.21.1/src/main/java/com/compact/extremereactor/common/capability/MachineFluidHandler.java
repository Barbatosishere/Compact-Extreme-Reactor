package com.compact.extremereactor.common.capability;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 组合流体处理器：把一个机器的输入端和输出端合并为两个逻辑槽位。
 *
 * <p>tank 0 是输入，tank 1 是输出；两者共享同一个 ER FluidContainer 的底层容量，
 * 并不是两个相互独立的储罐。fill/drain 仍按操作方向路由到对应端点。</p>
 */
public class MachineFluidHandler implements IFluidHandler {

    private final IFluidHandler _input;
    private final IFluidHandler _output;

    public MachineFluidHandler(IFluidHandler input, IFluidHandler output) {
        this._input = input;
        this._output = output;
    }

    @Override
    public int getTanks() {
        return this._input.getTanks() + this._output.getTanks();
    }

    @Override
    public @NotNull FluidStack getFluidInTank(int tank) {
        final IFluidHandler handler = this.handlerFor(tank);
        return handler == null ? FluidStack.EMPTY : handler.getFluidInTank(this.localIndex(tank));
    }

    @Override
    public int getTankCapacity(int tank) {
        final IFluidHandler handler = this.handlerFor(tank);
        return handler == null ? 0 : handler.getTankCapacity(this.localIndex(tank));
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        final IFluidHandler handler = this.handlerFor(tank);
        return handler != null && handler.isFluidValid(this.localIndex(tank), stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        return this._input.fill(resource, action);
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        return this._output.drain(resource, action);
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        return this._output.drain(maxDrain, action);
    }

    @Nullable
    private IFluidHandler handlerFor(int tank) {
        if (tank < 0 || tank >= this.getTanks()) {
            return null;
        }
        return tank < this._input.getTanks() ? this._input : this._output;
    }

    private int localIndex(int tank) {
        return tank < this._input.getTanks() ? tank : tank - this._input.getTanks();
    }
}
