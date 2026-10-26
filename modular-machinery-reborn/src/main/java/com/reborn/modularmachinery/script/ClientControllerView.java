package com.reborn.modularmachinery.script;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.UUID;

/** Read-only view of the last state received by a client; no server mutation methods are exposed. */
public final class ClientControllerView {
    private final MachineControllerBlockEntity controller;
    public ClientControllerView(MachineControllerBlockEntity controller) { this.controller = controller; }
    public ResourceLocation getMachine() { return controller.machineId(); }
    public BlockPos getPos() { return controller.getBlockPos(); }
    public Level getWorld() { return controller.getLevel(); }
    public BlockState getBlockState() { return controller.getBlockState(); }
    public boolean isFormed() { return controller.isFormed(); }
    public boolean isWorking() { return controller.isWorking(); }
    public boolean isFactory() { return controller.isFactory(); }
    public int getProgress() { return controller.progress(); }
    public int getDuration() { return controller.maxProgress(); }
    public int getParallelism() { return controller.activeParallelism(); }
    public ResourceLocation getActiveRecipe() { return controller.scriptActiveRecipeId(); }
    public UUID getOwnerUUID() { return controller.getOwner(); }
    public String getStatusMessage() { return controller.scriptState().status(); }
    public CompoundTag getCustomData() { return controller.scriptState().data().copy(); }
}
