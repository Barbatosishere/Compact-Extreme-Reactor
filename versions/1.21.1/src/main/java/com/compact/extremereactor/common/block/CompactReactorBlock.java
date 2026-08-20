package com.compact.extremereactor.common.block;

import com.compact.extremereactor.common.menu.CompactReactorMenu;
import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import it.zerono.mods.extremereactors.api.reactor.ReactantMappingsRegistry;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 压缩极限反应堆方块：持有 {@link CompactReactorTileEntity}，右键打开反应堆 GUI
 * 或直接注入燃料（手持燃料物品右键方块时）。
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
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CompactReactorTileEntity tile) {
            player.openMenu(new SimpleMenuProvider(
                    (id, inventory, p) -> new CompactReactorMenu(id, inventory, tile),
                    Component.translatable("block.compactextremereactor.compact_reactor")));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide()) return ItemInteractionResult.CONSUME;
        if (!(level.getBlockEntity(pos) instanceof CompactReactorTileEntity tile)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        // 手持燃料物品 → 直接注入反应堆（无需打开 GUI）
        var mapping = ReactantMappingsRegistry.getFromSolid(stack);
        if (mapping.isPresent() && stack.getCount() >= mapping.get().getSourceAmount()) {
            var controller = tile.getController();
            if (controller instanceof CompactReactorController reactor) {
                final int productAmount = mapping.get().getProductAmount();
                if (reactor.insertFuel(mapping.get().getProduct(), productAmount, OperationMode.Simulate) >= productAmount) {
                    reactor.insertFuel(mapping.get().getProduct(), productAmount, OperationMode.Execute);
                    stack.shrink(mapping.get().getSourceAmount());
                    tile.setChanged();
                    return ItemInteractionResult.CONSUME;
                }
            }
        }

        // 非燃料物品 → 打开 GUI
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new CompactReactorMenu(id, inventory, tile),
                Component.translatable("block.compactextremereactor.compact_reactor")));
        return ItemInteractionResult.SUCCESS;
    }
}