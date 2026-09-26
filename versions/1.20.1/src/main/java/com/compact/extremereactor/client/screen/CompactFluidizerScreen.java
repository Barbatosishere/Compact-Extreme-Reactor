package com.compact.extremereactor.client.screen;

import com.compact.extremereactor.common.menu.CompactFluidizerMenu;
import com.compact.extremereactor.common.multiblock.CompactFluidizerController;
import com.compact.extremereactor.common.network.ModPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 压缩流化器 GUI：显示配方模式/进度、进料（物品/流体）、产物与能量缓冲，
 * 并提供机器开关与清空进料按钮。
 *
 * 玩家操作通过 {@link ModPackets} 的 C2S 数据包发送到服务端。
 */
public class CompactFluidizerScreen extends AbstractContainerScreen<CompactFluidizerMenu> {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
            "compactextremereactor", "textures/gui/compact_fluidizer.png");

    private Button _toggleButton;
    private Button _clearButton;

    public CompactFluidizerScreen(CompactFluidizerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 320;
        this.imageHeight = 180;
    }

    @Override
    protected void init() {
        super.init();
        this._toggleButton = this.addRenderableWidget(
                Button.builder(Component.translatable("gui.compactextremereactor.toggle"),
                                b -> this.sendAction(ModPackets.ACTION_TOGGLE_ACTIVE))
                        .bounds(this.leftPos + 204, this.topPos + 38, 82, 20).build());
        this._clearButton = this.addRenderableWidget(
                Button.builder(Component.translatable("gui.compactextremereactor.fluidizer_clear_inputs"),
                                b -> this.sendAction(ModPackets.ACTION_CLEAR_INPUTS))
                        .bounds(this.leftPos + 204, this.topPos + 64, 82, 20).build());
        this._toggleButton.active = false;
        this._clearButton.active = false;
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // 坐标和控制器均同步就绪后才允许操作，避免初始化期间发送必然被拒绝的控制包。
        final boolean enabled = this.menu.getData(CompactFluidizerMenu.DATA_POS_READY) == 1
                && this.menu.getData(CompactFluidizerMenu.DATA_CONTROLLER_READY) == 1
                && this.menu.getData(CompactFluidizerMenu.DATA_INIT_FAILED) != 1;
        this._toggleButton.active = enabled;
        this._clearButton.active = enabled;
    }

    /** 发送机器动作指令到服务端。 */
    private void sendAction(int action) {
        if (this.menu.getData(CompactFluidizerMenu.DATA_POS_READY) != 1) {
            return;
        }
        final BlockPos pos = new BlockPos(
                this.menu.getData(CompactFluidizerMenu.DATA_POS_X),
                this.menu.getData(CompactFluidizerMenu.DATA_POS_Y),
                this.menu.getData(CompactFluidizerMenu.DATA_POS_Z));
        ModPackets.sendToServer(new ModPackets.MachineActionPayload(pos, action));
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(
                TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, 512, 256);
        this.renderHorizontalBar(guiGraphics, this.leftPos + 14, this.topPos + 112, 272, 10,
                this.menu.getData(CompactFluidizerMenu.DATA_PROGRESS), 1000, 0xFFFF9F43);
        this.renderHorizontalBar(guiGraphics, this.leftPos + 14, this.topPos + 136, 130, 8,
                this.menu.getData(CompactFluidizerMenu.DATA_FLUID_IN_0_MB),
                CompactFluidizerController.FLUID_INPUT_CAPACITY, 0xFF70D6D0);
        this.renderHorizontalBar(guiGraphics, this.leftPos + 156, this.topPos + 136, 130, 8,
                this.menu.getData(CompactFluidizerMenu.DATA_FLUID_IN_1_MB),
                CompactFluidizerController.FLUID_INPUT_CAPACITY, 0xFF70D6D0);
        this.renderHorizontalBar(guiGraphics, this.leftPos + 14, this.topPos + 160, 272, 10,
                this.menu.getData(CompactFluidizerMenu.DATA_OUTPUT_MB),
                this.menu.getData(CompactFluidizerMenu.DATA_OUTPUT_CAPACITY_MB), 0xFF70D6A0);
        this.renderVerticalBar(guiGraphics, this.leftPos + 300, this.topPos + 14, 10, 138,
                this.menu.getData(CompactFluidizerMenu.DATA_ENERGY),
                this.menu.getData(CompactFluidizerMenu.DATA_ENERGY_CAPACITY), 0xFFE7B95B);
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
        if (this.menu.getData(CompactFluidizerMenu.DATA_INIT_FAILED) == 1) {
            this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.init_failed"), 14, 14, 272, 0xFFFF6B6B);
            return;
        }
        if (this.menu.getData(CompactFluidizerMenu.DATA_CONTROLLER_READY) != 1) {
            this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.controller_initializing"), 14, 14, 272, 0xFFFFD166);
            return;
        }
        // 配方模式（左）与运行状态（右）
        final var modes = CompactFluidizerController.RecipeMode.values();
        final var mode = modes[Math.floorMod(this.menu.getData(CompactFluidizerMenu.DATA_RECIPE_MODE), modes.length)];
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.fluidizer_mode",
                Component.translatable("gui.compactextremereactor.fluidizer_mode_" + mode.name().toLowerCase())), 14, 14, 150, 0xFFFFFFFF);
        final boolean active = this.menu.getData(CompactFluidizerMenu.DATA_ACTIVE) == 1;
        this.drawFit(guiGraphics, Component.translatable(active
                        ? "gui.compactextremereactor.status_on" : "gui.compactextremereactor.status_off"),
                174, 14, 112, active ? 0xFF70D6A0 : 0xFFFF6B6B);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.energy",
                this.menu.getData(CompactFluidizerMenu.DATA_ENERGY)), 14, 44, 272, 0xFFE7B95B);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.fluidizer_items",
                this.menu.getData(CompactFluidizerMenu.DATA_ITEM_COUNT_0),
                this.menu.getData(CompactFluidizerMenu.DATA_ITEM_COUNT_1)), 14, 74, 272, 0xFFFFFFFF);
        // 配方进度
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.fluidizer_progress",
                this.menu.getData(CompactFluidizerMenu.DATA_PROGRESS) / 10), 14, 100, 272, 0xFFFF9F43);
        // 流体进料（两罐）
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.fluidizer_fluid_in",
                this.menu.getData(CompactFluidizerMenu.DATA_FLUID_IN_0_MB)), 14, 124, 130, 0xFF70D6D0);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.fluidizer_fluid_in",
                this.menu.getData(CompactFluidizerMenu.DATA_FLUID_IN_1_MB)), 156, 124, 130, 0xFF70D6D0);
        // 产物
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.fluidizer_output",
                this.menu.getData(CompactFluidizerMenu.DATA_OUTPUT_MB),
                this.menu.getData(CompactFluidizerMenu.DATA_OUTPUT_CAPACITY_MB)), 14, 148, 272, 0xFF70D6A0);
    }

    private void drawFit(GuiGraphics guiGraphics, Component text, int x, int y, int maxWidth, int color) {
        guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), maxWidth), x, y, color);
    }
}
