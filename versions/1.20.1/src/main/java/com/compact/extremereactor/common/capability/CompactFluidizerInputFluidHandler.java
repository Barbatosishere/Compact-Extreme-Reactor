package com.compact.extremereactor.common.capability;

import it.zerono.mods.zerocore.lib.fluid.FluidStackHolder;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** 两个 8000 mB 原料罐，只允许输入；同流体混合按配方用量分配，其他配方优先填同类罐。 */
public class CompactFluidizerInputFluidHandler implements IFluidHandler {

    private final FluidStackHolder _input0;
    private final FluidStackHolder _input1;
    private final Predicate<FluidStack> _validator;
    private final ToIntFunction<FluidStack> _sameFluidFirstAmount;
    private final BiPredicate<FluidStack, FluidStack> _pairValidator;

    public CompactFluidizerInputFluidHandler(FluidStackHolder input0, FluidStackHolder input1,
                                             Predicate<FluidStack> validator) {
        this(input0, input1, validator, stack -> 0);
    }

    public CompactFluidizerInputFluidHandler(FluidStackHolder input0, FluidStackHolder input1,
                                             Predicate<FluidStack> validator, ToIntFunction<FluidStack> sameFluidFirstAmount) {
        this(input0, input1, validator, sameFluidFirstAmount,
                (first, second) -> first.isEmpty() || second.isEmpty() || !first.isFluidEqual(second)
                        || sameFluidFirstAmount.applyAsInt(first) > 0);
    }

    /** pairValidator 按原料罐顺序检查配方，空栈表示尚未填入的原料。 */
    public CompactFluidizerInputFluidHandler(FluidStackHolder input0, FluidStackHolder input1,
                                             Predicate<FluidStack> validator, ToIntFunction<FluidStack> sameFluidFirstAmount,
                                             BiPredicate<FluidStack, FluidStack> pairValidator) {
        this._input0 = input0;
        this._input1 = input1;
        this._validator = validator;
        this._sameFluidFirstAmount = sameFluidFirstAmount;
        this._pairValidator = pairValidator;
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
        if (!stored.isEmpty() && !stored.isFluidEqual(stack)) {
            return false;
        }
        final FluidStack other = (tank == 0 ? this._input1 : this._input0).getFluidInTank(0);
        return tank == 0 ? this._pairValidator.test(stack, other) : this._pairValidator.test(other, stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !this._validator.test(resource)) {
            return 0;
        }
        final FluidStack stored0 = this._input0.getFluidInTank(0);
        final FluidStack stored1 = this._input1.getFluidInTank(0);
        final boolean same0 = !stored0.isEmpty() && stored0.isFluidEqual(resource);
        final boolean same1 = !stored1.isEmpty() && stored1.isFluidEqual(resource);
        final int firstAmount = this._sameFluidFirstAmount.applyAsInt(resource);
        if (firstAmount > 0 && (stored0.isEmpty() || same0) && (stored1.isEmpty() || same1)
                && this._pairValidator.test(resource, resource)) {
            return this.fillSameFluid(resource, action, firstAmount, stored0.getAmount());
        }
        final FluidStackHolder target;
        if (same0 && stored0.getAmount() < this._input0.getTankCapacity(0) && this.isFluidValid(0, resource)) {
            target = this._input0;
        } else if (same1 && stored1.getAmount() < this._input1.getTankCapacity(0) && this.isFluidValid(1, resource)) {
            target = this._input1;
        } else if (same0 || same1) {
            return 0;
        } else if (stored0.isEmpty() && this.isFluidValid(0, resource)) {
            target = this._input0;
        } else if (stored1.isEmpty() && this.isFluidValid(1, resource)) {
            target = this._input1;
        } else {
            return 0;
        }
        return target.fill(resource, action);
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

    /** 先满足配方的第一个输入，再填第二罐；余量回填第一罐，模拟与执行使用相同的容量预算。 */
    private int fillSameFluid(FluidStack resource, FluidAction action, int firstAmount, int stored0) {
        final int capacity0 = this._input0.getTankCapacity(0);
        final int needed0 = Math.max(0, Math.min(firstAmount, capacity0) - stored0);
        final int inserted0 = fillPortion(this._input0, resource, Math.min(resource.getAmount(), needed0), action);
        final int inserted1 = fillPortion(this._input1, resource, resource.getAmount() - inserted0, action);
        final int remainder = Math.min(resource.getAmount() - inserted0 - inserted1,
                Math.max(0, capacity0 - stored0 - inserted0));
        return inserted0 + inserted1 + fillPortion(this._input0, resource, remainder, action);
    }

    private static int fillPortion(FluidStackHolder target, FluidStack resource, int amount, FluidAction action) {
        if (amount <= 0) return 0;
        final FluidStack portion = resource.copy();
        portion.setAmount(amount);
        return target.fill(portion, action);
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
