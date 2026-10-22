package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * Posted after every successful structure check, including the check that formed the machine.
 *
 * <p>The original posted it from {@code TileMultiblockMachineController:556-557}; a controller whose
 * structure check is skipped by the scheduler (A10) does not post it, exactly as the original only posted it
 * when it actually re-checked.
 */
public final class MachineStructureUpdateEvent extends MachineEvent {

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public MachineStructureUpdateEvent() {
    }

    public MachineStructureUpdateEvent(MachineControllerBlockEntity controller,
                                       @Nullable ResourceLocation machineId) {
        super(controller, machineId);
    }
}
