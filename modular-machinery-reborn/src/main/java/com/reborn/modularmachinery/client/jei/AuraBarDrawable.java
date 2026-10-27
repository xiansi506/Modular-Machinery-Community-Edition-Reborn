package com.reborn.modularmachinery.client.jei;

import com.mojang.blaze3d.systems.RenderSystem;
import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.magic.MagicFamily;
import com.reborn.modularmachinery.magic.MagicRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import mezz.jei.api.gui.builder.ITooltipBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/** gugu-utils' 6x80 Aura gauge, adapted to the current once/per-tick recipe semantics.
 * Texture and rendering provenance: NOTICE.md and META-INF/licenses/gugu-utils-LICENSE.txt. */
final class AuraBarDrawable implements IDrawable {
    static final int WIDTH = 6, HEIGHT = 80, PADDING_Y = 10;
    static final int PART_HEIGHT = HEIGHT + PADDING_Y * 2;
    static final int GAP_X = 8, GAP_Y = 4;
    // The source uses TileAuraHatch.MAX_AURA / 3; this is a display scale, not a storage limit.
    static final int FULL_SCALE = 500_000 / 3;
    static final int COLOR = 0x53A008;
    // Upstream's PNG is 256x257, so implicit 256x256 UVs would sample the wrong rows.
    static final int TEXTURE_WIDTH = 256, TEXTURE_HEIGHT = 257;
    static final ResourceLocation TEXTURE = new ResourceLocation(ModularMachineryReborn.MOD_ID,
            "textures/gui/gugu_recipes_ui.png");
    private final long total;

    AuraBarDrawable(MagicRequirement requirement, int recipeTime) {
        this.total = totalAmount(requirement, recipeTime);
    }

    static boolean supports(MachineRequirement requirement) {
        return requirement instanceof MagicRequirement magic && magic.family == MagicFamily.AURA;
    }

    static long totalAmount(MagicRequirement requirement, int recipeTime) {
        // Aura amounts and recipe times are bounded ints, so their product fits a signed long.
        return (long) requirement.amount * (requirement.perTick() ? Math.max(1, recipeTime) : 1L);
    }

    static int filledHeight(long amount) {
        if (amount <= 0) return 0;
        if (amount >= FULL_SCALE) return HEIGHT;
        return (int) ((amount * HEIGHT + FULL_SCALE - 1) / FULL_SCALE);
    }

    static boolean contains(MachineRecipeLayout.Cell cell, double x, double y) {
        return x >= cell.x() && x < cell.x() + WIDTH
                && y >= cell.y() + PADDING_Y && y < cell.y() + PADDING_Y + HEIGHT;
    }

    @Override public int getWidth() { return WIDTH; }
    @Override public int getHeight() { return HEIGHT; }
    @Override public void draw(GuiGraphics graphics, int x, int y) {
        draw(graphics, Minecraft.getInstance().font, x, y);
    }

    void draw(GuiGraphics graphics, Font font, int x, int y) {
        int filled = filledHeight(total), empty = HEIGHT - filled;
        float[] previous = RenderSystem.getShaderColor().clone();
        try {
            graphics.setColor(83 / 255F, 160 / 255F, 8 / 255F, .75F);
            if (empty > 0) graphics.blit(TEXTURE, x, y, 0F, 104F, WIDTH, empty, TEXTURE_WIDTH, TEXTURE_HEIGHT);
            if (filled > 0) graphics.blit(TEXTURE, x, y + empty, 6F, 104F + empty, WIDTH, filled,
                    TEXTURE_WIDTH, TEXTURE_HEIGHT);
        } finally {
            graphics.setColor(previous[0], previous[1], previous[2], previous[3]);
        }
        if (total > FULL_SCALE) graphics.drawString(font, "+", x + 4, y - 1, COLOR, true);
    }

    static void addTooltip(ITooltipBuilder tooltip, MagicRequirement requirement, int recipeTime) {
        tooltip.add(Component.literal(requirement.describe()));
        long total = totalAmount(requirement, recipeTime);
        if (requirement.perTick()) tooltip.add(Component.translatable(
                "tooltip.modular_machinery_reborn.aura.total", format(total)));
        tooltip.add(Component.translatable("tooltip.modular_machinery_reborn.aura.scale", format(FULL_SCALE)));
        if (total > FULL_SCALE) tooltip.add(Component.translatable("tooltip.modular_machinery_reborn.aura.overflow"));
        MachineRecipeText.addScriptTooltip(tooltip, requirement);
    }

    private static String format(long amount) { return String.format(Locale.ROOT, "%,d", amount); }
}
