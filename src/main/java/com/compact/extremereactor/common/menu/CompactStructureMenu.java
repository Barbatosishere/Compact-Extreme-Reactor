package com.compact.extremereactor.common.menu;

import com.compact.extremereactor.common.Content;
import com.compact.extremereactor.common.structure.StructureInventory;
import com.compact.extremereactor.common.structure.StructureKind;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public final class CompactStructureMenu extends AbstractCompactMachineMenu {
    private static final int DATA_COUNT = 20;
    @Nullable private final AbstractCompactMachineTileEntity tile;

    public CompactStructureMenu(int id, Inventory inventory) {
        this(id, inventory, null, new PackedContainerData(new SimpleContainerData(DATA_COUNT * 2), DATA_COUNT, false));
    }

    public CompactStructureMenu(int id, Inventory inventory, AbstractCompactMachineTileEntity tile) {
        this(id, inventory, tile, new PackedContainerData(new StructureData(tile), DATA_COUNT, true));
    }

    private CompactStructureMenu(int id, Inventory inventory, @Nullable AbstractCompactMachineTileEntity tile, PackedContainerData data) {
        super(Content.COMPACT_STRUCTURE_MENU.get(), id, data);
        this.tile = tile;
        var components = tile == null ? new SimpleContainer(StructureInventory.SLOT_COUNT) : tile.getStructureInventory();
        for (int i = 0; i < StructureInventory.SLOT_COUNT; ++i) {
            final int component = i / StructureInventory.SLOTS_PER_COMPONENT;
            this.addSlot(new Slot(components, i, 230 + (i % 4) * 18, 44 + component * 22) {
                @Override public boolean mayPlace(ItemStack stack) {
                    return getKind().accepts(component, stack);
                }
            });
        }
        for (int row = 0; row < 3; ++row) {
            for (int column = 0; column < 9; ++column) {
                this.addSlot(new Slot(inventory, column + row * 9 + 9, 79 + column * 18, 144 + row * 18));
            }
        }
        for (int column = 0; column < 9; ++column) {
            this.addSlot(new Slot(inventory, column, 79 + column * 18, 202));
        }
    }

    public StructureKind getKind() {
        return StructureKind.values()[Math.max(0, Math.min(2, this.getData(0)))];
    }

    public int getData(int index) { return this._data.getValue(index); }
    public int count(int component) { return this.getData(4 + component * 3); }
    public int minimum(int component) { return this.getData(5 + component * 3); }
    public int growth(int component) { return this.getData(6 + component * 3); }

    @Override
    public boolean stillValid(Player player) {
        return this.tile == null || (!this.tile.isRemoved()
                && player.distanceToSqr(this.tile.getBlockPos().getCenter()) < 64
                && player.level().getBlockEntity(this.tile.getBlockPos()) == this.tile);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= this.slots.size()) return ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), original = stack.copy();
        if (index < StructureInventory.SLOT_COUNT) {
            if (!this.moveItemStackTo(stack, StructureInventory.SLOT_COUNT, this.slots.size(), true)) return ItemStack.EMPTY;
        } else if (!this.moveItemStackTo(stack, 0, StructureInventory.SLOT_COUNT, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (button != 0 || this.tile == null || !this.stillValid(player)) return false;
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> switch (this.tile.getStructureKind()) {
            case REACTOR -> new CompactReactorMenu(id, inventory, (com.compact.extremereactor.common.tile.CompactReactorTileEntity) this.tile);
            case TURBINE -> new CompactTurbineMenu(id, inventory, (com.compact.extremereactor.common.tile.CompactTurbineTileEntity) this.tile);
            case FLUIDIZER -> new CompactFluidizerMenu(id, inventory, (com.compact.extremereactor.common.tile.CompactFluidizerTileEntity) this.tile);
        }, Component.translatable("block.compactextremereactor.compact_" + this.tile.getStructureKind().key())));
        return true;
    }

    private record StructureData(AbstractCompactMachineTileEntity tile) implements ContainerData {
        @Override public int get(int index) {
            var state = this.tile.getStructureState();
            return switch (index) {
                case 0 -> this.tile.getStructureKind().ordinal();
                case 1 -> state.ready() ? 1 : 0;
                case 2 -> state.scaleUnits();
                case 3 -> state.bottleneck();
                default -> {
                    int component = (index - 4) / 3;
                    if (index < 4 || component >= this.tile.getStructureKind().componentCount()) yield 0;
                    yield switch ((index - 4) % 3) {
                        case 0 -> state.count(component);
                        case 1 -> state.minimum(component);
                        default -> state.growth(component);
                    };
                }
            };
        }
        @Override public void set(int index, int value) {}
        @Override public int getCount() { return DATA_COUNT; }
    }
}
