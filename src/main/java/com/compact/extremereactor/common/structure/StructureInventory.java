package com.compact.extremereactor.common.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.function.BiPredicate;

public final class StructureInventory extends SimpleContainer {
    public static final int SLOTS_PER_COMPONENT = 4;
    public static final int SLOT_COUNT = 16;
    private final StructureKind kind;

    public StructureInventory(StructureKind kind) {
        super(SLOT_COUNT);
        this.kind = kind;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot >= 0 && slot < SLOT_COUNT && this.kind.accepts(slot / SLOTS_PER_COMPONENT, stack);
    }

    public int countComponent(int component) {
        int count = 0;
        for (int slot = component * SLOTS_PER_COMPONENT; slot < (component + 1) * SLOTS_PER_COMPONENT; ++slot) {
            ItemStack stack = this.getItem(slot);
            if (this.kind.accepts(component, stack)) count += stack.getCount();
        }
        return count;
    }

    public void dropContents(Level level, BlockPos pos, BiPredicate<ItemStack, ItemStack> canMerge) {
        if (level.isClientSide) return;
        var drops = new ArrayList<ItemStack>();
        for (int slot = 0; slot < SLOT_COUNT; ++slot) {
            ItemStack stack = this.removeItemNoUpdate(slot);
            for (ItemStack drop : drops) {
                if (stack.isEmpty()) break;
                if (canMerge.test(drop, stack)) {
                    int count = Math.min(stack.getCount(), Math.min(drop.getMaxStackSize(), stack.getMaxStackSize()) - drop.getCount());
                    if (count > 0) {
                        drop.grow(count);
                        stack.shrink(count);
                    }
                    break;
                }
            }
            while (!stack.isEmpty()) drops.add(stack.split(Math.max(1, stack.getMaxStackSize())));
        }
        this.setChanged();
        double y = pos.getY() + (level.getBlockState(pos.above()).isAir() ? 1.01 : 0.5);
        for (ItemStack drop : drops) {
            var entity = new ItemEntity(level, pos.getX() + 0.5, y, pos.getZ() + 0.5, drop);
            entity.setDeltaMovement(0, 0.2, 0);
            level.addFreshEntity(entity);
        }
    }
}
