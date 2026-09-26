package com.compact.extremereactor.common.block;

import com.compact.extremereactor.common.menu.CompactFluidizerMenu;
import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.tile.CompactFluidizerTileEntity;
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
import net.neoforged.neoforge.fluids.FluidUtil;

/**
 * 压缩流化器方块：持有 {@link CompactFluidizerTileEntity}，右键打开流化器 GUI
 * 或直接注入固体原料（手持流化器配方原料右键方块时）。
 *
 * <p>流体桶/流体容器右键由 {@link FluidUtil} 优先处理（注入进料罐），
 * 不与固体原料注入冲突。</p>
 */
public class CompactFluidizerBlock extends CompactMachineBlock {

    public CompactFluidizerBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CompactFluidizerTileEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof CompactFluidizerTileEntity tile) {
            player.openMenu(new SimpleMenuProvider(
                    (id, inventory, p) -> new CompactFluidizerMenu(id, inventory, tile),
                    Component.translatable("block.compactextremereactor.compact_fluidizer")));
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hitResult) {
        // 流体容器优先：桶/罐注入流体进料罐（FluidUtil 内部含客户端预测）
        if (FluidUtil.interactWithFluidHandler(player, hand, level, pos, hitResult.getDirection())) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        // 客户端分流与服务端一致：手持固体原料 → CONSUME（挥动动画），否则打开 GUI
        if (level.isClientSide()) {
            if (CompactFluidizerController.isValidSolidIngredient(stack)) {
                return ItemInteractionResult.CONSUME;
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof CompactFluidizerTileEntity tile)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // 手持固体原料 → 直接注入进料槽（先槽 0 后槽 1，与物品能力同序）
        if (CompactFluidizerController.isValidSolidIngredient(stack)) {
            final var controller = tile.getController();
            if (!(controller instanceof CompactFluidizerController fluidizer)) {
                player.displayClientMessage(Component.translatable(
                        tile.isControllerInitFailed()
                                ? "gui.compactextremereactor.init_failed"
                                : "gui.compactextremereactor.controller_initializing"), true);
                return ItemInteractionResult.CONSUME;
            }
            // 底层 ItemStackHolder.insertItem 是槽位严格的：槽 0 被异种物品占用时不会
            // 自动落入槽 1（SolidMixing 需要两槽分别装不同原料），因此逐槽尝试。
            // 且容量派生自槽内现有物品（空槽先收 1 个），需在同一槽反复重试直到不再有进展
            // （与管道逐 tick 重试等价，一次右键即可装满配方所需数量）。
            ItemStack remaining = stack.copy();
            for (int slot = 0; slot < 2 && !remaining.isEmpty(); slot++) {
                ItemStack previous;
                do {
                    previous = remaining;
                    remaining = fluidizer.getItemInputs().insertItem(slot, remaining, false);
                } while (!remaining.isEmpty() && remaining.getCount() < previous.getCount());
            }
            if (remaining.getCount() < stack.getCount()) {
                final int inserted = stack.getCount() - remaining.getCount();
                stack.setCount(remaining.getCount());
                tile.setChanged();
                player.displayClientMessage(Component.translatable(
                        "gui.compactextremereactor.fluidizer_ingredient_inserted", inserted), true);
                return ItemInteractionResult.CONSUME;
            }
            player.displayClientMessage(Component.translatable(
                    "gui.compactextremereactor.fluidizer_ingredient_full"), true);
            return ItemInteractionResult.CONSUME;
        }

        // 非原料物品 → 打开 GUI
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new CompactFluidizerMenu(id, inventory, tile),
                Component.translatable("block.compactextremereactor.compact_fluidizer")));
        return ItemInteractionResult.SUCCESS;
    }
}
