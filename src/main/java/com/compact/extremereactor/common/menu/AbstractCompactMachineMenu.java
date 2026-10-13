package com.compact.extremereactor.common.menu;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import com.compact.extremereactor.common.tile.AbstractCompactMachineTileEntity;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.network.chat.Component;

public abstract class AbstractCompactMachineMenu extends AbstractContainerMenu {
    private AbstractCompactMachineTileEntity machine;
    private PackedContainerData structureData;

    protected final void bindMachine(AbstractCompactMachineTileEntity machine) {
        this.machine = machine;
        ContainerData source = machine == null
                ? new SimpleContainerData(2)
                : new ContainerData() {
                    public int get(int index) { return machine.getStructureState().ready() ? 1 : 0; }
                    public void set(int index, int value) {}
                    public int getCount() { return 1; }
                };
        this.structureData = new PackedContainerData(source, 1, machine != null);
        this.addDataSlots(this.structureData);
    }

    public boolean isStructureReady() { return this.structureData != null && this.structureData.getValue(0) == 1; }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (button != 100 || this.machine == null || !this.stillValid(player)
                || player.level().getBlockEntity(this.machine.getBlockPos()) != this.machine) return false;
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new CompactStructureMenu(id, inventory, this.machine),
                Component.translatable("gui.compactextremereactor.structure")));
        return true;
    }

    protected final PackedContainerData _data;

    protected AbstractCompactMachineMenu(MenuType<?> menuType, int containerId, PackedContainerData data) {
        super(menuType, containerId);
        this._data = data;
        this.addDataSlots(data);
    }

    @Override
    public void broadcastChanges() {
        this._data.beginSync();
        try {
            super.broadcastChanges();
        } finally {
            this._data.endSync();
        }
    }

    @Override
    public void broadcastFullState() {
        this._data.beginSync();
        try {
            super.broadcastFullState();
        } finally {
            this._data.endSync();
        }
    }

    @Override
    public void sendAllDataToRemote() {
        this._data.beginSync();
        try {
            super.sendAllDataToRemote();
        } finally {
            this._data.endSync();
        }
    }
}
