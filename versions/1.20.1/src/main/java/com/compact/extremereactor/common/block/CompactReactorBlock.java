package com.compact.extremereactor.common.block;

import com.compact.extremereactor.common.menu.CompactReactorMenu;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 压缩反应堆方块：持有 {@link CompactReactorTileEntity}，右键打开反应堆 GUI。
 */
public class CompactReactorBlock extends CompactMachineBlock {

    public CompactReactorBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompactReactorTileEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CompactReactorTileEntity tile) {
            player.openMenu(new SimpleMenuProvider(
                    (id, inventory, p) -> new CompactReactorMenu(id, inventory, tile),
                    Component.translatable("block.compactextremereactor.compact_reactor")));
        }
        return InteractionResult.SUCCESS;
    }
}
