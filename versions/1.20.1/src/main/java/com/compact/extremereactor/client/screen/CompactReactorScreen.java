package com.compact.extremereactor.client.screen;

import com.compact.extremereactor.common.menu.CompactReactorMenu;
import com.compact.extremereactor.common.network.ModPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * 压缩反应堆 GUI：显示能量/燃料/废物/控制棒状态，并提供控制棒调节、
 * 机器开关与清除废料按钮。
 *
 * 背景复用 ER 的 basic_background 纹理（256x256，取左上 176x166 区域）。
 * 玩家操作通过 {@link ModPackets} 的 C2S 数据包发送到服务端。
 */
public class CompactReactorScreen extends AbstractContainerScreen<CompactReactorMenu> {

    private static final ResourceLocation BACKGROUND = new ResourceLocation(
            "bigreactors", "textures/gui/multiblock/basic_background.png");

    // 控制棒调节按钮与动作按钮（初始禁用，等待方块坐标同步完成）
    private Button _minusButton;
    private Button _plusButton;
    private Button _toggleButton;
    private Button _wasteButton;

    public CompactReactorScreen(CompactReactorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
    }

    @Override
    protected void init() {
        super.init();
        this._minusButton = this.addRenderableWidget(Button.builder(Component.literal("-5"), b -> this.adjustControlRod(-5))
                .bounds(this.leftPos + 100, this.topPos + 20, 20, 20).build());
        this._plusButton = this.addRenderableWidget(Button.builder(Component.literal("+5"), b -> this.adjustControlRod(5))
                .bounds(this.leftPos + 124, this.topPos + 20, 20, 20).build());
        this._toggleButton = this.addRenderableWidget(
                Button.builder(Component.translatable("gui.compactextremereactor.toggle"), b -> this.sendAction(ModPackets.ACTION_TOGGLE_ACTIVE))
                        .bounds(this.leftPos + 100, this.topPos + 44, 44, 20).build());
        this._wasteButton = this.addRenderableWidget(
                Button.builder(Component.translatable("gui.compactextremereactor.void_waste"), b -> this.sendAction(ModPackets.ACTION_VOID_WASTE))
                        .bounds(this.leftPos + 100, this.topPos + 68, 44, 20).build());
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // 方块坐标同步完成后才允许操作（防止把无效坐标发给服务端）
        final boolean ready = this.menu.getData(CompactReactorMenu.DATA_POS_READY) == 1;
        this._minusButton.active = ready;
        this._plusButton.active = ready;
        this._toggleButton.active = ready;
        this._wasteButton.active = ready;
    }

    /** 从同步数据中解析方块坐标；未同步完成时返回 null。 */
    private BlockPos getBlockPos() {
        if (this.menu.getData(CompactReactorMenu.DATA_POS_READY) != 1) {
            return null;
        }
        return new BlockPos(
                this.menu.getData(CompactReactorMenu.DATA_POS_X),
                this.menu.getData(CompactReactorMenu.DATA_POS_Y),
                this.menu.getData(CompactReactorMenu.DATA_POS_Z));
    }

    /** 调节控制棒插入比例并发送到服务端。 */
    private void adjustControlRod(int delta) {
        final BlockPos pos = this.getBlockPos();
        if (pos == null) {
            return;
        }
        final int ratio = Math.max(0, Math.min(100, this.menu.getData(CompactReactorMenu.DATA_CONTROL_ROD) + delta));
        ModPackets.sendToServer(new ModPackets.ControlRodPayload(pos, ratio));
    }

    /** 发送机器动作指令到服务端。 */
    private void sendAction(int action) {
        final BlockPos pos = this.getBlockPos();
        if (pos == null) {
            return;
        }
        ModPackets.sendToServer(new ModPackets.MachineActionPayload(pos, action));
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(BACKGROUND, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, 256, 256);

        // 能量条（右上，竖直）
        this.renderBar(guiGraphics, this.leftPos + 152, this.topPos + 17, 16, 60,
                this.menu.getData(CompactReactorMenu.DATA_ENERGY),
                this.menu.getData(CompactReactorMenu.DATA_ENERGY_CAPACITY), 0xFFE8B000);
        // 燃料条（燃料槽下方，青色）
        this.renderBar(guiGraphics, this.leftPos + 8, this.topPos + 57, 80, 8,
                this.menu.getData(CompactReactorMenu.DATA_FUEL),
                this.menu.getData(CompactReactorMenu.DATA_FUEL_CAPACITY), 0xFF40C0C0);
        // 废物条（深灰）
        this.renderBar(guiGraphics, this.leftPos + 8, this.topPos + 67, 80, 8,
                this.menu.getData(CompactReactorMenu.DATA_WASTE),
                this.menu.getData(CompactReactorMenu.DATA_FUEL_CAPACITY), 0xFF707070);
    }

    /** 绘制一个带黑色边框、按比例填充的条。 */
    private void renderBar(GuiGraphics guiGraphics, int x, int y, int width, int height, int value, int capacity, int color) {
        guiGraphics.fill(x, y, x + width, y + height, 0xFF000000);
        if (capacity > 0 && value > 0) {
            final int filled = (int) Math.min(height - 2, (height - 2) * value / (float) capacity);
            guiGraphics.fill(x + 1, y + height - 1 - filled, x + width - 1, y + height - 1, color);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 控制棒状态文本
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.control_rod",
                        this.menu.getData(CompactReactorMenu.DATA_CONTROL_ROD)),
                100, 8, 0xFFFFFF);
        // 燃料/废物量文本
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.fuel",
                        this.menu.getData(CompactReactorMenu.DATA_FUEL)),
                8, 47, 0xFFFFFF);
        // 能量值文本
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.energy",
                        this.menu.getData(CompactReactorMenu.DATA_ENERGY)),
                116, 24, 0xFFFFFF);
    }
}
