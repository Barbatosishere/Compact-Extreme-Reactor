package com.compact.extremereactor.common.capability;

import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import it.zerono.mods.zerocore.lib.energy.IWideEnergyStorage2;
import net.minecraftforge.energy.IEnergyStorage;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 将 ZeroCore 的 {@link IWideEnergyStorage2}（ER 控制器）适配为 Forge 的
 * {@link IEnergyStorage} 能力，供能量线缆 / 相邻机器提取功率。
 *
 * 压缩机器是发电机：只允许提取（extract），不接受输入（receive）。
 * 注意 IEnergyStorage 以 int 为单位，ER 内部是 64 位 WideAmount，
 * 超出 int 范围的部分会被截断（实际游戏中单个方块能量容量远小于 2^31 的极少见）。
 */
public class CompactEnergyStorage implements IEnergyStorage {

    private final Supplier<? extends IWideEnergyStorage2> _delegate;
    private volatile boolean _released;

    public CompactEnergyStorage(IWideEnergyStorage2 delegate) {
        this(() -> delegate);
    }

    /**
     * 延迟解析控制器，避免能力查询在区块加载等嵌套路径中同步初始化控制器。
     */
    public CompactEnergyStorage(Supplier<? extends IWideEnergyStorage2> delegate) {
        this._delegate = delegate;
    }

    /** 使已分发的旧能力引用立即失效；可重复调用。 */
    public void release() {
        this._released = true;
    }

    @Nullable
    private IWideEnergyStorage2 delegate() {
        return this._released ? null : this._delegate.get();
    }

    /** 子类（如 {@link CompactEnergySink}）判断失效状态。 */
    protected boolean isReleased() {
        return this._released;
    }

    /** 子类访问延迟解析的控制器委托。 */
    @Nullable
    protected IWideEnergyStorage2 delegateForSubclass() {
        return this.delegate();
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        // 发电机不接受能量输入
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
        final OperationMode mode = simulate ? OperationMode.Simulate : OperationMode.Execute;
        final long extracted = delegate
                .extractEnergy(EnergySystem.ForgeEnergy, WideAmount.from(maxExtract), mode)
                .longValue();
        // 防止 long 超出 int 范围时截断
        return extracted > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) extracted;
    }

    @Override
    public int getEnergyStored() {
        final IWideEnergyStorage2 delegate = this.delegate();
        if (delegate == null) {
            return 0;
        }
        final long energy = delegate.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
        return energy > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) energy;
    }

    @Override
    public int getMaxEnergyStored() {
        final IWideEnergyStorage2 delegate = this.delegate();
        if (delegate == null) {
            return 0;
        }
        final long capacity = delegate.getCapacity(EnergySystem.ForgeEnergy).longValue();
        return capacity > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) capacity;
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
