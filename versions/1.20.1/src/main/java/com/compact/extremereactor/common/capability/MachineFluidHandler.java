package com.compact.extremereactor.common.capability;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 合并输入和输出端点；槽位依次映射到输入和输出处理器。 */
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
        if (tank < 0) {
            return FluidStack.EMPTY;
        }
        final int inputTanks = this._input.getTanks();
        final IFluidHandler handler = this.handlerFor(tank, inputTanks);
        return handler == null ? FluidStack.EMPTY : handler.getFluidInTank(this.localIndex(tank, inputTanks));
    }

    @Override
    public int getTankCapacity(int tank) {
        if (tank < 0) {
            return 0;
        }
        final int inputTanks = this._input.getTanks();
        final IFluidHandler handler = this.handlerFor(tank, inputTanks);
        return handler == null ? 0 : handler.getTankCapacity(this.localIndex(tank, inputTanks));
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        if (tank < 0 || stack.isEmpty()) {
            return false;
        }
        final int inputTanks = this._input.getTanks();
        final IFluidHandler handler = this.handlerFor(tank, inputTanks);
        return handler != null && handler.isFluidValid(this.localIndex(tank, inputTanks), stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) {
            return 0;
        }
        return this._input.fill(resource, action);
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) {
            return FluidStack.EMPTY;
        }
        return this._output.drain(resource, action);
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        if (maxDrain <= 0) {
            return FluidStack.EMPTY;
        }
        return this._output.drain(maxDrain, action);
    }

    private @Nullable IFluidHandler handlerFor(int tank, int inputTanks) {
        if (tank < inputTanks) {
            return this._input;
        }
        return tank - inputTanks < this._output.getTanks() ? this._output : null;
    }

    private int localIndex(int tank, int inputTanks) {
        return tank < inputTanks ? tank : tank - inputTanks;
    }
}
