package com.reborn.modularmachinery.client.jei;

import com.reborn.modularmachinery.client.GuiGasRenderer;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.client.gui.GuiGraphics;

/** Empty chemical slots still draw their background in JEI 15.49; draw the sprite and shell in that pass. */
final class GasTankDrawable implements IDrawable {
    private final MachineRequirement requirement;
    GasTankDrawable(MachineRequirement requirement) { this.requirement = requirement; }
    @Override public int getWidth() { return 18; }
    @Override public int getHeight() { return 18; }
    @Override public void draw(GuiGraphics graphics, int x, int y) {
        MachineRecipeAtlas.CELL.draw(graphics, x, y);
        if (GuiGasRenderer.draw(graphics, requirement.tankTexture(), requirement.tankTint(), x, y, 18, 18)) {
            MachineRecipeAtlas.GAS_SHELL.draw(graphics, x, y);
        } else {
            String text = requirement.describe();
            if (text != null && !text.isEmpty())
                MachineRecipeCategory.drawTextInCell(graphics, new MachineRecipeLayout.Cell(x, y), text);
        }
    }
}
