package com.reborn.modularmachinery.magic;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.*;
import javax.annotation.Nullable;

public class MagicHatchBlockEntity extends BlockEntity implements MenuProvider {
    private final MagicFamily family;
    private final boolean input;
    private final int manaCapacity;
    private int stored;
    private boolean displayDirty = true;
    private MagicBackend backend;
    private final ItemStackHandler orbs;
    private LazyOptional<IItemHandler> itemCapability;
    private final int[] words = new int[24];
    private boolean clientBound;
    private int clientOrbCapacity;
    @Nullable private Integer machineColor;
    @Nullable public Integer machineColor() { return machineColor; }
    public void setMachineColor(@Nullable Integer color) {
        if (java.util.Objects.equals(machineColor,color)) return;
        machineColor = color; changed();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    public MagicHatchBlockEntity(BlockPos pos, BlockState state) {
        super(MagicBlocks.TYPE.get(), pos, state);
        MagicHatchBlock block = (MagicHatchBlock) state.getBlock(); family = block.family; input = block.input;
        manaCapacity = MagicConfig.manaCapacity();
        orbs = new ItemStackHandler(family == MagicFamily.LIFEESSENCE ? 1 : 0) {
            @Override public int getSlotLimit(int slot) { return 1; }
            @Override public boolean isItemValid(int slot, ItemStack stack) { return backend != null && backend.acceptsOrb(stack); }
            @Override protected void onContentsChanged(int slot) { changed(); }
        };
        backend = switch (family) {
            case MANA -> new com.reborn.modularmachinery.compat.magic.BotaniaBackend(this);
            case AURA -> new com.reborn.modularmachinery.compat.magic.AuraBackend(this);
            case LIFEESSENCE, WILL -> new com.reborn.modularmachinery.compat.magic.BloodMagicBackend(this);
        };
        itemCapability = LazyOptional.of(() -> orbs);
    }
    public MagicFamily family() { return family; }
    public boolean input() { return input; }
    public MagicBackend backend() { return backend; }
    public ItemStackHandler orbs() { return orbs; }
    public int manaCapacity() { return manaCapacity; }
    public int stored() { return stored; }
    public void stored(int amount) {
        int next = Math.max(0, Math.min(family == MagicFamily.MANA ? manaCapacity : Integer.MAX_VALUE, amount));
        if (next != stored) { stored = next; changed(); }
    }
    public void changed() { setChanged(); displayDirty = true; }
    public void serverTick() {
        if (level == null || level.isClientSide || isRemoved()) return;
        backend.tick();
        if (level.getGameTime() % 5 == 0 && (displayDirty || family == MagicFamily.AURA || family == MagicFamily.WILL)) {
            displayDirty = false; level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }
    public double value(int index) {
        if (level == null || level.isClientSide) return decode(index);
        if (index == 5) return family == MagicFamily.MANA ? manaCapacity : family == MagicFamily.LIFEESSENCE ? (input ? backend.orbCapacity()/10 : backend.orbCapacity()) : family == MagicFamily.WILL ? 100 : 1000000;
        String[] variants = backend.variants();
        if (index >= variants.length) return 0;
        MagicPort.Access access = backend.access(variants[index]);
        return access == null ? 0 : access.amount();
    }
    public int menuWord(int index) { return level == null || level.isClientSide ? words[index] : (int)(Double.doubleToRawLongBits(value(index / 4)) >>> ((index % 4) * 16)) & 0xffff; }
    public void menuWord(int index, int value) { words[index] = value & 0xffff; }
    private double decode(int index) {
        int at = index * 4; long bits = 0;
        for (int i = 0; i < 4; i++) bits |= (words[at+i] & 0xffffL) << (i*16);
        double value = Double.longBitsToDouble(bits); return Double.isFinite(value) ? value : 0;
    }
    public boolean boundOrb() { return level != null && !level.isClientSide ? backend.boundOrb() : clientBound; }
    public int orbCapacity() { return level != null && !level.isClientSide ? backend.orbCapacity() : clientOrbCapacity; }
    @Override public Component getDisplayName() { return getBlockState().getBlock().getName(); }
    @Override public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new MagicHatchMenu(id, inventory, this); }
    @Override public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER && family == MagicFamily.LIFEESSENCE) return itemCapability.cast();
        LazyOptional<?> optional = backend.capability(capability);
        return optional == null ? super.getCapability(capability, side) : optional.cast();
    }
    @Override public void invalidateCaps() { super.invalidateCaps(); itemCapability.invalidate(); backend.invalidate(); }
    @Override public void reviveCaps() { super.reviveCaps(); itemCapability = LazyOptional.of(() -> orbs); backend.revive(); }
    @Override public void onLoad() { super.onLoad(); backend.load(); }
    @Override public void setRemoved() { backend.unload(); super.setRemoved(); }
    @Override public void onChunkUnloaded() { backend.unload(); super.onChunkUnloaded(); }
    @Override protected void saveAdditional(CompoundTag tag) { super.saveAdditional(tag); tag.putInt("stored", stored); tag.put("orb", orbs.serializeNBT()); if (machineColor != null) tag.putInt("machineColor",machineColor); }
    @Override public void load(CompoundTag tag) { super.load(tag); orbs.deserializeNBT(tag.getCompound("orb")); stored = Math.max(0, tag.getInt("stored")); if (family == MagicFamily.MANA) stored = Math.min(stored, manaCapacity); machineColor = tag.contains("machineColor") ? tag.getInt("machineColor") : null; }
    @Override public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        for (int i = 0; i < 6; i++) tag.putDouble("value" + i, value(i));
        tag.putBoolean("bound", backend.boundOrb()); tag.putInt("orbCapacity", backend.orbCapacity()); if (machineColor != null) tag.putInt("machineColor",machineColor); return tag;
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void handleUpdateTag(CompoundTag tag) {
        Integer previous = machineColor;
        machineColor = tag.contains("machineColor") ? tag.getInt("machineColor") : null;
        for (int i = 0; i < 6; i++) { long bits = Double.doubleToRawLongBits(tag.getDouble("value"+i)); for (int j = 0; j < 4; j++) words[i*4+j] = (int)(bits >>> (j*16)) & 0xffff; }
        stored = (int) Math.max(0, Math.min(Integer.MAX_VALUE, decode(0)));
        clientBound = tag.getBoolean("bound"); clientOrbCapacity = tag.getInt("orbCapacity");
        if (level != null && !java.util.Objects.equals(previous,machineColor)) level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),Block.UPDATE_CLIENTS);
    }
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) { if (packet.getTag() != null) handleUpdateTag(packet.getTag()); }
}
