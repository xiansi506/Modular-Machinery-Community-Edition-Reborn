package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Posted around the start gate of a craft: {@link Phase#START} before {@code canStart}/{@code canTick} are
 * asked, {@link Phase#END} after they both answered yes and before the one-shot inputs are paid.
 *
 * <p>The original's {@code RecipeCheckEvent} could also fail the craft and lower parallelism
 * ({@code TileMultiblockMachineController:1020-1049}). This slice keeps the half that has a defined meaning in
 * this state machine: {@link #setParallelism(int)} lowers the parallelism used for <b>this</b> start, the
 * original's {@code min(recipe.getParallelism(), requested)}. The result is clamped to
 * {@code 1..computed} when the controller resolves it, so an event can never raise the machine's ceiling or
 * ask for zero copies.
 *
 * <p><b>Not ported yet:</b> {@code setFailed}. This port has no failure-message path that can carry an
 * addon's own translation key to the client (the menu mirrors a fixed integer list), so a failed check would
 * show a bare "idle" — the exact invisible failure the project forbids. Failure events are C2b, together with
 * the requirement API that can produce a per-tick cause.
 */
public final class RecipeCheckEvent extends RecipeEvent {

    private final Phase phase;
    private int parallelism;

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public RecipeCheckEvent() {
        this.phase = null;
        this.parallelism = 0;
    }

    public RecipeCheckEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                            Phase phase, MachineRecipe recipe, int progress, int maxProgress, int parallelism) {
        super(controller, machineId, recipe, progress, maxProgress, parallelism);
        this.phase = Objects.requireNonNull(phase, "phase");
        this.parallelism = parallelism;
    }

    public Phase phase() {
        return this.phase;
    }

    /** The parallelism the event currently asks for; starts at the controller's computed value. */
    public int requestedParallelism() {
        return this.parallelism;
    }

    /**
     * Lowers (or raises, up to the computed value) the parallelism used for this start. The controller clamps
     * the answer when it resolves it; this setter stores exactly what the listener wrote so the listener can
     * read back its own request.
     */
    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    /**
     * The parallelism the controller should use: the smaller of the computed value and the requested one,
     * never below one. This is the original's {@code Math.min(activeRecipe.getParallelism(), requested)} with
     * the lower bound the recipe setter already applies.
     */
    public int resolvedParallelism(int computed) {
        return Math.max(1, Math.min(computed, this.parallelism));
    }
}
