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
import net.minecraftforge.fluids.FluidUtil;

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
        // 1.20.1 的 use() 无 PASS_TO_DEFAULT_BLOCK_INTERACTION 等效路径：客户端返回 PASS 会继续
        // 执行物品使用/方块放置（如手持方块右键会误放置），因此对燃料注入与打开 GUI 一律
        // 返回 SUCCESS（挥动动画）。GUI 由服务端 openMenu 驱动，客户端仅做动画裁决。
        if (FluidUtil.interactWithFluidHandler(player, hand, level, pos, hitResult.getDirection())) {
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof CompactReactorTileEntity tile)) return InteractionResult.PASS;

        // 手持燃料物品 → 直接注入反应堆（无需打开 GUI）
        // 缓存 mapping，避免 getFromSolid 的 Optional 被重复求值
        ItemStack held = player.getItemInHand(hand);
        final var mappingOpt = ReactantMappingsRegistry.getFromSolid(held);
        if (mappingOpt.isPresent() && mappingOpt.get().getProduct().getType().isFuel()) {
            final var mapping = mappingOpt.get();
            // 防御：第三方 mod 可能注册非法 mapping（sourceAmount=0 → 除零崩溃；productAmount<=0 → 死循环）。
            // 提示后落入末尾的 GUI 路径——1.20.1 服务端返回 PASS 会继续物品放置/交互流程，行为不可控
            final boolean invalidMapping = mapping.getSourceAmount() <= 0 || mapping.getProductAmount() <= 0;
            if (invalidMapping) {
                player.displayClientMessage(Component.translatable(
                        "gui.compactextremereactor.invalid_fuel_mapping"), true);
            } else if (held.getCount() >= mapping.getSourceAmount()) {
                var controller = tile.getController();
                if (!(controller instanceof CompactReactorController reactor)) {
                    player.displayClientMessage(Component.translatable(
                            tile.isControllerInitFailed()
                                    ? "gui.compactextremereactor.init_failed"
                                    : "gui.compactextremereactor.controller_initializing"), true);
                    return InteractionResult.SUCCESS;
                }
                final int productAmount = mapping.getProductAmount();
                final int sourceAmount = mapping.getSourceAmount();
                final int availableBatches = held.getCount() / sourceAmount;
                final int requestedBatches = Math.min(availableBatches, Integer.MAX_VALUE / productAmount);
                final int requestedFuel = requestedBatches * productAmount;
                final int acceptedFuel = reactor.insertFuel(
                        mapping.getProduct(), requestedFuel, OperationMode.Simulate);
                final int acceptedBatches = Math.min(
                        requestedBatches, Math.max(0, acceptedFuel) / productAmount);
                if (acceptedBatches <= 0) {
                    player.displayClientMessage(Component.translatable(
                            "gui.compactextremereactor.fuel_full"), true);
                    return InteractionResult.SUCCESS;
                }

                final int insertedFuel = reactor.insertFuel(
                        mapping.getProduct(), acceptedBatches * productAmount, OperationMode.Execute);
                final int insertedBatches = Math.min(
                        acceptedBatches, Math.max(0, insertedFuel) / productAmount);
                if (insertedBatches <= 0) {
                    player.displayClientMessage(Component.translatable(
                            "gui.compactextremereactor.fuel_full"), true);
                    return InteractionResult.SUCCESS;
                }

                final int injected = insertedBatches * sourceAmount;
                held.shrink(injected);
                tile.setChanged();
                player.displayClientMessage(Component.translatable(
                        insertedBatches < availableBatches
                                ? "gui.compactextremereactor.fuel_inserted_then_full"
                                : "gui.compactextremereactor.fuel_inserted",
                        injected), true);
                return InteractionResult.SUCCESS;
            }
        }

        // 非燃料物品 → 打开 GUI
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new CompactReactorMenu(id, inventory, tile),
                Component.translatable("block.compactextremereactor.compact_reactor")));
        return InteractionResult.SUCCESS;
    }
}