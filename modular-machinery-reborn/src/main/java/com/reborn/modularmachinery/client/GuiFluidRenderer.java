package com.reborn.modularmachinery.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;

/** Fluid sprites come from the block atlas, including animation and the fluid's own tint. */
public final class GuiFluidRenderer {
    private GuiFluidRenderer() { }

    public static void draw(GuiGraphics graphics, FluidStack fluid, int x, int y, int width, int height) {
        if (fluid.isEmpty() || width <= 0 || height <= 0) return;
        var extensions = IClientFluidTypeExtensions.of(fluid.getFluid());
        int tint = extensions.getTintColor(fluid);
        ResourceLocation texture = extensions.getStillTexture(fluid);
        if (texture == null) {
            graphics.fill(x, y, x + width, y + height, tint);
            return;
        }
        var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
        graphics.enableScissor(x, y, x + width, y + height);
        try {
            graphics.setColor(((tint >>> 16) & 255) / 255F, ((tint >>> 8) & 255) / 255F,
                    (tint & 255) / 255F, ((tint >>> 24) & 255) / 255F);
            for (int dx = 0; dx < width; dx += 16) {
                for (int dy = 0; dy < height; dy += 16) {
                    graphics.blit(x + dx, y + dy, 0, 16, 16, sprite);
                }
            }
        } finally {
            graphics.setColor(1F, 1F, 1F, 1F);
            graphics.disableScissor();
        }
    }
}
