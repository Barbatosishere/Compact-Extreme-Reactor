package com.compact.extremereactor.common.capability;

import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.tile.CompactFluidizerTileEntity;
import it.zerono.mods.zerocore.lib.item.inventory.ItemStackHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 压缩流化器物品能力：两个固体进料槽（对应真实多方块的两个固体注入器）。
 *
 * <p>槽位语义与 ER2 注入器一致：只接受流化器配方匹配的物品（Solid / SolidMixing），
 * 插入与提取均允许（玩家/管道可以取回放错的原料）。底层存储是控制器内的
 * {@link ItemStackHolder}，本类只做 fail-closed 包装（控制器释放后拒绝一切访问）。</p>
 */
public class CompactFluidizerItemHandler implements IItemHandler {

    private final CompactFluidizerTileEntity _tile;
    private volatile boolean _released;

    public CompactFluidizerItemHandler(CompactFluidizerTileEntity tile) {
        this._tile = tile;
    }

    /** 使区块卸载或方块移除前已分发的旧能力引用 fail-closed。 */
    public void release() {
        this._released = true;
    }

    @Nullable
    private ItemStackHolder holder() {
        if (this._released) {
            return null;
        }
        return this._tile.getController() instanceof CompactFluidizerController fluidizer
                ? fluidizer.getItemInputs()
                : null;
    }

    @Override
    public int getSlots() {
        return this.holder() == null ? 0 : 2;
    }

    @NotNull
    @Override
    public ItemStack getStackInSlot(int slot) {
        final ItemStackHolder holder = this.holder();
        if (holder == null || slot < 0 || slot >= 2) {
            return ItemStack.EMPTY;
        }
        return holder.getStackInSlot(slot);
    }

    @NotNull
    @Override
    public ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
        final ItemStackHolder holder = this.holder();
        if (holder == null || slot < 0 || slot >= 2 || stack.isEmpty()) {
            return stack;
        }
        final ItemStack remainder = holder.insertItem(slot, stack, simulate);
        if (!simulate && remainder.getCount() != stack.getCount()) {
            this._tile.setChanged();
        }
        return remainder;
    }

    @NotNull
    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        final ItemStackHolder holder = this.holder();
        if (holder == null || slot < 0 || slot >= 2 || amount <= 0) {
            return ItemStack.EMPTY;
        }
        final ItemStack extracted = holder.extractItem(slot, amount, simulate);
        if (!simulate && !extracted.isEmpty()) {
            this._tile.setChanged();
        }
        return extracted;
    }

    @Override
    public int getSlotLimit(int slot) {
        final ItemStackHolder holder = this.holder();
        if (holder == null || slot < 0 || slot >= 2) {
            return 0;
        }
        return holder.getSlotLimit(slot);
    }

    @Override
    public boolean isItemValid(int slot, @NotNull ItemStack stack) {
        final ItemStackHolder holder = this.holder();
        if (holder == null || slot < 0 || slot >= 2) {
            return false;
        }
        return holder.isItemValid(slot, stack);
    }
}
