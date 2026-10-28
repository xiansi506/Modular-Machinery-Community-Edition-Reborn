package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.authoring.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;
import java.util.UUID;
import java.util.function.Supplier;

public record RecipeEditorActionPacket(UUID token, long revision, RecipeEditorService.Action action, String id, String source, RecipeDocument.Format format) {
    public RecipeEditorActionPacket(FriendlyByteBuf buf) { this(buf.readUUID(),buf.readLong(),buf.readEnum(RecipeEditorService.Action.class),buf.readUtf(256),buf.readUtf(RecipeDocument.MAX_SOURCE),buf.readEnum(RecipeDocument.Format.class)); }
    public void encode(FriendlyByteBuf buf) { buf.writeUUID(token); buf.writeLong(revision); buf.writeEnum(action); buf.writeUtf(id,256); buf.writeUtf(source,RecipeDocument.MAX_SOURCE); buf.writeEnum(format); }
    public static void handle(RecipeEditorActionPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get(); ctx.enqueueWork(()->RecipeEditorService.handle(ctx.getSender(),packet.token,packet.revision,packet.action,packet.id,packet.source,packet.format)); ctx.setPacketHandled(true);
    }
}
