package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineRegistry;
import com.reborn.modularmachinery.machine.MachineStructures;
import com.reborn.modularmachinery.render.WorldHidingHooks;
import com.reborn.modularmachinery.script.ClientModelRegistry;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Only models successfully drawn in the real client world may hide terrain. Preview worlds are excluded. */
public final class WorldComponentHider {
    private static final Map<Long, Tracked> TRACKED = new HashMap<>();
    private static ClientLevel world;

    private static final class Tracked {
        final WeakReference<MachineControllerBlockEntity> controller;
        MachineDefinition definition;
        long generation;
        boolean modelAvailable;
        Set<BlockPos> offsets;
        Object dynamicPatterns;
        int rotation;
        LongSet parts = LongSets.EMPTY_SET;
        Tracked(MachineControllerBlockEntity controller) { this.controller = new WeakReference<>(controller); }
    }

    private WorldComponentHider() {}

    public static void modelRendered(MachineControllerBlockEntity controller, boolean success) {
        if (!(controller.getLevel() instanceof ClientLevel level) || Minecraft.getInstance().level != level) return;
        if (world != level) reset(level);
        long owner = controller.getBlockPos().asLong();
        Tracked tracked = TRACKED.get(owner);
        if (tracked == null || tracked.controller.get() != controller) {
            if (!success) return;
            tracked = new Tracked(controller);
            TRACKED.put(owner, tracked);
        }
        MachineDefinition definition = MachineRegistry.clientById(controller.machineId()).orElse(null);
        if (tracked.definition != definition) tracked.offsets = null;
        tracked.definition = definition;
        tracked.generation = ClientModelRegistry.generation();
        tracked.modelAvailable = success;
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (world != minecraft.level) reset(minecraft.level);
        if (world == null) return;
        Map<Long, LongSet> contributions = new HashMap<>();
        TRACKED.entrySet().removeIf(entry -> {
            Tracked tracked = entry.getValue();
            MachineControllerBlockEntity controller = tracked.controller.get();
            if (controller == null || controller.isRemoved() || controller.getLevel() != world
                    || world.getBlockEntity(controller.getBlockPos()) != controller) return true;
            MachineDefinition definition = MachineRegistry.clientById(controller.machineId()).orElse(null);
            if (!controller.isFormed() || definition == null || definition != tracked.definition
                    || tracked.generation != ClientModelRegistry.generation()) tracked.modelAvailable = false;
            if (!tracked.modelAvailable) return false;
            if (definition.hideComponentsWhenFormed()) {
                Set<BlockPos> offsets = controller.formedPartOffsets();
                if (tracked.offsets != offsets || tracked.dynamicPatterns != controller.dynamicPatterns()
                        || tracked.rotation != controller.rotationSteps()) {
                    tracked.offsets = offsets;
                    tracked.dynamicPatterns = controller.dynamicPatterns();
                    tracked.rotation = controller.rotationSteps();
                    // Older saves did not send the matched pattern. Reconstruct only until the server's first check.
                    Set<BlockPos> effective = controller.hasFormedPartSnapshot() ? offsets
                            : MachineStructures.runtimeStructure(controller, definition).positions().keySet();
                    LongSet parts = new LongOpenHashSet();
                    for (BlockPos offset : effective) parts.add(controller.getBlockPos().offset(offset).asLong());
                    tracked.parts = LongSets.unmodifiable(parts);
                }
                contributions.put(entry.getKey(), tracked.parts);
            } else contributions.put(entry.getKey(), LongSets.EMPTY_SET);
            return false;
        });
        for (long section : WorldHidingHooks.INDEX.replace(world, contributions)) {
            // This public renderer API also reaches Embeddium's section rebuild scheduler.
            int x = SectionPos.x(section) << 4, y = SectionPos.y(section) << 4, z = SectionPos.z(section) << 4;
            minecraft.levelRenderer.setBlocksDirty(x, y, z, x + 15, y + 15, z + 15);
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) { reset(null); }

    private static void reset(ClientLevel next) {
        TRACKED.clear();
        WorldHidingHooks.INDEX.replace(null, Map.of());
        world = next;
    }
}
