package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.client.ClientSelection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server-to-client selection snapshot: dimension, count, then three ints per position. Full coordinates
 * avoid BlockPos packing truncation. The client filters by dimension, including for late snapshots.
 */
public final class SelectionSyncPacket {

    /** The most positions a single message may announce, for the initial allocation only. */
    private static final int INITIAL_CAPACITY_CAP = 4096;

    private final net.minecraft.resources.ResourceLocation dimension;
    private final List<BlockPos> positions;

    public SelectionSyncPacket(List<BlockPos> positions) {
        this(net.minecraft.world.level.Level.OVERWORLD.location(), positions);
    }

    public SelectionSyncPacket(net.minecraft.resources.ResourceLocation dimension, List<BlockPos> positions) {
        this.dimension = java.util.Objects.requireNonNull(dimension);
        this.positions = List.copyOf(positions);
    }

    public net.minecraft.resources.ResourceLocation dimension() { return dimension; }

    public SelectionSyncPacket(FriendlyByteBuf buf) {
        this.dimension = buf.readResourceLocation();
        int count = buf.readInt();
        if (count < 0 || count > buf.readableBytes() / 12) {
            throw new IllegalArgumentException("Invalid selection position count: " + count);
        }
        List<BlockPos> read = new ArrayList<>(Math.max(0, Math.min(count, INITIAL_CAPACITY_CAP)));
        for (int i = 0; i < count; i++) {
            read.add(new BlockPos(buf.readInt(), buf.readInt(), buf.readInt()));
        }
        this.positions = List.copyOf(read);
    }

    public List<BlockPos> positions() {
        return this.positions;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeInt(this.positions.size());
        for (BlockPos pos : this.positions) {
            buf.writeInt(pos.getX());
            buf.writeInt(pos.getY());
            buf.writeInt(pos.getZ());
        }
    }

    public static void handle(SelectionSyncPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // Client-only, through DistExecutor so no common class ever names the client state directly.
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientSelection.set(message.dimension(), message.positions())));
        context.setPacketHandled(true);
    }

    /** Sends the player's whole selection, which is what the original sent after every click. */
    public static void sendTo(ServerPlayer player, List<BlockPos> positions) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SelectionSyncPacket(player.level().dimension().location(), positions));
    }
}
