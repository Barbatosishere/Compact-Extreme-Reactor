package com.compact.extremereactor.common.block;

import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 压缩机器方块基类：携带一个 TileEntity，每个游戏刻驱动其内部的多方块控制器模拟。
 */
public class CompactMachineBlock extends Block implements EntityBlock {

    public CompactMachineBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // 由子类覆写创建对应的 TileEntity
        return null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // 方块 ticker 已弃用：机器 tick 由 AbstractCompactMachineTileEntity 注册的
        // TickEvent.ServerTickEvent 全局事件统一驱动（见该类的 TICKING_MACHINES）。
        // 若仍返回非 null ticker，读档（chunk 加载）时会同时注册方块 ticker，
        // 导致机器被 tick 两次（双倍发电/燃料消耗）。
        return null;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
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
