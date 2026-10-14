package com.compact.extremereactor.common.capability;

import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import it.zerono.mods.zerocore.lib.energy.IWideEnergyStorage2;
import net.neoforged.neoforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** 将 WideAmount 适配为整数 FE 输出；返回值钳制到 int 范围。 */
public class CompactEnergyStorage implements IEnergyStorage {

    private final Supplier<? extends IWideEnergyStorage2> _delegate;
    private volatile boolean _released;

    public CompactEnergyStorage(IWideEnergyStorage2 delegate) {
        this(() -> delegate);
    }

    /** 延迟解析控制器，避免能力查询在区块加载等嵌套路径中同步初始化控制器。 */
    public CompactEnergyStorage(Supplier<? extends IWideEnergyStorage2> delegate) {
        this._delegate = delegate;
    }

    /** 使已分发的旧能力引用立即失效；可重复调用。 */
    public void release() {
        this._released = true;
    }

    @Nullable
    protected IWideEnergyStorage2 delegate() {
        return this._released ? null : this._delegate.get();
    }

    /** 子类（如 {@link CompactEnergySink}）判断失效状态。 */
    protected boolean isReleased() {
        return this._released;
    }

    protected static int clampToInt(long amount) {
        return amount <= 0 ? 0 : amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {

        return 0;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        if (this._released || maxExtract <= 0) {
            return 0;
        }
        final IWideEnergyStorage2 delegate = this.delegate();
        if (delegate == null) {
            return 0;
        }
        final int available = Math.min(maxExtract, clampToInt(delegate
                .extractEnergy(EnergySystem.ForgeEnergy, WideAmount.from(maxExtract), OperationMode.Simulate)
                .longValue()));
        if (simulate || available == 0) {
            return available;
        }
        return clampToInt(delegate
                .extractEnergy(EnergySystem.ForgeEnergy, WideAmount.from(available), OperationMode.Execute)
                .longValue());
    }

    @Override
    public int getEnergyStored() {
        final IWideEnergyStorage2 delegate = this.delegate();
        if (delegate == null) {
            return 0;
        }
        final long energy = delegate.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
        return clampToInt(energy);
    }

    @Override
    public int getMaxEnergyStored() {
        final IWideEnergyStorage2 delegate = this.delegate();
        if (delegate == null) {
            return 0;
        }
        final long capacity = delegate.getCapacity(EnergySystem.ForgeEnergy).longValue();
        return clampToInt(capacity);
    }

    @Override
    public boolean canExtract() {
        return !this._released;
    }

    @Override
    public boolean canReceive() {
        return false;
    }
}
