package com.compact.extremereactor.common.multiblock;

import it.zerono.mods.extremereactors.gamecontent.multiblock.common.IFluidContainer;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import it.zerono.mods.zerocore.lib.data.WideAmount;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import it.zerono.mods.zerocore.lib.energy.EnergySystem;
import it.zerono.mods.zerocore.lib.energy.IWideEnergyStorage2;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.util.Optional;

/** 三类压缩机器的模拟、存档、能量和流体接口。 */
public interface ICompactController extends IWideEnergyStorage2 {

    /** 模拟多方块"装配"：初始化能量/流体/燃料容量。 */
    void simulateAssembly();

    /** 驱动一游戏刻的机器逻辑。 */
    void tick();

    void setStructureReady(java.util.function.BooleanSupplier ready);

    /** 设置机器激活状态（是否运行）。 */
    void setMachineActive(boolean active);

    /** 机器当前是否处于激活（运行）状态。 */
    boolean isMachineActive();

    /** 满仓暂停产能，不改变玩家开关状态；抽出能量后自动恢复。 */
    default boolean isEnergyBufferFull() {
        final WideAmount capacity = this.getCapacity(EnergySystem.ForgeEnergy);
        if (capacity.isZero()) {
            return false;
        }
        return this.getEnergyStored(EnergySystem.ForgeEnergy).greaterOrEqual(capacity);
    }

    /** 从 NBT 恢复控制器数据。 */
    void syncDataFrom(CompoundTag tag, ISyncableEntity.SyncReason reason);

    /** 将控制器数据写入 NBT。 */
    CompoundTag syncDataTo(CompoundTag tag, ISyncableEntity.SyncReason reason);

    /** 获取指定方向的流体处理器：Input=进料口，Output=出料口。 */
    Optional<IFluidHandler> getFluidHandler(IoDirection direction);

    /** 获取机器的流体容器（冷却剂/蒸汽等），供 UI 显示。 */
    IFluidContainer getFluidContainer();

    /** 燃料总容量（反应堆）；非反应堆返回 0。 */
    default int getFuelCapacity() {
        return 0;
    }

    /** 当前燃料量（反应堆）；非反应堆返回 0。 */
    default int getFuelAmount() {
        return 0;
    }

    /** 当前核废料量（反应堆）；非反应堆返回 0。 */
    default int getWasteAmount() {
        return 0;
    }

    /** 最近一游戏刻发电量（涡轮机）；其他机器返回 0。 */
    default double getEnergyGeneratedLastTick() {
        return 0;
    }

    /** 反应堆堆芯温度（摄氏度，开氏温标转换后）；非反应堆返回 0。 */
    default double getReactorTemperatureCelsius() {
        return 0;
    }

    /** 涡轮机转子转速（弧度/秒）；非涡轮机返回 0。 */
    default double getRotorAngularSpeed() {
        return 0;
    }

    /** 涡轮机转子最大安全转速（弧度/秒）；非涡轮机返回 0。 */
    default double getMaxRotorAngularSpeed() {
        return 0;
    }

    /** 注册能力变更后的即时保存回调；无需回调的控制器可保持默认实现。 */
    default void setFluidDirtyCallback(Runnable callback) {
    }

    /** 释放控制器缓存的外部流体 handler，防止卸载后旧引用访问 detached 容器。 */
    default void releaseFluidHandlers() {
    }
}
