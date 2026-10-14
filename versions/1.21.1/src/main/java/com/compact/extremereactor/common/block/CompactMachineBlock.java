package com.compact.extremereactor.common.block;

import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 压缩机器方块基类：携带一个 TileEntity，每个游戏刻驱动其内部的多方块控制器模拟。 */
public abstract class CompactMachineBlock extends Block implements EntityBlock {

    public CompactMachineBlock(Properties properties) {
        super(properties);
    }

    // EntityBlock 的默认 ticker 返回 null；机器由全局服务端 tick 驱动。

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // 方块被破坏时，通知 TileEntity 释放控制器（防止遗留脏数据）
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof AbstractCompactMachineTileEntity machine) {
                machine.onBlockRemoved();
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
