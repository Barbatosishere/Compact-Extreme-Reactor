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
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 压缩极限反应堆 GUI：显示能量/燃料/废料/控制棒状态，并提供控制棒调节、
 * 机器开关与清除废料按钮。
 *
 * 玩家操作通过 {@link ModPackets} 的 C2S 数据包发送到服务端。
 */
public class CompactReactorScreen extends AbstractContainerScreen<CompactReactorMenu> {

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("compactextremereactor", "textures/gui/compact_reactor.png");

    // 控制棒调节按钮与动作按钮（初始禁用，等待方块坐标同步完成）
    private Button _minusButton;
    private Button _plusButton;
    private Button _toggleButton;
    private Button _wasteButton;

    /** 客户端本地预测值；服务端同步变化或确认超时后会自动校正。 */
    private int _localControlRodRatio = 50;
    private int _lastServerControlRodRatio = 50;
    private boolean _localRatioInitialized;
    private long _controlRodPredictionDeadline = Long.MIN_VALUE;

    public CompactReactorScreen(CompactReactorMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 320;
        this.imageHeight = 180;
    }

    @Override
    protected void init() {
        super.init();
        // 控制棒按钮：右侧，与控制棒文字对齐
        this._minusButton = this.addRenderableWidget(Button.builder(Component.translatable("gui.compactextremereactor.control_rod_minus"), b -> this.adjustControlRod(-5))
                .bounds(this.leftPos + 196, this.topPos + 68, 42, 20).build());
        this._plusButton = this.addRenderableWidget(Button.builder(Component.translatable("gui.compactextremereactor.control_rod_plus"), b -> this.adjustControlRod(5))
                .bounds(this.leftPos + 244, this.topPos + 68, 42, 20).build());
        this._toggleButton = this.addRenderableWidget(
                Button.builder(Component.translatable("gui.compactextremereactor.toggle"), b -> this.sendAction(ModPackets.ACTION_TOGGLE_ACTIVE))
                        .bounds(this.leftPos + 204, this.topPos + 38, 82, 20).build());
        this._wasteButton = this.addRenderableWidget(
                Button.builder(Component.translatable("gui.compactextremereactor.void_waste"), b -> this.sendAction(ModPackets.ACTION_VOID_WASTE))
                        .bounds(this.leftPos + 204, this.topPos + 128, 82, 20).build());
        this._minusButton.active = false;
        this._plusButton.active = false;
        this._toggleButton.active = false;
        this._wasteButton.active = false;
    }

    @Override
    public void containerTick() {
        super.containerTick();
        // 坐标和控制器均同步就绪后才允许操作，避免初始化期间发送必然被拒绝的控制包。
        final boolean positionReady = this.menu.getData(CompactReactorMenu.DATA_POS_READY) == 1;
        final boolean controllerReady = this.menu.getData(CompactReactorMenu.DATA_CONTROLLER_READY) == 1;
        final boolean initFailed = this.menu.getData(CompactReactorMenu.DATA_INIT_FAILED) == 1;
        final boolean enabled = positionReady && controllerReady && !initFailed;
        this._minusButton.active = enabled;
        this._plusButton.active = enabled;
        this._toggleButton.active = enabled;
        this._wasteButton.active = enabled;
        if (controllerReady) {
            final int serverRatio = this.menu.getData(CompactReactorMenu.DATA_CONTROL_ROD);
            final long nowTick = this.getClientGameTime();
            if (!this._localRatioInitialized) {
                this._localControlRodRatio = serverRatio;
                this._lastServerControlRodRatio = serverRatio;
                this._localRatioInitialized = true;
            } else if (serverRatio != this._lastServerControlRodRatio) {
                this._lastServerControlRodRatio = serverRatio;
                this._localControlRodRatio = serverRatio;
                this._controlRodPredictionDeadline = Long.MIN_VALUE;
            } else if (this._controlRodPredictionDeadline != Long.MIN_VALUE
                    && nowTick >= this._controlRodPredictionDeadline) {
                this._localControlRodRatio = serverRatio;
                this._controlRodPredictionDeadline = Long.MIN_VALUE;
            }
        }
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
        final long nowTick = this.getClientGameTime();
        if (nowTick == Long.MIN_VALUE) {
            return; // 世界未就绪（加载/重连过渡期）：fail-closed，不发控制棒包
        }
        if (nowTick == this._lastControlRodSendTick) {
            return;
        }
        this._lastControlRodSendTick = nowTick;
        this._localControlRodRatio = Math.clamp(this._localControlRodRatio + delta, 0, 100);
        this._controlRodPredictionDeadline = nowTick + 10;
        PacketDistributor.sendToServer(new ModPackets.ControlRodPayload(pos, delta));
    }

    private long getClientGameTime() {
        return this.minecraft != null && this.minecraft.level != null
                ? this.minecraft.level.getGameTime() : Long.MIN_VALUE;
    }

    /** 上次发送控制棒数据包的世界刻（客户端去抖，见 adjustControlRod）。 */
    private long _lastControlRodSendTick = Long.MIN_VALUE;

    /** 发送机器动作指令到服务端。 */
    private void sendAction(int action) {
        final BlockPos pos = this.getBlockPos();
        if (pos == null) {
            return;
        }
        PacketDistributor.sendToServer(new ModPackets.MachineActionPayload(pos, action));
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.blit(
                TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight, 512, 256);
        this.renderHorizontalBar(guiGraphics, this.leftPos + 14, this.topPos + 112, 272, 10, this.menu.getData(CompactReactorMenu.DATA_FUEL), this.menu.getData(CompactReactorMenu.DATA_FUEL_CAPACITY), 0xFFB4D85A);
        this.renderHorizontalBar(guiGraphics, this.leftPos + 14, this.topPos + 140, 174, 10, this.menu.getData(CompactReactorMenu.DATA_WASTE), this.menu.getData(CompactReactorMenu.DATA_FUEL_CAPACITY), 0xFFAD9164);
        this.renderVerticalBar(guiGraphics, this.leftPos + 300, this.topPos + 14, 10, 138, this.menu.getData(CompactReactorMenu.DATA_ENERGY), this.menu.getData(CompactReactorMenu.DATA_ENERGY_CAPACITY), 0xFFE7B95B);
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
        if (this.menu.getData(CompactReactorMenu.DATA_INIT_FAILED) == 1) {
            this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.init_failed"), 14, 14, 272, 0xFFFF6B6B);
            return;
        }
        if (this.menu.getData(CompactReactorMenu.DATA_CONTROLLER_READY) != 1) {
            this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.controller_initializing"), 14, 14, 272, 0xFFFFD166);
            return;
        }
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.power", this.menu.getData(CompactReactorMenu.DATA_POWER)), 14, 14, 150, 0xFFFFFFFF);
        double heat = this.menu.getData(CompactReactorMenu.DATA_REACTOR_HEAT) / 10.0;
        int heatColor = heat > 800 ? 0xFFFF6B6B : heat > 500 ? 0xFFFFD166 : 0xFF70D6D0;
        this.drawFit(
                guiGraphics,
                Component.translatable("gui.compactextremereactor.heat", String.format("%.0f", heat)),
                174,
                14,
                112,
                heatColor);
        boolean energyFull = this.menu.getData(CompactReactorMenu.DATA_ENERGY_CAPACITY) > 0
                && this.menu.getData(CompactReactorMenu.DATA_ENERGY) >= this.menu.getData(CompactReactorMenu.DATA_ENERGY_CAPACITY);
        boolean active = this.menu.getData(CompactReactorMenu.DATA_ACTIVE) == 1;
        final String statusKey;
        final int statusColor;
        if (energyFull) {
            statusKey = "gui.compactextremereactor.status_energy_full";
            statusColor = 0xFFE7B95B;
        } else if (active) {
            statusKey = "gui.compactextremereactor.status_on";
            statusColor = 0xFF70D6A0;
        } else {
            statusKey = "gui.compactextremereactor.status_off";
            statusColor = 0xFFFF6B6B;
        }
        this.drawFit(guiGraphics, Component.translatable(statusKey), 14, 44, 174, statusColor);
        int rod = this._localRatioInitialized ? this._localControlRodRatio : this.menu.getData(CompactReactorMenu.DATA_CONTROL_ROD);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.control_rod", rod), 14, 74, 174, 0xFFFFFFFF);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.fuel", this.menu.getData(CompactReactorMenu.DATA_FUEL)), 14, 100, 272, 0xFFB4D85A);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.waste", this.menu.getData(CompactReactorMenu.DATA_WASTE)), 14, 128, 174, 0xFFAD9164);
        this.drawFit(guiGraphics, Component.translatable("gui.compactextremereactor.energy", this.menu.getData(CompactReactorMenu.DATA_ENERGY)), 14, 164, 272, 0xFFE7B95B);
    }

    private void drawFit(GuiGraphics guiGraphics, Component text, int x, int y, int maxWidth, int color) {
        guiGraphics.drawString(this.font, this.font.plainSubstrByWidth(text.getString(), maxWidth), x, y, color);
    }
}
