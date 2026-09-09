package com.compact.extremereactor.common.capability;

import it.zerono.mods.extremereactors.api.coolant.FluidMappingsRegistry;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidContainer;
import it.zerono.mods.extremereactors.gamecontent.multiblock.common.FluidType;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;

/**
 * 直接代理 ER 内部 FluidContainer 的单向流体端点。
 *
 * <p>压缩机器没有真实 FluidPort，因此不使用 ER 的受限端口 handler。每个实例只代表一个
 * 严格的 Liquid 或 Gas 端点：输入端只接受 fill，输出端只允许从目标类型 drain。底层
 * ZeroCore stack 重载负责完整 FluidStack 内容匹配（Forge NBT/NeoForge components）。</p>
 */
public class BypassFluidHandler implements IFluidHandler {

    private final FluidContainer _container;
    private final boolean _isInput;
    private final IntSupplier _capacity;
    private final FluidType _target;
    @Nullable
    private volatile Runnable _dirtyCallback;
    private volatile boolean _released;

    public BypassFluidHandler(FluidContainer container, boolean isInput, IntSupplier capacity) {
        this(container, isInput, capacity, null, isInput ? FluidType.Liquid : FluidType.Gas);
    }

    public BypassFluidHandler(FluidContainer container, boolean isInput, IntSupplier capacity,
                              @Nullable Runnable dirtyCallback) {
        this(container, isInput, capacity, dirtyCallback,
                isInput ? FluidType.Liquid : FluidType.Gas);
    }

    /** 创建严格绑定到指定 Liquid/Gas 端点的 handler。 */
    public BypassFluidHandler(FluidContainer container, boolean isInput, IntSupplier capacity,
                              @Nullable Runnable dirtyCallback, FluidType target) {
        this._container = container;
        this._isInput = isInput;
        this._capacity = capacity;
        this._dirtyCallback = dirtyCallback;
        this._target = target;
    }

    /**
     * 兼容旧调用方：输入使用 fillTarget，输出使用 drainTarget；两个参数不能再表达 fallback
     * 优先级，最终只会选择一个严格目标端点。
     */
    public BypassFluidHandler(FluidContainer container, boolean isInput, IntSupplier capacity,
                              @Nullable Runnable dirtyCallback,
                              @Nullable FluidType fillTarget, @Nullable FluidType drainTarget) {
        this(container, isInput, capacity, dirtyCallback,
                isInput
                        ? (fillTarget == null ? FluidType.Liquid : fillTarget)
                        : (drainTarget == null ? FluidType.Gas : drainTarget));
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
