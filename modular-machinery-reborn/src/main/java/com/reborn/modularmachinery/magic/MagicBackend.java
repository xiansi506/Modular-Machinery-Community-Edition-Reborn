package com.reborn.modularmachinery.magic;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

/** Optional API code stays behind this common boundary. */
public interface MagicBackend extends MagicPort {
    default void tick() {}
    default void load() {}
    default void revive() {}
    default void unload() {}
    default void invalidate() {}
    default LazyOptional<?> capability(Capability<?> capability) { return null; }
    default boolean acceptsOrb(ItemStack stack) { return false; }
    default int orbCapacity() { return 0; }
    default boolean boundOrb() { return false; }
    default String[] variants() { return new String[]{""}; }
}
