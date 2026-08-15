package com.compact.extremereactor.common.capability;

import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;

/**
 * 组合流体处理器：将控制器的"输入"与"输出"两个流体处理器合并为一个能力。
 *
 * 压缩机器只暴露一个流体能力槽，但需要同时支持进料与出料：
 *   - 反应堆：输入水 → 输出蒸汽
 *   - 涡轮机：输入蒸汽 → 输出冷凝水
 * 本类把 fill 路由到输入处理器、drain 路由到输出处理器，槽位索引按
 * "先输入后输出"合并，让玩家用一条流体管道即可完成进料与出料。
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
        return this.tankOf(tank).getFluidInTank(this.localIndex(tank));
    }

    @Override
    public int getTankCapacity(int tank) {
        return this.tankOf(tank).getTankCapacity(this.localIndex(tank));
    }

    @Override
    public boolean isFluidValid(int tank, @NotNull FluidStack stack) {
        return this.tankOf(tank).isFluidValid(this.localIndex(tank), stack);
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

    /** 按合并后的槽位索引路由到输入/输出处理器。 */
    private IFluidHandler tankOf(int tank) {
        return tank < this._input.getTanks() ? this._input : this._output;
    }

    /** 转换为子处理器内部的槽位索引。 */
    private int localIndex(int tank) {
        return tank < this._input.getTanks() ? tank : tank - this._input.getTanks();
    }
}
