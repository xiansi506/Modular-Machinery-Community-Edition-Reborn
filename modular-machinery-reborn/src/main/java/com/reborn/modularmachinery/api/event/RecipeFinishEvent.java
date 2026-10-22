package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Posted after the outputs were settled and before the controller clears the craft's state.
 *
 * <p>The original posted this from {@code TileMachineController#onFinished:299-306} after the recipe thread
 * completed; the outputs had already been produced. This port settles each output's chance inside
 * {@code MachineRecipe#finish}, so the same ordering holds: finish, then this event, then the finish commands
 * and the state reset.
 */
public final class RecipeFinishEvent extends RecipeEvent {

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public RecipeFinishEvent() {
    }

    public RecipeFinishEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                             MachineRecipe recipe, int progress, int maxProgress, int parallelism) {
        super(controller, machineId, recipe, progress, maxProgress, parallelism);
    }
}
