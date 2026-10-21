package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.menu.MeItemBusMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * C4（0.73.0）：ME 物品总线的窗口。
 *
 * <p>输入窗口沿用旧版 {@code textures/gui/meiteminputbus.png}（176×204）；输出窗口在旧版里
 * 借用 AE2 的 skychest，本版为许可干净自绘一张 176×195 的同类面板。这里不 import 任何
 * {@code appeng} 类型。
 */
public final class MeItemBusScreen extends AbstractContainerScreen<MeItemBusMenu> {

    private static final ResourceLocation INPUT =
            new ResourceLocation("modular_machinery_reborn", "textures/gui/me_item_input_bus.png");
    private static final ResourceLocation OUTPUT =
            new ResourceLocation("modular_machinery_reborn", "textures/gui/me_item_output_bus.png");

    public MeItemBusScreen(MeItemBusMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = menu.isInput() ? 204 : 195;
        this.inventoryLabelY = menu.isInput() ? 111 : 101;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        Component status = Component.translatable(this.menu.meOnline()
                ? "screen.modular_machinery_reborn.me_bus.online"
                : "screen.modular_machinery_reborn.me_bus.offline");
        int color = this.menu.meOnline() ? 0x308030 : 0x903030;
        graphics.drawString(this.font, status,
                this.leftPos + this.imageWidth - 8 - this.font.width(status),
                this.topPos + 6, color, false);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(this.menu.isInput() ? INPUT : OUTPUT,
                this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
    }
}
