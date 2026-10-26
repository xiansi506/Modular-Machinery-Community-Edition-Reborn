package com.reborn.modularmachinery.render;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/** Small guards called by the render coremod. This class must also be safe to load on a dedicated server. */
public final class WorldHidingHooks {
    /** Added only to terrain render views, never to the gameplay world. */
    public interface RenderView { Object mmr$visibilityWorld(); }

    public static final WorldVisibilityIndex INDEX = new WorldVisibilityIndex();

    private WorldHidingHooks() {}

    private static Object world(Object view) {
        return view instanceof RenderView renderView ? renderView.mmr$visibilityWorld() : view;
    }

    public static boolean hiddenModel(Object view, BlockPos pos) {
        var mask = INDEX.snapshot();
        return mask.matches(world(view)) && mask.models().contains(pos.asLong());
    }

    public static boolean hiddenComponent(Object view, BlockPos pos) {
        var mask = INDEX.snapshot();
        return mask.matches(world(view)) && mask.components().contains(pos.asLong());
    }

    public static boolean hiddenComponentAt(Object view, int x, int y, int z) {
        var mask = INDEX.snapshot();
        return mask.matches(world(view)) && mask.components().contains(BlockPos.asLong(x, y, z));
    }

    public static boolean hiddenNeighbor(Object view, BlockPos pos, Direction side) {
        var mask = INDEX.snapshot();
        return mask.matches(world(view)) && mask.models().contains(BlockPos.asLong(
                pos.getX() + side.getStepX(), pos.getY() + side.getStepY(), pos.getZ() + side.getStepZ()));
    }

    public static boolean hiddenBlockEntity(BlockEntity entity) {
        return hiddenComponent(entity.getLevel(), entity.getBlockPos());
    }

    public static BlockState air() { return Blocks.AIR.defaultBlockState(); }
    public static FluidState emptyFluid() { return Fluids.EMPTY.defaultFluidState(); }
}
