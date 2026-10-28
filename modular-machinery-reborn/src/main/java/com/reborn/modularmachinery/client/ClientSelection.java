package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.selection.StructureSelection;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

/** The dimension is carried with the snapshot so a late packet cannot draw in another world. */
public final class ClientSelection {
    private static StructureSelection selection = new StructureSelection();
    private static ResourceLocation dimension;
    private ClientSelection() {}

    public static void set(ResourceLocation world, List<BlockPos> positions) {
        dimension = world;
        selection = StructureSelection.of(positions);
    }
    public static List<BlockPos> positions(ResourceLocation world) {
        return world.equals(dimension) ? selection.positions() : List.of();
    }
    public static List<BlockPos> positions() { return selection.positions(); }
    public static void clear() { dimension = null; selection = new StructureSelection(); }
}
