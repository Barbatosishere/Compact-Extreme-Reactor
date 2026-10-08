package com.compact.extremereactor.common.multiblock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class FixedSlotInventoryPersistence {

    private FixedSlotInventoryPersistence() {
    }

    public static CompoundTag withSlots(CompoundTag tag, String inventoryKey, int slots) {
        if (!needsNormalization(tag, inventoryKey, slots)) {
            return tag;
        }
        final CompoundTag restored = tag.copy();
        normalizeInPlace(restored, inventoryKey, slots);
        return restored;
    }

    static boolean needsNormalization(CompoundTag tag, String inventoryKey, int slots) {
        if (!tag.contains(inventoryKey)) {
            return false;
        }
        if (!tag.contains(inventoryKey, Tag.TAG_COMPOUND)) {
            return true;
        }
        final CompoundTag inventory = tag.getCompound(inventoryKey);
        final int savedSlots = inventory.getInt("Size");
        if (savedSlots > 0 && savedSlots != slots) {
            return true;
        }
        final ListTag items = inventory.getList("Items", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            if (!isValidSlot(items.getCompound(index), slots)) {
                return true;
            }
        }
        return false;
    }

    static void normalizeInPlace(CompoundTag tag, String inventoryKey, int slots) {
        if (!tag.contains(inventoryKey, Tag.TAG_COMPOUND)) {
            tag.remove(inventoryKey);
            return;
        }
        final CompoundTag inventory = tag.getCompound(inventoryKey);
        final int savedSlots = inventory.getInt("Size");
        if (savedSlots > 0 && savedSlots != slots) {
            inventory.putInt("Size", slots);
        }
        final ListTag items = inventory.getList("Items", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            if (!isValidSlot(items.getCompound(index), slots)) {
                final ListTag validItems = new ListTag();
                for (int restoredIndex = 0; restoredIndex < items.size(); restoredIndex++) {
                    final CompoundTag item = items.getCompound(restoredIndex);
                    if (isValidSlot(item, slots)) {
                        validItems.add(item);
                    }
                }
                inventory.put("Items", validItems);
                break;
            }
        }
    }

    private static boolean isValidSlot(CompoundTag item, int slots) {
        if (!item.contains("Slot", Tag.TAG_ANY_NUMERIC)) {
            return false;
        }
        final double slot = item.getDouble("Slot");
        return slot >= 0 && slot < slots && slot == Math.floor(slot);
    }
}
