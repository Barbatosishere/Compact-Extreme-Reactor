package com.compact.extremereactor.common.capability;

import com.compact.extremereactor.common.multiblock.CompactReactorController;
import com.compact.extremereactor.common.tile.CompactReactorTileEntity;
import it.zerono.mods.extremereactors.api.IMapping;
import it.zerono.mods.extremereactors.api.reactor.Reactant;
import it.zerono.mods.extremereactors.api.reactor.ReactantMappingsRegistry;
import it.zerono.mods.zerocore.lib.data.stack.OperationMode;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * 反应堆物品能力：允许管道接入燃料输入与废物输出。
 *
 * 槽位布局：
 *  - 槽 0：燃料输入（插入燃料物品 → 注入反应堆）
 *  - 槽 1：废物输出（废物 Reactant → 反向映射为物品提取）
 *
 * 燃料/废物以 Reactant 流体形式存储于控制器，无内部物品堆。
 * 槽 1 的 {@link #getStackInSlot(int)} 返回废物物品预览堆，供管道探测。
 */
public class CompactReactorItemHandler implements IItemHandler {

    private static final int SLOT_FUEL_IN = 0;
    private static final int SLOT_WASTE_OUT = 1;

    private final CompactReactorTileEntity _tile;
    private volatile boolean _released;

    public CompactReactorItemHandler(CompactReactorTileEntity tile) {
        this._tile = tile;
    }

    /** 使区块卸载或方块移除前已分发的旧能力引用 fail-closed。 */
    public void release() {
        this._released = true;
    }

    private CompactReactorController getReactor() {
        return !this._released && this._tile.getController() instanceof CompactReactorController reactor
                ? reactor
                : null;
    }

    @Override
    public int getSlots() {
        return this._released ? 0 : 2;
    }

    @NotNull
    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot != SLOT_WASTE_OUT) {
            return ItemStack.EMPTY;
        }
        final CompactReactorController reactor = this.getReactor();
        if (reactor == null || reactor.getWasteAmount() <= 0) {
            return ItemStack.EMPTY;
        }
        final IMapping<Reactant, TagKey<net.minecraft.world.item.Item>> map = this.findWasteMapping(reactor);
        return map == null
                ? ItemStack.EMPTY
                : this.createWasteStack(map, reactor.getWasteAmount(), Integer.MAX_VALUE);
    }

    /**
     * 选择单个物品堆能转换掉最多废物的合法映射。预览和提取共用该稳定选择，
     * 因此管道探测到的物品类型与实际提取一致。
     */
    @Nullable
    private IMapping<Reactant, TagKey<net.minecraft.world.item.Item>> findWasteMapping(
            CompactReactorController reactor) {
        final Optional<List<IMapping<Reactant, TagKey<net.minecraft.world.item.Item>>>> mapsOpt =
                ReactantMappingsRegistry.getToSolid(reactor.getWasteReactant());
        if (mapsOpt.isEmpty() || mapsOpt.get().isEmpty()) {
            return null;
        }
        IMapping<Reactant, TagKey<net.minecraft.world.item.Item>> best = null;
        long bestWaste = 0;
        for (IMapping<Reactant, TagKey<net.minecraft.world.item.Item>> candidate : mapsOpt.get()) {
            final int sourceAmount = candidate.getSourceAmount();
            final int productAmount = candidate.getProductAmount();
            if (sourceAmount <= 0 || productAmount <= 0) {
                continue;
            }
            final ItemStack sample = ReactantMappingsRegistry.getSolidStackFrom(candidate, 1);
            if (sample.isEmpty()) {
                continue;
            }
            final int batches = Math.min(reactor.getWasteAmount() / sourceAmount,
                    sample.getMaxStackSize() / productAmount);
            final long waste = (long) batches * sourceAmount;
            if (waste > bestWaste) {
                bestWaste = waste;
                best = candidate;
            }
        }
        return best;
    }

    private ItemStack createWasteStack(
            IMapping<Reactant, TagKey<net.minecraft.world.item.Item>> map,
            int availableWaste,
            int itemLimit) {
        final int sourceAmount = map.getSourceAmount();
        final int productAmount = map.getProductAmount();
        if (sourceAmount <= 0 || productAmount <= 0 || availableWaste < sourceAmount || itemLimit <= 0) {
            return ItemStack.EMPTY;
        }
        final ItemStack sample = ReactantMappingsRegistry.getSolidStackFrom(map, 1);
        if (sample.isEmpty()) {
            return ItemStack.EMPTY;
        }
        final int batches = Math.min(availableWaste / sourceAmount,
                Math.min(itemLimit, sample.getMaxStackSize()) / productAmount);
        return batches <= 0
                ? ItemStack.EMPTY
                : ReactantMappingsRegistry.getSolidStackFrom(map, batches * productAmount);
    }

    @NotNull
    @Override
    public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        if (slot != SLOT_FUEL_IN || stack.isEmpty()) {
            return stack;
        }
        final CompactReactorController reactor = this.getReactor();
        if (reactor == null) {
            return stack;
        }
        final Optional<IMapping<TagKey<net.minecraft.world.item.Item>, Reactant>> mappingOpt =
                ReactantMappingsRegistry.getFromSolid(stack);
        if (mappingOpt.isEmpty() || !mappingOpt.get().getProduct().getType().isFuel()) {
            return stack;
        }
        final IMapping<TagKey<net.minecraft.world.item.Item>, Reactant> mapping = mappingOpt.get();
        if (mapping.getSourceAmount() <= 0 || mapping.getProductAmount() <= 0) {
            return stack;
        }
        if (stack.getCount() < mapping.getSourceAmount()) {
            return stack;
        }
        final Reactant reactant = mapping.getProduct();
        final int sourceAmount = mapping.getSourceAmount();
        final int productAmount = mapping.getProductAmount();
        final int availableBatches = stack.getCount() / sourceAmount;
        final int requestedBatches = Math.min(
                availableBatches,
                Integer.MAX_VALUE / productAmount);
        if (requestedBatches <= 0) {
            return stack;
        }
        final int requestedFuel = requestedBatches * productAmount;
        final int acceptedFuel = reactor.insertFuel(
                reactant,
                requestedFuel,
                OperationMode.Simulate);
        final int acceptedBatches = Math.min(
                requestedBatches,
                Math.max(0, acceptedFuel) / productAmount);
        if (acceptedBatches <= 0) {
            return stack;
        }
        final int fuelToInsert = acceptedBatches * productAmount;
        final ItemStack remainder = stack.copy();
        remainder.shrink(acceptedBatches * sourceAmount);
        if (!simulate) {
            final int inserted = reactor.insertFuel(reactant, fuelToInsert, OperationMode.Execute);
            final int insertedBatches = Math.min(acceptedBatches, inserted / productAmount);
            remainder.setCount(stack.getCount() - insertedBatches * sourceAmount);
            if (insertedBatches > 0) {
                this._tile.setChanged();
            }
        }
        return remainder;
    }

    @NotNull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (slot != SLOT_WASTE_OUT || amount <= 0) {
            return ItemStack.EMPTY;
        }
        final CompactReactorController reactor = this.getReactor();
        if (reactor == null || reactor.getWasteAmount() <= 0) {
            return ItemStack.EMPTY;
        }
        final IMapping<Reactant, TagKey<net.minecraft.world.item.Item>> map = this.findWasteMapping(reactor);
        if (map == null) {
            return ItemStack.EMPTY;
        }
        final ItemStack result = this.createWasteStack(map, reactor.getWasteAmount(), amount);
        if (result.isEmpty() || simulate) {
            return result;
        }
        final int batches = result.getCount() / map.getProductAmount();
        final int wasteToRemove = batches * map.getSourceAmount();
        final int removed = reactor.voidWaste(wasteToRemove);
        final int removedBatches = Math.min(batches, Math.max(0, removed) / map.getSourceAmount());
        if (removedBatches <= 0) {
            return ItemStack.EMPTY;
        }
        this._tile.setChanged();
        return ReactantMappingsRegistry.getSolidStackFrom(map, removedBatches * map.getProductAmount());
    }

    @Override
    public int getSlotLimit(int slot) {
        if (this._released) {
            return 0;
        }
        if (slot != SLOT_WASTE_OUT) {
            return 64;
        }
        final CompactReactorController reactor = this.getReactor();
        final IMapping<Reactant, TagKey<net.minecraft.world.item.Item>> map =
                reactor == null ? null : this.findWasteMapping(reactor);
        if (map == null) {
            return 64;
        }
        final ItemStack oneItem = ReactantMappingsRegistry.getSolidStackFrom(map, 1);
        return oneItem.isEmpty() ? 64 : oneItem.getMaxStackSize();
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        if (this._released || slot != SLOT_FUEL_IN || stack.isEmpty()) {
            return false;
        }
        return ReactantMappingsRegistry.getFromSolid(stack)
                .filter(mapping -> mapping.getProduct().getType().isFuel())
                .filter(mapping -> mapping.getSourceAmount() > 0 && mapping.getProductAmount() > 0)
                .isPresent();
    }
}