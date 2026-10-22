package com.reborn.modularmachinery.api.event;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.IEventBus;

import javax.annotation.Nullable;

/**
 * The posting helper every lifecycle event goes through.
 *
 * <p>It exists so the wiring ("does the controller post this event, and with what?") can be exercised
 * without a running Forge mod loader: the bus is resolved lazily, and the offline harness replaces the
 * private field with a plain {@code BusBuilder} bus before touching any dispatch code. Production resolves
 * {@link MinecraftForge#EVENT_BUS}, which is the same bus {@code ControllerGuiInfoEvent} uses.
 *
 * <p>Not a public subscription surface: addons subscribe with {@code @SubscribeEvent} and never call this.
 */
public final class MachineEvents {

    @Nullable
    private static volatile IEventBus bus;

    private MachineEvents() {
    }

    /** The bus events are posted to; {@code MinecraftForge.EVENT_BUS} unless a test replaced it. */
    public static IEventBus bus() {
        IEventBus current = bus;
        if (current == null) {
            synchronized (MachineEvents.class) {
                current = bus;
                if (current == null) {
                    current = MinecraftForge.EVENT_BUS;
                    bus = current;
                }
            }
        }
        return current;
    }

    /** Posts {@code event} and returns it, so a caller can inspect what listeners wrote. */
    public static <T extends Event> T post(T event) {
        bus().post(event);
        return event;
    }
}
