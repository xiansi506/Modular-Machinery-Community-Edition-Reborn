package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.script.ClientModelRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Resource reload invalidates per-controller animation caches without discarding client script definitions. */
@Mod.EventBusSubscriber(modid = ModularMachineryReborn.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientPresentationLifecycle {
    @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                manager -> { ClientModelRegistry.invalidateCaches(); MachineBloom.reset(); });
    }
}
