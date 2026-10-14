package com.compact.extremereactor.common.capability;

import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.tile.CompactFluidizerTileEntity;
import it.zerono.mods.zerocore.lib.item.inventory.ItemStackHolder;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** 两个固体原料槽的物品能力；控制器释放后旧引用拒绝访问。 */
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
        return this._released ? 0 : 2;
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
