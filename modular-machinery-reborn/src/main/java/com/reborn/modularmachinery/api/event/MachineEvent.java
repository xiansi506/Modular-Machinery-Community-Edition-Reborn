package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.Event;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Base of every controller lifecycle event.
 *
 * <p>The controller is the server-side block entity for recipe events and either side for the client-only
 * events; lifecycle posting happens in {@link MachineControllerBlockEntity#tick}, which runs on the server.
 * {@link #machineId()} is the machine the controller is currently formed as, or {@code null} when the
 * structure does not match anything.
 *
 * <p><b>This is a new API surface, not a byte-for-byte port.</b> The original's lifecycle events were
 * <b>not</b> posted on the Forge bus (its {@code ModularMachinery.EVENT_BUS.post(this)} line is commented
 * out); they went only to component tiles, upgrade handlers and CraftTweaker callbacks. The original's
 * CraftTweaker bridge is not ported (D7), so the events here are posted on the Forge bus, the same way
 * {@code ControllerGuiInfoEvent} already is. The payloads are rebuilt from this port's state machine, which
 * has no {@code RecipeCraftingContext} or {@code RecipeThread} objects.
 */
public abstract class MachineEvent extends Event {

    private final MachineControllerBlockEntity controller;
    @Nullable
    private final ResourceLocation machineId;

    /**
     * For Forge's listener-list probe only: {@code EventListenerHelper} instantiates every concrete event class
     * once through a public no-argument constructor to read its listener list. That probe instance is discarded
     * and never posted, so its fields are null. Real events always use the parameterised constructor below.
     */
    protected MachineEvent() {
        this.controller = null;
        this.machineId = null;
    }

    protected MachineEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.machineId = machineId;
    }

    /** The controller that posted the event; null only on the event bus's internal probe instance. */
    @Nullable
    public MachineControllerBlockEntity controller() {
        return this.controller;
    }

    /** The formed machine's registry name, or {@code null} when no structure matches. */
    @Nullable
    public ResourceLocation machineId() {
        return this.machineId;
    }
}
