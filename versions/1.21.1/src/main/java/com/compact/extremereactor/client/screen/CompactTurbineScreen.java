package com.compact.extremereactor.client.screen;

import com.compact.extremereactor.common.menu.CompactTurbineMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/** 涡轮机的能量、流体和转速显示。 */
public class CompactTurbineScreen extends CompactMachineScreen<CompactTurbineMenu> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("compactextremereactor", "textures/gui/compact_turbine.png");

    public CompactTurbineScreen(CompactTurbineMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(
                TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, 512, 256);
        this.renderHorizontalBar(guiGraphics, this.leftPos + 14, this.topPos + 64, 174, 14, this.menu.getData(CompactTurbineMenu.DATA_STEAM), this.menu.getData(CompactTurbineMenu.DATA_FLUID_CAPACITY), 0xFFA3DCE7);
        this.renderHorizontalBar(guiGraphics, this.leftPos + 14, this.topPos + 120, 174, 14, this.menu.getData(CompactTurbineMenu.DATA_WATER), this.menu.getData(CompactTurbineMenu.DATA_FLUID_CAPACITY), 0xFF4C9DD8);
        this.renderVerticalBar(guiGraphics, this.leftPos + 300, this.topPos + 14, 10, 138, this.menu.getData(CompactTurbineMenu.DATA_ENERGY), this.menu.getData(CompactTurbineMenu.DATA_ENERGY_CAPACITY), 0xFFE7B95B);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        if (this.menu.getData(CompactTurbineMenu.DATA_INIT_FAILED) == 1) {
            this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.init_failed"), 14, 14, 272, 0xFFFF6B6B);
            return;
        }
        if (this.menu.getData(CompactTurbineMenu.DATA_CONTROLLER_READY) != 1) {
            this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.controller_initializing"), 14, 14, 272, 0xFFFFD166);
            return;
        }
        boolean energyFull = this.menu.getData(CompactTurbineMenu.DATA_ENERGY_CAPACITY) > 0
                && this.menu.getData(CompactTurbineMenu.DATA_ENERGY) >= this.menu.getData(CompactTurbineMenu.DATA_ENERGY_CAPACITY);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.power", this.menu.getData(CompactTurbineMenu.DATA_POWER)), 14, 14, 150, 0xFFFFFFFF);
        if (!this.menu.isStructureReady()) {
            this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.structure_incomplete"), 174, 32, 112, 0xFFEEA687);
        } else if (energyFull) {
            this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.status_energy_full"), 174, 32, 112, 0xFFE7B95B);
        }
        double rpm = this.menu.getData(CompactTurbineMenu.DATA_ROTOR_SPEED) / 10.0;
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.rotor_speed", String.format("%.0f", rpm)), 174, 14, 112, rpm > 0 ? 0xFFE7B95B : 0xFF9AA7B2);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.steam", this.menu.getData(CompactTurbineMenu.DATA_STEAM)), 14, 48, 174, 0xFFA3DCE7);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.water", this.menu.getData(CompactTurbineMenu.DATA_WATER)), 14, 104, 174, 0xFF4C9DD8);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.energy", this.menu.getData(CompactTurbineMenu.DATA_ENERGY)), 14, 164, 272, 0xFFE7B95B);
    }
}
