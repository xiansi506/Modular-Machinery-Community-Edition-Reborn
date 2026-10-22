package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Posted once at each end of a server tick of the controller, whether or not the machine is formed.
 *
 * <p>The original posted {@code MachineTickEvent(Phase.START)} at
 * {@code TileMultiblockMachineController:123} and {@code Phase.END} at {@code :136}, wrapping the whole
 * restricted tick. This port wraps {@code MachineControllerBlockEntity#tick}'s server half the same way, so
 * every early return still produces the matching END.
 *
 * <p>The event is a notification only: a listener cannot stop the tick or change its outcome.
 */
public final class MachineTickEvent extends MachineEvent {

    private final Phase phase;

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public MachineTickEvent() {
        this.phase = null;
    }

    public MachineTickEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                            Phase phase) {
        super(controller, machineId);
        this.phase = Objects.requireNonNull(phase, "phase");
    }

    public Phase phase() {
        return this.phase;
    }
}
