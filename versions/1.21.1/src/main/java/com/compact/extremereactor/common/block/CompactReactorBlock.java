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
import net.neoforged.neoforge.fluids.FluidUtil;

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
        // 客户端分流逻辑与服务端一致：手持燃料物品 → 返回 CONSUME（挥动动画），
        // 非燃料物品 → PASS_TO_DEFAULT（不挥动，服务端走 openMenu 打开 GUI 的视觉语义一致）
        if (FluidUtil.interactWithFluidHandler(player, hand, level, pos, hitResult.getDirection())) {
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        if (level.isClientSide()) {
            var mappingOpt = ReactantMappingsRegistry.getFromSolid(stack);
            // 与服务端的非法 mapping 防御保持一致（source/product<=0 → 服务端走 GUI 路径），
            // 否则客户端预测 CONSUME（挥动）而服务端实际打开 GUI，动画与结果不一致
            if (mappingOpt.isPresent() && mappingOpt.get().getProduct().getType().isFuel()
                    && mappingOpt.get().getSourceAmount() > 0
                    && mappingOpt.get().getProductAmount() > 0
                    && stack.getCount() >= mappingOpt.get().getSourceAmount()) {
                return ItemInteractionResult.CONSUME;
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof CompactReactorTileEntity tile)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;

        // 手持燃料物品 → 直接注入反应堆（无需打开 GUI）
        // 缓存 mapping，避免 getFromSolid(stack) 的 Optional 被重复求值
        final var mappingOpt = ReactantMappingsRegistry.getFromSolid(stack);
        if (mappingOpt.isPresent() && mappingOpt.get().getProduct().getType().isFuel()) {
            final var mapping = mappingOpt.get();
            // 防御：第三方 mod 可能注册非法 mapping（sourceAmount=0 → 除零崩溃；productAmount<=0 → 死循环）
            // 拒绝并走 GUI 路径（让玩家改用其他燃料物品）
            if (mapping.getSourceAmount() <= 0 || mapping.getProductAmount() <= 0) {
                player.displayClientMessage(Component.translatable(
                        "gui.compactextremereactor.invalid_fuel_mapping"), true);
                return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            }
            if (stack.getCount() >= mapping.getSourceAmount()) {
                var controller = tile.getController();
                if (!(controller instanceof CompactReactorController reactor)) {
                    player.displayClientMessage(Component.translatable(
                            tile.isControllerInitFailed()
                                    ? "gui.compactextremereactor.init_failed"
                                    : "gui.compactextremereactor.controller_initializing"), true);
                    return ItemInteractionResult.CONSUME;
                }
                final int productAmount = mapping.getProductAmount();
                final int sourceAmount = mapping.getSourceAmount();
                final int availableBatches = Math.min(stack.getCount() / sourceAmount, 64);
                final int requestedBatches = Math.min(availableBatches, Integer.MAX_VALUE / productAmount);
                final int requestedFuel = requestedBatches * productAmount;
                final int acceptedFuel = reactor.insertFuel(
                        mapping.getProduct(), requestedFuel, OperationMode.Simulate);
                final int acceptedBatches = Math.min(
                        requestedBatches, Math.max(0, acceptedFuel) / productAmount);
                if (acceptedBatches <= 0) {
                    player.displayClientMessage(Component.translatable(
                            "gui.compactextremereactor.fuel_full"), true);
                    return ItemInteractionResult.CONSUME;
                }

                final int insertedFuel = reactor.insertFuel(
                        mapping.getProduct(), acceptedBatches * productAmount, OperationMode.Execute);
                final int insertedBatches = Math.min(
                        acceptedBatches, Math.max(0, insertedFuel) / productAmount);
                if (insertedBatches <= 0) {
                    player.displayClientMessage(Component.translatable(
                            "gui.compactextremereactor.fuel_full"), true);
                    return ItemInteractionResult.CONSUME;
                }

                final int injected = insertedBatches * sourceAmount;
                stack.shrink(injected);
                tile.setChanged();
                player.displayClientMessage(Component.translatable(
                        insertedBatches < availableBatches
                                ? "gui.compactextremereactor.fuel_inserted_then_full"
                                : "gui.compactextremereactor.fuel_inserted",
                        injected), true);
                return ItemInteractionResult.CONSUME;
            }
        }

        // 非燃料物品 → 打开 GUI
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new CompactReactorMenu(id, inventory, tile),
                Component.translatable("block.compactextremereactor.compact_reactor")));
        return ItemInteractionResult.SUCCESS;
    }
}