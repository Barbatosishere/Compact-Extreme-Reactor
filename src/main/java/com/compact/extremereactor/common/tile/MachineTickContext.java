package com.compact.extremereactor.common.tile;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.IdentityHashMap;
import java.util.Map;

/** 一次 tick 派发内按世界和区块共享运行许可；不跨 tick 保留缓存。 */
public final class MachineTickContext {

    private final Map<Level, Long2ByteOpenHashMap> _chunks = new IdentityHashMap<>();

    public boolean shouldTick(Level level, BlockPos position) {
        final Long2ByteOpenHashMap chunks = this._chunks.computeIfAbsent(level, ignored -> new Long2ByteOpenHashMap());
        final long key = ChunkPos.asLong(position.getX() >> 4, position.getZ() >> 4);
        byte state = chunks.get(key);
        if (state == 0) {
            state = (byte) (level.isLoaded(position) && level.shouldTickBlocksAt(position) ? 2 : 1);
            chunks.put(key, state);
        }
        return state == 2;
    }
}
