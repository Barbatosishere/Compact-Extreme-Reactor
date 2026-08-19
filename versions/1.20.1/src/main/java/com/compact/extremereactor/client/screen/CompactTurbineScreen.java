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

    private static final ResourceLocation BACKGROUND = new ResourceLocation(
            "bigreactors", "textures/gui/multiblock/basic_background.png");

    public CompactTurbineScreen(CompactTurbineMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(BACKGROUND, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, 256, 256);

        // 能量条（右上，竖直）
        this.renderVerticalBar(guiGraphics, this.leftPos + 152, this.topPos + 17, 16, 60,
                this.menu.getData(CompactTurbineMenu.DATA_ENERGY),
                this.menu.getData(CompactTurbineMenu.DATA_ENERGY_CAPACITY), 0xFFE8B000);
        // 蒸汽条（水平，淡蓝）
        this.renderHorizontalBar(guiGraphics, this.leftPos + 8, this.topPos + 17, 80, 8,
                this.menu.getData(CompactTurbineMenu.DATA_STEAM),
                this.menu.getData(CompactTurbineMenu.DATA_FLUID_CAPACITY), 0xFFB0D0E0);
        // 冷凝水条（水平，深蓝）
        this.renderHorizontalBar(guiGraphics, this.leftPos + 8, this.topPos + 27, 80, 8,
                this.menu.getData(CompactTurbineMenu.DATA_WATER),
                this.menu.getData(CompactTurbineMenu.DATA_FLUID_CAPACITY), 0xFF2050A0);
    }

    /** 绘制一个带黑色边框、按比例竖直填充的条（从下往上）。 */
    private void renderVerticalBar(GuiGraphics guiGraphics, int x, int y, int width, int height, int value, int capacity, int color) {
        guiGraphics.fill(x, y, x + width, y + height, 0xFF000000);
        if (capacity > 0 && value > 0) {
            final int filled = Math.min(height - 2, (int)((long)(height - 2) * value / capacity));
            guiGraphics.fill(x + 1, y + height - 1 - filled, x + width - 1, y + height - 1, color);
        }
    }

    /** 绘制一个带黑色边框、按比例水平填充的条（从左往右）。 */
    private void renderHorizontalBar(GuiGraphics guiGraphics, int x, int y, int width, int height, int value, int capacity, int color) {
        guiGraphics.fill(x, y, x + width, y + height, 0xFF000000);
        if (capacity > 0 && value > 0) {
            final int filled = Math.min(width - 2, (int)((long)(width - 2) * value / capacity));
            guiGraphics.fill(x + 1, y + 1, x + 1 + filled, y + height - 1, color);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 发电量与流体量文本
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.power",
                        this.menu.getData(CompactTurbineMenu.DATA_POWER)),
                8, 47, 0xFFFFFF);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.steam",
                        this.menu.getData(CompactTurbineMenu.DATA_STEAM)),
                8, 57, 0xFFFFFF);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.water",
                        this.menu.getData(CompactTurbineMenu.DATA_WATER)),
                8, 67, 0xFFFFFF);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.energy",
                        this.menu.getData(CompactTurbineMenu.DATA_ENERGY)),
                116, 24, 0xFFFFFF);
    }
}
