package com.reborn.modularmachinery.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import java.util.function.Function;

/** Draws the animated chemical atlas icon without changing the caller's tint or scissor state. */
public final class GuiGasRenderer {
    private GuiGasRenderer() {}
    public static boolean draw(GuiGraphics graphics, ResourceLocation texture, int tint,
                               int x, int y, int width, int height) {
        if (texture == null || width <= 0 || height <= 0) return false;
        return draw(graphics, texture, tint, x, y, width, height,
                Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS));
    }
    public static boolean draw(GuiGraphics graphics, ResourceLocation texture, int tint,
                               int x, int y, int width, int height,
                               Function<ResourceLocation, TextureAtlasSprite> sprites) {
        if (texture == null || width <= 0 || height <= 0) return false;
        TextureAtlasSprite sprite = sprites.apply(texture);
        if (sprite == null || sprite.contents().name().equals(MissingTextureAtlasSprite.getLocation())) return false;
        graphics.blit(x, y, 0, width, height, sprite,
                ((tint >>> 16) & 255) / 255F, ((tint >>> 8) & 255) / 255F,
                (tint & 255) / 255F, ((tint >>> 24) & 255) / 255F);
        return true;
    }
}
