package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Posted once when a controller's structure check turns "not formed" into "formed".
 *
 * <p>It is followed immediately by {@link MachineStructureUpdateEvent}, the original's order
 * ({@code TileMultiblockMachineController#onStructureFormed}: Formed, then Update). A machine that changes
 * from one definition to another while staying formed only produces an update event.
 */
public final class MachineStructureFormedEvent extends MachineEvent {

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public MachineStructureFormedEvent() {
    }

    public MachineStructureFormedEvent(MachineControllerBlockEntity controller,
                                       @Nullable ResourceLocation machineId) {
        super(controller, machineId);
    }
}
