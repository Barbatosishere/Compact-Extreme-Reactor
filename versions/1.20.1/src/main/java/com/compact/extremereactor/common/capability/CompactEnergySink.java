package com.compact.extremereactor.common.capability;

import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import it.zerono.mods.zerocore.lib.energy.IWideEnergyStorage2;

import java.util.function.Supplier;

/**
 * 能量输入端（消费型机器）：把外部 FE 注入 ER 控制器的能量缓冲。
 *
 * <p>与 {@link CompactEnergyStorage}（发电机，只出不进）相反：本适配器只接受输入，
 * 不允许提取。用于压缩流化器（{@code insertEnergy} 委托至 ER 能量缓冲，
 * {@code extractEnergy} 恒为 0，由控制器语义保证 fail-closed）。</p>
 */
public class CompactEnergySink extends CompactEnergyStorage {

    public CompactEnergySink(IWideEnergyStorage2 delegate) {
        this(() -> delegate);
    }

    /** 延迟解析控制器，避免能力查询在区块加载等嵌套路径中同步初始化控制器。 */
    public CompactEnergySink(Supplier<? extends IWideEnergyStorage2> delegate) {
        super(delegate);
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        if (this.isReleased() || maxReceive <= 0) {
            return 0;
        }
        final IWideEnergyStorage2 delegate = this.delegateForSubclass();
        if (delegate == null) {
            return 0;
        }
        final OperationMode mode = simulate ? OperationMode.Simulate : OperationMode.Execute;
        final long inserted = delegate
                .insertEnergy(EnergySystem.ForgeEnergy, WideAmount.from(maxReceive), mode)
                .longValue();
        return inserted > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) inserted;
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public boolean canReceive() {
        return !this.isReleased();
    }
}
