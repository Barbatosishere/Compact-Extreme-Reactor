package com.compact.extremereactor.common.capability;

import it.zerono.mods.zerocore.lib.fluid.FluidStackHolder;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * 压缩流化器的进料流体处理器：合并两个流体进料罐（对应真实多方块的两个流体注入器）。
 *
 * <p>语义与 ER2 注入器一致：</p>
 * <ul>
 *   <li>每罐容量固定 {@code 8000 mB}（8 桶），只接受流化器配方匹配的流体；</li>
 *   <li>fill 优先补入与来料相同流体的罐，其次进入空罐（保证两个罐可分别装两种原料）；</li>
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
        return this.holderFor(tank) != null && !stack.isEmpty() && this._validator.test(stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource.isEmpty() || !this.isFluidValid(0, resource)) {
            return 0;
        }
        // 先补同流体罐，再进空罐；两罐分开装才能满足 FluidMixing 的两种原料
        final boolean firstIsInput0 = this.preferInput0(resource);
        final FluidStackHolder first = firstIsInput0 ? this._input0 : this._input1;
        final FluidStackHolder second = firstIsInput0 ? this._input1 : this._input0;
        final int filled = this.fillInto(first, resource, 0, action);
        return filled >= resource.getAmount() ? filled
                : filled + this.fillInto(second, resource, filled, action);
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

    /** fill 的罐顺序：同流体的罐优先，空罐次之（两罐都被异种流体占满时顺序无关，均会被拒绝）。 */
    private boolean preferInput0(FluidStack resource) {
        final boolean same0 = !this._input0.isEmpty(0)
                && FluidStack.isSameFluidSameComponents(this._input0.getFluidInTank(0), resource);
        final boolean same1 = !this._input1.isEmpty(0)
                && FluidStack.isSameFluidSameComponents(this._input1.getFluidInTank(0), resource);
        if (same0 || (!same1 && this._input0.isEmpty(0))) {
            return true;
        }
        return false;
    }

    private int fillInto(FluidStackHolder holder, FluidStack resource, int alreadyFilled, FluidAction action) {
        if (!this.acceptsInto(holder, resource)) {
            return 0;
        }
        final FluidStack remaining = resource.copy();
        remaining.shrink(alreadyFilled);
        return holder.fill(remaining, action);
    }

    /** 该罐是否可接受此流体：罐内为同流体，或罐为空。 */
    private boolean acceptsInto(FluidStackHolder holder, FluidStack resource) {
        final FluidStack current = holder.getFluidInTank(0);
        if (current.isEmpty()) {
            return true;
        }
        return FluidStack.isSameFluidSameComponents(current, resource);
    }
}
