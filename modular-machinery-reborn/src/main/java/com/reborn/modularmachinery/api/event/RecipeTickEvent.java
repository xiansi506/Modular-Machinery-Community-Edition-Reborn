package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Posted around one successful craft tick: {@link Phase#START} before this tick's requirements are paid,
 * {@link Phase#END} after they were paid and before the controller counts the tick.
 *
 * <p>{@link #preventProgressing(String)} is the original's {@code RecipeTickEvent#preventProgressing}: the
 * craft stays armed but this tick does not advance it. On {@code START} the requirements for this tick are
 * never drawn; on {@code END} they were already drawn (the original also could not refund them), so the net
 * effect there is one tick of resources spent for no progress — the original's own behaviour.
 *
 * <p><b>Not ported yet:</b> {@code setFailed(destruct, reason)}. A per-tick requirement in this port has no
 * failure message to report ({@code MachineRequirement} only exposes {@code startFailure}), and destructing a
 * craft on an addon's word without a visible cause repeats the failure this project keeps fixing. The
 * existing {@code cancelIfPerTickFails} path still handles the recipe's own flag.
 */
public final class RecipeTickEvent extends RecipeEvent {

    private final Phase phase;
    private boolean preventProgressing;
    private String preventReason = "";

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public RecipeTickEvent() {
        this.phase = null;
    }

    public RecipeTickEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                           Phase phase, MachineRecipe recipe, int progress, int maxProgress, int parallelism) {
        super(controller, machineId, recipe, progress, maxProgress, parallelism);
        this.phase = Objects.requireNonNull(phase, "phase");
    }

    public Phase phase() {
        return this.phase;
    }

    /** Holds this tick without advancing the craft; {@code reason} is for listeners and logs, not the GUI. */
    public void preventProgressing(String reason) {
        this.preventProgressing = true;
        this.preventReason = reason == null ? "" : reason;
    }

    public boolean isPreventProgressing() {
        return this.preventProgressing;
    }

    public String preventReason() {
        return this.preventReason;
    }
}
