package com.compact.extremereactor.common.multiblock;

import it.zerono.mods.extremereactors.gamecontent.multiblock.reactor.IIrradiationSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.function.Supplier;

/**
 * 模拟辐射源：替代真实多方块中的燃料棒。
 *
 * ER 的 ReactorLogic.performIrradiation() 每次更新从
 * MultiblockReactor.getNextIrradiationSource() 获取一个辐射源来计算燃料消耗、
 * 热量与功率。单方块模拟没有真实燃料棒，因此提供本实现，仅暴露：
 *   - 控制棒插入比例（由压缩方块的"模拟控制棒"状态提供，可调）
 *   - 辐射方向（全部六个方向）
 *   - 位置（锚点方块自身）
 */
public class SimulatedIrradiationSource implements IIrradiationSource {

    /** 模拟控制棒插入比例的提供者（0-100，由控制器/配置控制）。 */
    private final Supplier<Byte> _controlRodInsertionRatio;

    private final BlockPos _position;

    public SimulatedIrradiationSource(Supplier<Byte> controlRodInsertionRatio, BlockPos position) {
        this._controlRodInsertionRatio = controlRodInsertionRatio;
        this._position = position;
    }

    @Override
    public byte getControlRodInsertionRatio() {
        return this._controlRodInsertionRatio.get();
    }

    @Override
    public Direction[] getIrradiationDirections() {
        return Direction.values();
    }

    @Override
    public boolean isLinked() {
        // 模拟源始终"已链接"，保证辐射逻辑正常运行
        return true;
    }

    @Override
    public BlockPos getWorldPosition() {
        return this._position;
    }
}
