package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.selection.ConstructToolService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;

public record ConstructToolActionPacket(net.minecraft.resources.ResourceLocation dimension,ConstructToolService.Action action,long revision,String options){
    public ConstructToolActionPacket(FriendlyByteBuf buf){this(buf.readResourceLocation(),buf.readEnum(ConstructToolService.Action.class),buf.readLong(),buf.readUtf(16384));}
    public void encode(FriendlyByteBuf buf){buf.writeResourceLocation(dimension);buf.writeEnum(action);buf.writeLong(revision);buf.writeUtf(options,16384);}
    public static void handle(ConstructToolActionPacket packet,Supplier<NetworkEvent.Context> supplier){
        NetworkEvent.Context context=supplier.get();context.enqueueWork(()->{
            var player=context.getSender();
            if(player!=null&&player.level().dimension().location().equals(packet.dimension()))ConstructToolService.handle(player,packet.action(),packet.revision(),packet.options());
        });context.setPacketHandled(true);
    }
}
