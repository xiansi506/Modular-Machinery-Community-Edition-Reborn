package com.reborn.modularmachinery.client.jei;

import com.reborn.modularmachinery.recipe.MachineRequirement;
import mezz.jei.api.gui.drawable.IDrawable;
import net.minecraft.client.gui.GuiGraphics;

/** A resource text cell with a color cue; category tooltips carry the full description. */
final class MagicTankDrawable implements IDrawable {
    private final MachineRequirement requirement;
    MagicTankDrawable(MachineRequirement requirement) { this.requirement = requirement; }
    public int getWidth() { return 18; }
    public int getHeight() { return 18; }
    public void draw(GuiGraphics graphics, int x, int y) {
        MachineRecipeAtlas.CELL.draw(graphics,x,y);
        graphics.fill(x+1,y+1,x+17,y+3,requirement.tankTint());
        MachineRecipeCategory.drawTextInCell(graphics,new MachineRecipeLayout.Cell(x,y),requirement.describe());
    }
}
