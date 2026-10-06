package com.compact.extremereactor.common.multiblock;

import it.zerono.mods.zerocore.lib.data.WideAmount;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.function.Function;

public final class GeneratorEnergyPersistence {

    private GeneratorEnergyPersistence() {
    }

    public static CompoundTag withCapacity(CompoundTag tag, WideAmount capacity, Function<WideAmount, Tag> serialize) {
        return withCapacity(tag, "buffer", capacity, serialize);
    }

    public static CompoundTag withCapacity(CompoundTag tag, String bufferKey, WideAmount capacity,
                                            Function<WideAmount, Tag> serialize) {
        if (!tag.contains(bufferKey, Tag.TAG_COMPOUND)) {
            if (!tag.contains(bufferKey)) {
                return tag;
            }
            final CompoundTag restored = tag.copy();
            restored.remove(bufferKey);
            return restored;
        }
        final CompoundTag restored = tag.copy();
        final CompoundTag buffer = restored.getCompound(bufferKey);
        if (!buffer.getBoolean("wide") || !isWideAmount(buffer, "energy")
                || !isWideAmount(buffer, "capacity") || !isWideAmount(buffer, "maxInsert")
                || !isWideAmount(buffer, "maxExtract")) {
            buffer.put("energy", normalizeAmount(buffer, "energy", serialize));
            buffer.put("maxInsert", normalizeAmount(buffer, "maxInsert", serialize));
            buffer.put("maxExtract", normalizeAmount(buffer, "maxExtract", serialize));
            buffer.putByte("wide", (byte) 1);
        }
        buffer.put("capacity", serialize.apply(capacity));
        return restored;
    }

    private static boolean isWideAmount(CompoundTag buffer, String key) {
        if (!buffer.contains(key, Tag.TAG_COMPOUND)) {
            return false;
        }
        final CompoundTag amount = buffer.getCompound(key);
        return amount.contains("i", Tag.TAG_LONG) && amount.contains("d", Tag.TAG_SHORT);
    }

    private static Tag normalizeAmount(CompoundTag buffer, String key,
                                       Function<WideAmount, Tag> serialize) {
        if (isWideAmount(buffer, key)) {
            return buffer.getCompound(key).copy();
        }
        final double value = buffer.contains(key, Tag.TAG_ANY_NUMERIC) ? buffer.getDouble(key) : 0.0d;
        return serialize.apply(Double.isFinite(value) && value > 0.0d
                ? WideAmount.from(value)
                : WideAmount.ZERO);
    }
}
