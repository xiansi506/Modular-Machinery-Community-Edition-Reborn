package com.reborn.modularmachinery.network;

import com.google.gson.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import java.util.function.Supplier;

public record RecipeEditorStatePacket(String state, boolean open) {
    public RecipeEditorStatePacket(FriendlyByteBuf buf) { this(buf.readUtf(262144),buf.readBoolean()); }
    public void encode(FriendlyByteBuf buf) { buf.writeUtf(state,262144); buf.writeBoolean(open); }
    public static void handle(RecipeEditorStatePacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->com.reborn.modularmachinery.client.RecipeEditorScreen.accept(JsonParser.parseString(packet.state).getAsJsonObject(),packet.open))); ctx.setPacketHandled(true);
    }
    public static void sendTo(ServerPlayer player, JsonObject state, boolean open) { ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new RecipeEditorStatePacket(state.toString(),open)); }
}
