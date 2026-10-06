package com.compact.extremereactor.common.multiblock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

public final class FixedSlotInventoryPersistence {

    private FixedSlotInventoryPersistence() {
    }

    public static CompoundTag withSlots(CompoundTag tag, String inventoryKey, int slots) {
        if (!tag.contains(inventoryKey, Tag.TAG_COMPOUND)) {
            if (!tag.contains(inventoryKey)) {
                return tag;
            }
            final CompoundTag restored = tag.copy();
            restored.remove(inventoryKey);
            return restored;
        }
        final CompoundTag inventory = tag.getCompound(inventoryKey);
        final int savedSlots = inventory.getInt("Size");
        CompoundTag restored = null;
        if (savedSlots > 0 && savedSlots != slots) {
            restored = tag.copy();
            restored.getCompound(inventoryKey).putInt("Size", slots);
        }
        final ListTag items = inventory.getList("Items", Tag.TAG_COMPOUND);
        for (int index = 0; index < items.size(); index++) {
            if (!isValidSlot(items.getCompound(index), slots)) {
                if (restored == null) {
                    restored = tag.copy();
                }
                final ListTag restoredItems = restored.getCompound(inventoryKey).getList("Items", Tag.TAG_COMPOUND);
                final ListTag validItems = new ListTag();
                for (int restoredIndex = 0; restoredIndex < restoredItems.size(); restoredIndex++) {
                    final CompoundTag item = restoredItems.getCompound(restoredIndex);
                    if (isValidSlot(item, slots)) {
                        validItems.add(item);
                    }
                }
                restored.getCompound(inventoryKey).put("Items", validItems);
                break;
            }
        }
        return restored == null ? tag : restored;
    }

    private static boolean isValidSlot(CompoundTag item, int slots) {
        if (!item.contains("Slot", Tag.TAG_ANY_NUMERIC)) {
            return false;
        }
        final double slot = item.getDouble("Slot");
        return slot >= 0 && slot < slots && slot == Math.floor(slot);
    }
}
