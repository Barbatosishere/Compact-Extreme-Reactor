package com.compact.extremereactor.common.menu;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

abstract class AbstractCompactMachineMenu extends AbstractContainerMenu {

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
