package com.compact.extremereactor.common.capability;

import it.zerono.mods.zerocore.lib.fluid.FluidStackHolder;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * 压缩流化器的进料流体处理器：合并两个流体进料罐（对应真实多方块的两个流体注入器）。
 *
 * <p>语义与 ER2 注入器一致：</p>
 * <ul>
 *   <li>每罐容量固定 {@code 8000 mB}（8 桶），只接受流化器配方匹配的流体；</li>
 *   <li>fill 优先补入与来料相同流体的罐，其次进入空罐；单次填充不跨罐（保留另一种原料的位置）；</li>
 *   <li>drain 恒为空：进料只进不出（产物从输出端口抽取）。</li>
 * </ul>
 */
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

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    @Nullable
    private FluidStackHolder holderFor(int tank) {
        return switch (tank) {
            case 0 -> this._input0;
            case 1 -> this._input1;
            default -> null;
        };
    }

}
