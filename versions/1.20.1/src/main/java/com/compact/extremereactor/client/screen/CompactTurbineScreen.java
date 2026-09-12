package com.compact.extremereactor.client.screen;

import com.compact.extremereactor.common.menu.CompactTurbineMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 压缩涡轮机 GUI：显示能量/蒸汽/水/发电量状态。
 *
 * 蒸汽输入与冷凝水输出通过流体能力完成（玩家用流体管道连接），
 * 本界面为纯状态显示，无交互按钮。
 */
public class CompactTurbineScreen extends AbstractContainerScreen<CompactTurbineMenu> {

    private static final ResourceLocation TEXTURE = new ResourceLocation("compactextremereactor", "textures/gui/compact_turbine.png");

    public CompactTurbineScreen(CompactTurbineMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 320;
        this.imageHeight = 180;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(
                TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, 512, 256);
        this.renderHorizontalBar(guiGraphics, this.leftPos + 14, this.topPos + 64, 174, 14, this.menu.getData(CompactTurbineMenu.DATA_STEAM), this.menu.getData(CompactTurbineMenu.DATA_FLUID_CAPACITY), 0xFFA3DCE7);
        this.renderHorizontalBar(guiGraphics, this.leftPos + 14, this.topPos + 120, 174, 14, this.menu.getData(CompactTurbineMenu.DATA_WATER), this.menu.getData(CompactTurbineMenu.DATA_FLUID_CAPACITY), 0xFF4C9DD8);
        this.renderVerticalBar(guiGraphics, this.leftPos + 300, this.topPos + 14, 10, 138, this.menu.getData(CompactTurbineMenu.DATA_ENERGY), this.menu.getData(CompactTurbineMenu.DATA_ENERGY_CAPACITY), 0xFFE7B95B);
    }

    private void renderVerticalBar(GuiGraphics g, int x, int y, int width, int height, int value, int capacity, int color) {
        g.fill(x, y, x + width, y + height, 0xFF52616C);
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF0D151D);
        if (capacity > 0 && value > 0) {
            int filled = Math.min(height - 2, (int) ((long) (height - 2) * value / capacity));
            g.fill(x + 1, y + height - 1 - filled, x + width - 1, y + height - 1, color);
        }
        g.fill(x + 1, y + 1, x + width - 1, y + 2, 0x66FFFFFF);
    }

    private void renderHorizontalBar(GuiGraphics g, int x, int y, int width, int height, int value, int capacity, int color) {
        g.fill(x, y, x + width, y + height, 0xFF52616C);
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF0D151D);
        if (capacity > 0 && value > 0) {
            int filled = Math.min(width - 2, (int) ((long) (width - 2) * value / capacity));
            g.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, color);
        }
        g.fill(x + 1, y + 1, x + width - 1, y + 2, 0x66FFFFFF);
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
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.power", this.menu.getData(CompactTurbineMenu.DATA_POWER)), 14, 14, 150, 0xFFFFFFFF);
        double rpm = this.menu.getData(CompactTurbineMenu.DATA_ROTOR_SPEED) / 10.0;
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.rotor_speed", String.format("%.0f", rpm)), 174, 14, 112, rpm > 0 ? 0xFFE7B95B : 0xFF9AA7B2);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.steam", this.menu.getData(CompactTurbineMenu.DATA_STEAM)), 14, 48, 174, 0xFFA3DCE7);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.water", this.menu.getData(CompactTurbineMenu.DATA_WATER)), 14, 104, 174, 0xFF4C9DD8);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.energy", this.menu.getData(CompactTurbineMenu.DATA_ENERGY)), 14, 164, 272, 0xFFE7B95B);
    }

    private void drawFit(GuiGraphics guiGraphics, Component text, int x, int y, int maxWidth, int color) {
        guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), maxWidth), x, y, color);
    }
}
