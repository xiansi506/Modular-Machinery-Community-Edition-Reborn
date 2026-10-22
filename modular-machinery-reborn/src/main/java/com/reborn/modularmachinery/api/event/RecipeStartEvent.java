package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Posted once a craft has really begun: after the start gate said yes, after the one-shot inputs were accepted,
 * and before the controller records the craft as active.
 *
 * <p>The original posted this immediately before {@code activeRecipe.start(...)} and started the craft
 * <b>regardless of cancellation</b> ({@code TileMachineController:265-275}); cancelling only stopped later
 * handlers. This slice therefore keeps the event read-only — there is no cancellation flag to misunderstand.
 * It is posted after {@code start()} succeeded rather than before it because this port's {@code start()} can
 * refuse a one-shot requirement; announcing a craft that did not begin would be false.
 */
public final class RecipeStartEvent extends RecipeEvent {

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public RecipeStartEvent() {
    }

    public RecipeStartEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                            MachineRecipe recipe, int progress, int maxProgress, int parallelism) {
        super(controller, machineId, recipe, progress, maxProgress, parallelism);
    }
}
