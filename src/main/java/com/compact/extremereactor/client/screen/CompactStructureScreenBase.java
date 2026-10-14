package com.compact.extremereactor.client.screen;

import com.compact.extremereactor.common.menu.CompactStructureMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.Locale;

public abstract class CompactStructureScreenBase extends CompactMachineScreen<CompactStructureMenu> {
    protected CompactStructureScreenBase(CompactStructureMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageHeight = 230;
    }

    @Override
    protected void init() {
        super.init();
        this.addRenderableWidget(Button.builder(Component.literal("<"), button -> {
            if (this.minecraft != null && this.minecraft.gameMode != null)
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, 0);
        }).bounds(this.leftPos + this.imageWidth + 2, this.topPos + 8, 20, 20)
                .tooltip(Tooltip.create(Component.translatable("gui.compactextremereactor.machine_status"))).build());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = this.leftPos, y = this.topPos;
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, 0xFF20272C);
        graphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + 30, 0xFF334248);
        graphics.fill(x + 12, y + 128, x + 308, y + 129, 0xFF586166);
        for (var slot : this.menu.slots) {
            graphics.fill(x + slot.x - 1, y + slot.y - 1, x + slot.x + 17, y + slot.y + 17, 0xFF68757A);
            graphics.fill(x + slot.x, y + slot.y, x + slot.x + 16, y + slot.y + 16, 0xFF101719);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        this.drawFit(graphics, Component.translatable("block.compactextremereactor.compact_" + this.menu.getKind().key()),
                12, 10, 158, 0xFFF1F4F5);
        boolean ready = this.menu.getData(1) == 1;
        this.drawFit(graphics, Component.translatable(ready ? "gui.compactextremereactor.structure_scale"
                        : "gui.compactextremereactor.structure_incomplete",
                String.format(Locale.ROOT, "%.2f", this.menu.getData(2) / 1000.0)),
                176, 10, 132, ready ? 0xFF8DD8C4 : 0xFFEEA687);
        for (int component = 0; component < this.menu.getKind().componentCount(); ++component) {
            int y = 43 + component * 22;
            int color = component == this.menu.getData(3) ? 0xFFE7BE75 : 0xFFDCE2E4;
            this.drawFit(graphics, Component.translatable("gui.compactextremereactor.component."
                    + this.menu.getKind().componentKey(component)), 12, y, 130, color);
            this.drawFit(graphics, Component.literal(this.menu.count(component) + " / " + this.menu.minimum(component)),
                    156, y, 62, this.menu.count(component) < this.menu.minimum(component) ? 0xFFEEA687 : color);
            this.drawFit(graphics, Component.translatable("gui.compactextremereactor.component_growth",
                    String.format(Locale.ROOT, "%.3f", this.menu.growth(component) / 1000.0)),
                    12, y + 10, 206, 0xFF9EAEB4);
        }
        this.drawFit(graphics, Component.translatable("container.inventory"), 79, 132, 162, 0xFFDCE2E4);
    }
}
