package com.compact.extremereactor.common.capability;

import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import it.zerono.mods.zerocore.lib.energy.IWideEnergyStorage2;
import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * 将 ZeroCore 的 {@link IWideEnergyStorage2}（ER 控制器）适配为 NeoForge 的
 * {@link IEnergyStorage} 能力，供能量线缆 / 相邻机器提取功率。
 *
 * 压缩机器是发电机：只允许提取（extract），不接受输入（receive）。
 * 注意 IEnergyStorage 以 int 为单位，ER 内部是 64 位 WideAmount，
 * 超出 int 范围的部分会被截断（实际游戏中单个方块能量容量远小于 2^31 的极少见）。
 */
public class CompactEnergyStorage implements IEnergyStorage {

    private final IWideEnergyStorage2 _delegate;

    public CompactEnergyStorage(IWideEnergyStorage2 delegate) {
        this._delegate = delegate;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        // 发电机不接受能量输入
        return 0;
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        if (maxExtract <= 0) {
            return 0;
        }
        final OperationMode mode = simulate ? OperationMode.Simulate : OperationMode.Execute;
        final long extracted = this._delegate
                .extractEnergy(EnergySystem.ForgeEnergy, WideAmount.from(maxExtract), mode)
                .longValue();
        // 防止 long 超出 int 范围时截断
        return extracted > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) extracted;
    }

    @Override
    public int getEnergyStored() {
        final long energy = this._delegate.getEnergyStored(EnergySystem.ForgeEnergy).longValue();
        return energy > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) energy;
    }

    @Override
    public int getMaxEnergyStored() {
        final long capacity = this._delegate.getCapacity(EnergySystem.ForgeEnergy).longValue();
        return capacity > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) capacity;
    }

    @Override
    public boolean canExtract() {
        return true;
    }

    @Override
    public boolean canReceive() {
        return false;
    }
}
