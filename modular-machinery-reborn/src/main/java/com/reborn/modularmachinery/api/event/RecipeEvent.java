package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Base of the events posted while one single-craft recipe runs.
 *
 * <p>The original carried {@code ActiveMachineRecipe}, {@code RecipeCraftingContext} and
 * {@code RecipeThread}; this port has none of those objects, so an event carries the recipe and the numbers
 * the controller is acting on: {@link #progress()} before the action, {@link #maxProgress()} as recomputed
 * for this craft, and the recipe's current {@link #parallelism()}.
 */
public abstract class RecipeEvent extends MachineEvent {

    private final MachineRecipe recipe;
    private final int progress;
    private final int maxProgress;
    private final int parallelism;

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    protected RecipeEvent() {
        this.recipe = null;
        this.progress = 0;
        this.maxProgress = 0;
        this.parallelism = 0;
    }

    protected RecipeEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                          MachineRecipe recipe, int progress, int maxProgress, int parallelism) {
        super(controller, machineId);
        this.recipe = Objects.requireNonNull(recipe, "recipe");
        this.progress = progress;
        this.maxProgress = maxProgress;
        this.parallelism = parallelism;
    }

    public MachineRecipe recipe() {
        return this.recipe;
    }

    /** The progress before the action this event announces, in ticks. */
    public int progress() {
        return this.progress;
    }

    public int maxProgress() {
        return this.maxProgress;
    }

    /** The parallelism fixed for this craft, i.e. what the recipe's requirements scale by. */
    public int parallelism() {
        return this.parallelism;
    }
}
