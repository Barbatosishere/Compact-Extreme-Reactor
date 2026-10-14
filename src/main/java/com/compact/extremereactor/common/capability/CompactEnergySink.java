package com.compact.extremereactor.common.capability;

import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import it.zerono.mods.zerocore.lib.energy.IWideEnergyStorage2;

import java.util.function.Supplier;

/** 流化器的 FE 输入适配器，禁止外部提取。 */
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
        final IWideEnergyStorage2 delegate = this.delegate();
        if (delegate == null) {
            return 0;
        }
        final int accepted = Math.min(maxReceive, clampToInt(delegate
                .insertEnergy(EnergySystem.ForgeEnergy, WideAmount.from(maxReceive), OperationMode.Simulate)
                .longValue()));
        if (simulate || accepted == 0) {
            return accepted;
        }
        return clampToInt(delegate
                .insertEnergy(EnergySystem.ForgeEnergy, WideAmount.from(accepted), OperationMode.Execute)
                .longValue());
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
