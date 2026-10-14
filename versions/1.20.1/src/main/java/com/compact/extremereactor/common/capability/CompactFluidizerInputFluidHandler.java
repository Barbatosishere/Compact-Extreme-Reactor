package com.compact.extremereactor.common.capability;

import it.zerono.mods.zerocore.lib.fluid.FluidStackHolder;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/** 两个 8000 mB 原料罐，只允许输入；优先填同类罐，再填一个空罐。 */
public class CompactFluidizerInputFluidHandler implements IFluidHandler {

    private final FluidStackHolder _input0;
    private final FluidStackHolder _input1;
    private final Predicate<FluidStack> _validator;

    public CompactFluidizerInputFluidHandler(FluidStackHolder input0, FluidStackHolder input1,
                                             Predicate<FluidStack> validator) {
        this._input0 = input0;
        this._input1 = input1;
        this._validator = validator;
    }

    @Override
    public int getTanks() {
        return 2;
    }

    @Override
    public @NotNull FluidStack getFluidInTank(int tank) {
        final FluidStackHolder holder = this.holderFor(tank);
        return holder == null ? FluidStack.EMPTY : holder.getFluidInTank(0);
    }

    @Override
    public int getTankCapacity(int tank) {
        final FluidStackHolder holder = this.holderFor(tank);
        return holder == null ? 0 : holder.getTankCapacity(0);
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        final FluidStackHolder holder = this.holderFor(tank);
        if (holder == null || stack.isEmpty() || !this._validator.test(stack)) {
            return false;
        }
        final FluidStack stored = holder.getFluidInTank(0);
        if (!stored.isEmpty()) {
            return stored.isFluidEqual(stack);
        }
        final FluidStack other = (tank == 0 ? this._input1 : this._input0).getFluidInTank(0);
        return other.isEmpty() || !other.isFluidEqual(stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty()) {
            return 0;
        }
        final FluidStack stored0 = this._input0.getFluidInTank(0);
        final FluidStack stored1 = this._input1.getFluidInTank(0);
        final boolean same0 = !stored0.isEmpty() && stored0.isFluidEqual(resource);
        final boolean same1 = !stored1.isEmpty() && stored1.isFluidEqual(resource);
        final FluidStackHolder target;
        if (same0 && stored0.getAmount() < this._input0.getTankCapacity(0)) {
            target = this._input0;
        } else if (same1 && stored1.getAmount() < this._input1.getTankCapacity(0)) {
            target = this._input1;
        } else if (same0 || same1) {
            return 0;
        } else if (stored0.isEmpty()) {
            target = this._input0;
        } else if (stored1.isEmpty()) {
            target = this._input1;
        } else {
            return 0;
        }
        return this._validator.test(resource) ? target.fill(resource, action) : 0;
    }

    @Override
    public @NotNull FluidStack drain(FluidStack resource, FluidAction action) {
        // 进料只进不出（与真实注入器交互语义一致：产物走输出端口）
        return FluidStack.EMPTY;
    }

    @Override
    public @NotNull FluidStack drain(int maxDrain, FluidAction action) {
        return FluidStack.EMPTY;
    }

    // 内部

    @Nullable
    private FluidStackHolder holderFor(int tank) {
        return switch (tank) {
            case 0 -> this._input0;
            case 1 -> this._input1;
            default -> null;
        };
    }

}
