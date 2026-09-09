package com.compact.extremereactor.client.screen;

import com.compact.extremereactor.common.menu.CompactReactorMenu;
import com.compact.extremereactor.common.network.ModPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 压缩极限反应堆 GUI：显示能量/燃料/废物/控制棒状态，并提供控制棒调节、
 * 机器开关与清除废料按钮。
 *
 * 玩家操作通过 {@link ModPackets} 的 C2S 数据包发送到服务端。
 */
public class CompactReactorScreen extends AbstractContainerScreen<CompactReactorMenu> {

    private static final int BG_COLOR = 0xFF333333;

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
        this.imageWidth = 264;
        this.imageHeight = 126;
    }

    @Override
    protected void init() {
        super.init();
        // 控制棒按钮：右侧，与控制棒文字对齐
        this._minusButton = this.addRenderableWidget(Button.builder(Component.translatable("gui.compactextremereactor.control_rod_minus"), b -> this.adjustControlRod(-5))
                .bounds(this.leftPos + 170, this.topPos + 40, 22, 18).build());
        this._plusButton = this.addRenderableWidget(Button.builder(Component.translatable("gui.compactextremereactor.control_rod_plus"), b -> this.adjustControlRod(5))
                .bounds(this.leftPos + 196, this.topPos + 40, 22, 18).build());
        this._toggleButton = this.addRenderableWidget(
                Button.builder(Component.translatable("gui.compactextremereactor.toggle"), b -> this.sendAction(ModPackets.ACTION_TOGGLE_ACTIVE))
                        .bounds(this.leftPos + 170, this.topPos + 66, 52, 20).build());
        this._wasteButton = this.addRenderableWidget(
                Button.builder(Component.translatable("gui.compactextremereactor.void_waste"), b -> this.sendAction(ModPackets.ACTION_VOID_WASTE))
                        .bounds(this.leftPos + 170, this.topPos + 90, 52, 20).build());
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
        // 背景（纯色面板，不依赖 ER 纹理）
        guiGraphics.fill(this.leftPos, this.topPos, this.leftPos + this.imageWidth, this.topPos + this.imageHeight, BG_COLOR);

        // 能量条（右上，竖直，较长）
        this.renderVerticalBar(guiGraphics, this.leftPos + 244, this.topPos + 16, 12, 100,
                this.menu.getData(CompactReactorMenu.DATA_ENERGY),
                this.menu.getData(CompactReactorMenu.DATA_ENERGY_CAPACITY), 0xFFE8B000);
        // 燃料条（水平，青色，较宽）
        this.renderHorizontalBar(guiGraphics, this.leftPos + 8, this.topPos + 70, 140, 8,
                this.menu.getData(CompactReactorMenu.DATA_FUEL),
                this.menu.getData(CompactReactorMenu.DATA_FUEL_CAPACITY), 0xFF40C0C0);
        // 废物条（水平，深灰，较宽）——分母用燃料容量（废料与燃料共享同一容器）
        this.renderHorizontalBar(guiGraphics, this.leftPos + 8, this.topPos + 98, 140, 8,
                this.menu.getData(CompactReactorMenu.DATA_WASTE),
                this.menu.getData(CompactReactorMenu.DATA_FUEL_CAPACITY), 0xFF707070);
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
        // 控制器初始化失败时，顶部显示红色警告（让玩家立刻知道机器不可用）
        if (this.menu.getData(CompactReactorMenu.DATA_INIT_FAILED) == 1) {
            guiGraphics.drawString(this.font,
                    Component.translatable("gui.compactextremereactor.init_failed"),
                    8, 4, 0xFFC04040);
            return;
        }
        if (this.menu.getData(CompactReactorMenu.DATA_CONTROLLER_READY) != 1) {
            guiGraphics.drawString(this.font,
                    Component.translatable("gui.compactextremereactor.controller_initializing"),
                    8, 4, 0xFFE0C040);
            return;
        }
        // 发电量（FE/t）
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.power",
                        this.menu.getData(CompactReactorMenu.DATA_POWER)),
                8, 4, 0xFFFFFF);
        // 反应堆堆芯温度：> 500°C 黄、> 800°C 红（与 ER 默认 overheat 阈值一致）
        final int heatRaw = this.menu.getData(CompactReactorMenu.DATA_REACTOR_HEAT);
        final double heatC = heatRaw / 10.0;
        final int heatColor = heatC > 800 ? 0xFFC04040 : (heatC > 500 ? 0xFFE0C040 : 0xFF60D0D0);
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.heat", String.format("%.0f", heatC)),
                90, 4, heatColor);
        // 开关状态指示（绿色=运行中，红色=已停止）
        final boolean active = this.menu.getData(CompactReactorMenu.DATA_ACTIVE) == 1;
        guiGraphics.drawString(this.font,
                Component.translatable(active ? "gui.compactextremereactor.status_on" : "gui.compactextremereactor.status_off"),
                8, 22, active ? 0xFF40C040 : 0xFFC04040);
        // 控制棒状态文本
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.control_rod",
                        this._localRatioInitialized
                                ? this._localControlRodRatio
                                : this.menu.getData(CompactReactorMenu.DATA_CONTROL_ROD)),
                8, 40, 0xFFFFFF);
        // 燃料量文本
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.fuel",
                        this.menu.getData(CompactReactorMenu.DATA_FUEL)),
                8, 60, 0xFFFFFF);
        // 废物量文本
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.waste",
                        this.menu.getData(CompactReactorMenu.DATA_WASTE)),
                8, 88, 0xFFFFFF);
        // 能量值文本
        guiGraphics.drawString(this.font,
                Component.translatable("gui.compactextremereactor.energy",
                        this.menu.getData(CompactReactorMenu.DATA_ENERGY)),
                8, 116, 0xFFFFFF);
    }
}