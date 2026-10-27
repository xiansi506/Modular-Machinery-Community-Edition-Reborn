package com.reborn.modularmachinery.machine;

/** Inputs supplied by a component with its own storage, independent of ordinary hatch block classes. */
public interface MachineInputProvider {
    @FunctionalInterface interface GroupAllocator { int reserve(Object identity, int preferred); }
    default int declaredInputGroup() { return -1; }
    Object inputIdentity();
    /** Remote positions that can change this entrance, even while the remote chunk is unloaded. */
    default java.util.Collection<net.minecraft.core.BlockPos> inputDependencies() { return java.util.List.of(); }
    void appendInputs(HatchCollection.Builder builder, String selectorTag, GroupAllocator groups);
}
