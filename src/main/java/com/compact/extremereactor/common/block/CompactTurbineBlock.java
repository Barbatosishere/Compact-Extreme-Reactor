package com.compact.extremereactor.common.block;

import com.compact.extremereactor.common.menu.CompactTurbineMenu;
import com.compact.extremereactor.common.tile.CompactTurbineTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 压缩涡轮机方块：持有 {@link CompactTurbineTileEntity}，右键打开涡轮机 GUI。
 */
public class CompactTurbineBlock extends CompactMachineBlock {

    public CompactTurbineBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompactTurbineTileEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CompactTurbineTileEntity tile) {
            player.openMenu(new SimpleMenuProvider(
                    (id, inventory, p) -> new CompactTurbineMenu(id, inventory, tile),
                    Component.translatable("block.compactextremereactor.compact_turbine")));
        }
        return InteractionResult.SUCCESS;
    }
}
