package com.reborn.modularmachinery.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/** Stores the machine-instance tint for a structural casing block. */
public final class MachineCasingBlockEntity extends BlockEntity {

    private static final String TAG_COLOR = "machineColor";

    @Nullable private Integer machineColor;

    public MachineCasingBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.CASING_ENTITY.get(), pos, state);
    }

    @Nullable
    public Integer machineColor() {
        return this.machineColor;
    }

    /** Set by the formed machine controller; updates both saved data and the client render state. */
    public void setMachineColor(@Nullable Integer color) {
        if (java.util.Objects.equals(this.machineColor, color)) {
            return;
        }
        this.machineColor = color;
        setChanged();
        if (this.level != null && !this.level.isClientSide) {
            this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.machineColor != null) {
            tag.putInt(TAG_COLOR, this.machineColor);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.machineColor = tag.contains(TAG_COLOR) ? tag.getInt(TAG_COLOR) : null;
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        Integer previousColor = this.machineColor;
        super.onDataPacket(connection, packet);
        refreshTintIfChanged(previousColor);
    }

    private void refreshTintIfChanged(@Nullable Integer previousColor) {
        if (this.level != null && this.level.isClientSide
                && !java.util.Objects.equals(previousColor, this.machineColor)) {
            // A BE data packet can change tint without changing BlockState. Explicitly dirty the client render
            // section so the new tint is visible immediately instead of on the next unrelated block update.
            this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
