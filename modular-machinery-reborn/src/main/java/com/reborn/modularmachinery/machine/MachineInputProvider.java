package com.reborn.modularmachinery.machine;

/** Inputs supplied by a component with its own storage, independent of ordinary hatch block classes. */
public interface MachineInputProvider {
    @FunctionalInterface interface GroupAllocator { int reserve(Object identity, int preferred); }
    default int declaredInputGroup() { return -1; }
    Object inputIdentity();
    void appendInputs(HatchCollection.Builder builder, String selectorTag, GroupAllocator groups);
}
