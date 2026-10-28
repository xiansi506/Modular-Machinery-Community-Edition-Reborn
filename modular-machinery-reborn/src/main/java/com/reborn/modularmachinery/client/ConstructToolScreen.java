package com.reborn.modularmachinery.client;

import com.google.gson.*;
import com.reborn.modularmachinery.network.*;
import com.reborn.modularmachinery.selection.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;

/** Selection settings, export settings and paged read-only source preview. No file path comes from the client. */
public final class ConstructToolScreen extends Screen {
    private JsonObject state,options;
    private int tab,left,panel,row,scroll,waiting;
    private final Map<String,EditBox> fields=new LinkedHashMap<>();
    private String from="",to="";
    private List<FormattedCharSequence> lines=List.of();
    private static Component tr(String key,Object... args){return Component.translatable("tool.modular_machinery_reborn."+key,args);}
    public ConstructToolScreen(JsonObject state,int tab){super(tr("title"));this.state=state;this.options=state.getAsJsonObject("options").deepCopy();this.tab=tab;}
    @Override public void resize(net.minecraft.client.Minecraft mc,int width,int height){collect();super.resize(mc,width,height);}
    public void update(JsonObject next,int nextTab){
        collect();boolean error="error".equals(next.get("kind").getAsString());state=next;
        if(!error)options=next.getAsJsonObject("options").deepCopy();
        if(!error)tab=Math.max(0,Math.min(2,nextTab));waiting=0;scroll=0;clearWidgets();init();
    }
    private void collect(){
        fields.forEach((key,box)->{if(key.equals("from"))from=box.getValue();else if(key.equals("to"))to=box.getValue();else options.addProperty(key,box.getValue());});
    }
    public static int formTop(int height){return Math.min(72,Math.max(48,height-120));}
    public static int rowHeight(int height){return Math.max(20,Math.min(24,(height-formTop(height))/6));}
    @Override protected void init(){
        fields.clear();panel=Math.min(600,width-16);left=(width-panel)/2;row=rowHeight(height);
        button(0,24,panel/2-2,tr("selection"),()->page(0));button(panel/2+2,24,panel/2-2,tr("exportSettings"),()->page(1));
        if(tab==0){
            button(0,y(0),panel/2-2,tr("mode",tr("mode."+options.get("mode").getAsString())),()->cycle("mode",ConstructOptions.Mode.values()));
            button(panel/2+2,y(0),panel/2-2,tr("operation",tr("operation."+options.get("operation").getAsString())),()->cycle("operation",StructureSelection.Operation.values()));
            button(0,y(1),panel,tr("ignoreAir",tr(options.get("ignoreAir").getAsBoolean()?"on":"off")),()->{collect();options.addProperty("ignoreAir",!options.get("ignoreAir").getAsBoolean());send(ConstructToolService.Action.APPLY);});
            int third=panel/3;
            button(0,y(2),third-2,tr("undo"),()->send(ConstructToolService.Action.UNDO)).active=waiting==0&&state.get("undo").getAsBoolean();
            button(third,y(2),third-2,tr("redo"),()->send(ConstructToolService.Action.REDO)).active=waiting==0&&state.get("redo").getAsBoolean();
            button(third*2,y(2),panel-third*2,tr("clear"),()->send(ConstructToolService.Action.CLEAR));
            field("from",from,y(3),128);field("to",to,y(4),256);
            button(0,y(5),panel/2-2,tr("replace"),()->{collect();options.getAsJsonObject("replacements").addProperty(from.trim(),to.trim());send(ConstructToolService.Action.APPLY);});
            button(panel/2+2,y(5),panel/2-2,tr("resetReplacements",options.getAsJsonObject("replacements").size()),()->{collect();options.add("replacements",new JsonObject());send(ConstructToolService.Action.APPLY);});
        }else if(tab==1){
            field("machineId",options.get("machineId").getAsString(),y(0),128);field("displayName",options.get("displayName").getAsString(),y(1),128);
            field("states",options.get("states").getAsString(),y(2),512);field("matchingNbt",options.get("matchingNbt").getAsString(),y(3),1024);field("previewNbt",options.get("previewNbt").getAsString(),y(4),1024);
            button(0,y(5),panel/2-2,tr("format",tr("format."+options.get("format").getAsString())),()->{collect();var values=ConstructOptions.Format.values();int next=(ConstructOptions.Format.valueOf(options.get("format").getAsString()).ordinal()+1)%values.length;options.addProperty("format",values[next].name());clearWidgets();init();});
            button(panel/2+2,y(5),panel/2-2,tr("preview"),()->send(ConstructToolService.Action.PREVIEW));
        }else{
            lines=font.split(Component.literal(state.get("preview").getAsString()),panel-12);
            int quarter=panel/4;
            button(0,height-26,quarter-2,tr("previous"),()->send(ConstructToolService.Action.PREVIOUS_PAGE));
            button(quarter,height-26,quarter-2,tr("next"),()->send(ConstructToolService.Action.NEXT_PAGE));
            button(quarter*2,height-26,quarter-2,tr("copyPage"),()->minecraft.keyboardHandler.setClipboard(state.get("preview").getAsString()));
            button(quarter*3,height-26,panel-quarter*3,tr("save"),()->send(ConstructToolService.Action.EXPORT)).active=waiting==0&&state.get("pages").getAsInt()>0;
        }
    }
    private int y(int r){return formTop(height)+r*row;}
    private void page(int next){collect();tab=next;scroll=0;clearWidgets();init();}
    private <E extends Enum<E>> void cycle(String key,E[] values){collect();int index=0;for(int i=0;i<values.length;i++)if(values[i].name().equals(options.get(key).getAsString()))index=i;options.addProperty(key,values[(index+1)%values.length].name());send(ConstructToolService.Action.APPLY);}
    private Button button(int x,int y,int w,Component label,Runnable action){Button b=addRenderableWidget(Button.builder(label,ignored->action.run()).bounds(left+x,y,w,20).build());b.active=waiting==0;return b;}
    private void field(String key,String value,int y,int max){int label=Math.min(150,panel/3);EditBox box=addRenderableWidget(new EditBox(font,left+label,y+1,panel-label,18,tr(key)));box.setMaxLength(max);box.setValue(value);box.active=waiting==0;
        if(key.equals("states")||key.endsWith("Nbt"))box.setTooltip(Tooltip.create(tr("fieldsHelp")));fields.put(key,box);}
    private void send(ConstructToolService.Action action){
        if(waiting>0)return;collect();ModNetwork.CHANNEL.sendToServer(new ConstructToolActionPacket(new net.minecraft.resources.ResourceLocation(state.get("dimension").getAsString()),action,state.get("revision").getAsLong(),options.toString()));waiting=1;clearWidgets();init();
    }
    @Override public void tick(){
        fields.values().forEach(EditBox::tick);
        if(minecraft.player==null||minecraft.level==null||!minecraft.level.dimension().location().toString().equals(state.get("dimension").getAsString())){onClose();return;}
        if(waiting>0&&++waiting>100){waiting=0;state.addProperty("kind","error");state.addProperty("message","Request timed out; reopen the tool panel");clearWidgets();init();}
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partial){
        renderBackground(g);g.drawCenteredString(font,title,width/2,7,0xffffff);
        String notice=state.get("message").getAsString();
        Component header=notice.isBlank()?tr("summary",state.get("count").getAsInt(),state.get("bounds").getAsString()):tr(state.get("kind").getAsString(),notice);
        g.drawString(font,font.plainSubstrByWidth(header.getString(),panel),left,49,notice.isBlank()?0xffffff:0xffcc88);
        if(tab==2){
            g.drawString(font,font.plainSubstrByWidth(tr("target",state.get("filename").getAsString(),state.get("page").getAsInt()+1,state.get("pages").getAsInt()).getString(),panel),left,62,0xcccccc);
            int visible=Math.max(1,(height-110)/10);scroll=Math.max(0,Math.min(scroll,Math.max(0,lines.size()-visible)));
            g.fill(left,77,left+panel,height-32,0xc0000000);
            for(int i=0;i<visible&&i+scroll<lines.size();i++)g.drawString(font,lines.get(i+scroll),left+4,80+i*10,0xe0e0e0);
        }else{
            if(height>=200)g.drawString(font,font.plainSubstrByWidth(tr("origin",state.get("controller").getAsString()).getString(),panel),left,61,0xaaaaaa);
            for(var e:fields.entrySet())g.drawString(font,font.plainSubstrByWidth(tr(e.getKey()).getString(),Math.min(145,panel/3)-4),left,e.getValue().getY()+5,0xdddddd);
            if(height>=230)g.drawString(font,font.plainSubstrByWidth(tr(tab==0?"selectionHelp":"fieldsHelp").getString(),panel),left,height-12,0x999999);
        }
        super.render(g,mouseX,mouseY,partial);
        if(!notice.isBlank()&&mouseX>=left&&mouseX<left+panel&&mouseY>=48&&mouseY<60)g.renderTooltip(font,header,mouseX,mouseY);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta){if(tab==2){scroll-=delta*3;return true;}return super.mouseScrolled(x,y,delta);}
}
