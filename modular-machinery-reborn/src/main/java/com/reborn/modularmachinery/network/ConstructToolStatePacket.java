package com.reborn.modularmachinery.network;

import com.google.gson.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import java.util.function.Supplier;

/** A bounded preview page, not a client-controlled export source. */
public record ConstructToolStatePacket(String state,boolean open,int tab){
    public ConstructToolStatePacket(FriendlyByteBuf buf){this(buf.readUtf(131072),buf.readBoolean(),buf.readVarInt());}
    public void encode(FriendlyByteBuf buf){buf.writeUtf(state,131072);buf.writeBoolean(open);buf.writeVarInt(tab);}
    public static void handle(ConstructToolStatePacket packet,Supplier<NetworkEvent.Context> supplier){
        NetworkEvent.Context context=supplier.get();context.enqueueWork(()->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            ()->()->com.reborn.modularmachinery.client.ConstructToolClient.accept(JsonParser.parseString(packet.state()).getAsJsonObject(),packet.open(),packet.tab())));context.setPacketHandled(true);
    }
    public static void sendTo(ServerPlayer player,JsonObject state,boolean open,int tab){
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(()->player),new ConstructToolStatePacket(state.toString(),open,tab));
    }
}
