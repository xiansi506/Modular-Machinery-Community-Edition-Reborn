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
        if (requirement instanceof com.reborn.modularmachinery.magic.MagicRequirement resource
                && (resource.family == com.reborn.modularmachinery.magic.MagicFamily.AIR || resource.family == com.reborn.modularmachinery.magic.MagicFamily.EMBER)) {
            double scale = resource.family == com.reborn.modularmachinery.magic.MagicFamily.EMBER
                    ? com.reborn.modularmachinery.magic.MagicConfig.emberCapacity() : com.reborn.modularmachinery.magic.MagicConfig.airVolume()*20d;
            int fill = resource.amount <= 0 || scale <= 0 ? 0 : (int)Math.ceil(Math.min(1,resource.amount/scale)*14);
            graphics.fill(x+2,y+2,x+16,y+16,0xFF292929);
            if (fill > 0) graphics.fill(x+2,y+16-fill,x+16,y+16,resource.family.color);
            MachineRecipeCategory.drawTextInCell(graphics,new MachineRecipeLayout.Cell(x,y),
                    resource.family == com.reborn.modularmachinery.magic.MagicFamily.AIR ? "Air" : "Ember");
            return;
        }
        graphics.fill(x+1,y+1,x+17,y+3,requirement.tankTint());
        MachineRecipeCategory.drawTextInCell(graphics,new MachineRecipeLayout.Cell(x,y),requirement.describe());
    }
}
