package com.reborn.modularmachinery.client;

import com.google.gson.*;
import com.reborn.modularmachinery.item.ModItems;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.RenderGuiEvent;

/** Cached authoring summary. Rendering never traverses the entire selection to calculate its bounds. */
public final class ConstructToolClient {
    private static JsonObject state;
    private ConstructToolClient(){}
    public static void accept(JsonObject snapshot,boolean open,int tab){
        Minecraft mc=Minecraft.getInstance();
        if(mc.level==null||!mc.level.dimension().location().toString().equals(snapshot.get("dimension").getAsString()))return;
        state=snapshot;
        if(mc.screen instanceof ConstructToolScreen screen)screen.update(snapshot,open?tab:0);
        else if(open)mc.setScreen(new ConstructToolScreen(snapshot,tab));
    }
    public static void clear(){state=null;}
    public static BlockPos corner(){
        Minecraft mc=Minecraft.getInstance();
        if(state==null||mc.level==null||!mc.level.dimension().location().toString().equals(state.get("dimension").getAsString())||!state.has("corner"))return null;
        JsonArray p=state.getAsJsonArray("corner");return new BlockPos(p.get(0).getAsInt(),p.get(1).getAsInt(),p.get(2).getAsInt());
    }
    public static void onGui(RenderGuiEvent.Post event){
        Minecraft mc=Minecraft.getInstance();if(state==null||mc.player==null||mc.level==null||mc.screen!=null)return;
        if(!mc.player.getMainHandItem().is(ModItems.CONSTRUCT_TOOL.get())&&!mc.player.getOffhandItem().is(ModItems.CONSTRUCT_TOOL.get()))return;
        if(!mc.level.dimension().location().toString().equals(state.get("dimension").getAsString()))return;
        var g=event.getGuiGraphics();g.drawString(mc.font,Component.translatable("tool.modular_machinery_reborn.summary",state.get("count").getAsInt(),state.get("bounds").getAsString()),8,8,0xffffff);
        JsonObject options=state.getAsJsonObject("options");g.drawString(mc.font,Component.translatable("tool.modular_machinery_reborn.hud",
            Component.translatable("tool.modular_machinery_reborn.mode."+options.get("mode").getAsString()),
            Component.translatable("tool.modular_machinery_reborn.operation."+options.get("operation").getAsString())),8,20,0xdadada);
        BlockPos corner=corner();if(corner!=null)g.drawString(mc.font,Component.translatable("tool.modular_machinery_reborn.corner",corner.toShortString()),8,32,0x80ff80);
    }
}
