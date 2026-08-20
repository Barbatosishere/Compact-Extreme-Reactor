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
    public InteractionResult use(BlockState state, Level level, BlockPos pos,
                                 Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof CompactReactorTileEntity tile)) return InteractionResult.PASS;

        // 手持燃料物品 → 直接注入反应堆（无需打开 GUI）
        ItemStack held = player.getItemInHand(hand);
        var mapping = ReactantMappingsRegistry.getFromSolid(held);
        if (mapping.isPresent() && held.getCount() >= mapping.get().getSourceAmount()) {
            var controller = tile.getController();
            if (controller instanceof CompactReactorController reactor) {
                final int productAmount = mapping.get().getProductAmount();
                if (reactor.insertFuel(mapping.get().getProduct(), productAmount, OperationMode.Simulate) >= productAmount) {
                    reactor.insertFuel(mapping.get().getProduct(), productAmount, OperationMode.Execute);
                    held.shrink(mapping.get().getSourceAmount());
                    tile.setChanged();
                    return InteractionResult.SUCCESS;
                }
            }
        }

        // 非燃料物品 → 打开 GUI
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new CompactReactorMenu(id, inventory, tile),
                Component.translatable("block.compactextremereactor.compact_reactor")));
        return InteractionResult.SUCCESS;
    }
}