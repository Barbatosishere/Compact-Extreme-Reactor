package com.compact.extremereactor.common.multiblock;

import it.zerono.mods.extremereactors.gamecontent.multiblock.common.IFluidContainer;
import it.zerono.mods.zerocore.lib.data.IoDirection;
import it.zerono.mods.zerocore.lib.data.nbt.ISyncableEntity;
import it.zerono.mods.zerocore.lib.energy.IWideEnergyStorage2;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.util.Optional;

/**
 * 压缩机器模拟控制器的统一访问接口。
 *
 * 由 {@link CompactReactorController} 与 {@link CompactTurbineController} 实现，
 * 屏蔽两类控制器（反应堆/涡轮机）的差异，让 TileEntity 以一致的方式：
 *   - 驱动模拟（simulateAssembly / tick）
 *   - 读写 NBT（syncDataFrom / syncDataTo，与 ER 存档格式一致）
 *   - 暴露能量（继承 IWideEnergyStorage2）与流体（getFluidHandler）
 */
public interface ICompactController extends IWideEnergyStorage2 {

    /** 模拟多方块"装配"：初始化能量/流体/燃料容量。 */
    void simulateAssembly();

    /** 驱动一游戏刻的机器逻辑。 */
    void tick();

    /** 设置机器激活状态（是否运行）。 */
    void setMachineActive(boolean active);

    /** 机器当前是否处于激活（运行）状态。 */
    boolean isMachineActive();

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
}
