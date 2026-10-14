package com.compact.extremereactor.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** 共用机器 GUI 的尺寸、进度条和文字裁剪。 */
public abstract class CompactMachineScreen<T extends AbstractContainerMenu>
        extends AbstractContainerScreen<T> {

    protected CompactMachineScreen(T menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 320;
        this.imageHeight = 180;
    }

    @Override
    protected void init() {
        super.init();
        if (this.menu instanceof com.compact.extremereactor.common.menu.AbstractCompactMachineMenu
                && !(this.menu instanceof com.compact.extremereactor.common.menu.CompactStructureMenu)) {
            this.addRenderableWidget(net.minecraft.client.gui.components.Button.builder(Component.literal("+"), button -> {
                if (this.minecraft != null && this.minecraft.gameMode != null)
                    this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 100);
            }).bounds(this.leftPos + this.imageWidth + 2, this.topPos + 8, 20, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                            Component.translatable("gui.compactextremereactor.structure"))).build());
        }
    }

    protected final void renderVerticalBar(
            GuiGraphics graphics, int x, int y, int width, int height,
            int value, int capacity, int color) {
        graphics.fill(x, y, x + width, y + height, 0xFF52616C);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF0D151D);
        if (capacity > 0 && value > 0) {
            int filled = Math.min(height - 2, (int) ((long) (height - 2) * value / capacity));
            graphics.fill(x + 1, y + height - 1 - filled, x + width - 1, y + height - 1, color);
        }
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0x66FFFFFF);
    }

    protected final void renderHorizontalBar(
            GuiGraphics graphics, int x, int y, int width, int height,
            int value, int capacity, int color) {
        graphics.fill(x, y, x + width, y + height, 0xFF52616C);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF0D151D);
        if (capacity > 0 && value > 0) {
            int filled = Math.min(width - 2, (int) ((long) (width - 2) * value / capacity));
            graphics.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, color);
        }
        graphics.fill(x + 1, y + 1, x + width - 1, y + 2, 0x66FFFFFF);
    }

    protected final void drawFit(
            GuiGraphics graphics, Component text, int x, int y, int maxWidth, int color) {
        graphics.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), maxWidth), x, y, color);
    }
}
