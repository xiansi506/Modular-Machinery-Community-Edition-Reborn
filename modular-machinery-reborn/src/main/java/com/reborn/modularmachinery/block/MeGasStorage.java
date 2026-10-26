package com.reborn.modularmachinery.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nullable;

/** Optional gas side of a unified ME bus; common code never links Mekanism or AppMek classes. */
public interface MeGasStorage {
    int SLOTS = 9;
    int CAPACITY = 8000;
    int CONFIG_SLOTS = 16;
    record Entry(ResourceLocation id, long amount, Component name, int color) { }
    interface Network {
        long insert(ResourceLocation gas, long amount);
        long extract(ResourceLocation gas, long amount);
    }
    @Nullable Entry contents(int tank);
    @Nullable Entry mark(int slot);
    boolean setMark(int slot, @Nullable ResourceLocation gas, int amount);
    @Nullable Entry containedGas(ItemStack stack);
    Object handler();
    boolean tick(boolean input, Network network);
    CompoundTag save();
    void load(CompoundTag tag);
}
