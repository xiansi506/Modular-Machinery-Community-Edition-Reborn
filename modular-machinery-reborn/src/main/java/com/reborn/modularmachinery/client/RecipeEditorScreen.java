package com.reborn.modularmachinery.client;

import com.google.gson.*;
import com.reborn.modularmachinery.authoring.*;
import com.reborn.modularmachinery.network.*;
import com.reborn.modularmachinery.recipe.LiteralNbt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.*;

/** Local draft forms; a server validation ticket is needed before any file can be written. */
public final class RecipeEditorScreen extends Screen {
    private JsonObject root, state;
    private final UUID token;
    private final String dimension;
    private final List<String> machines = new ArrayList<>();
    private final Set<String> absent = new HashSet<>();
    private final Map<String,EditBox> fields = new LinkedHashMap<>();
    private final Map<String,MultiLineEditBox> multiline = new LinkedHashMap<>();
    private String recipeId="custom:recipe", message="", checkedSource="", checkedId="";
    private RecipeDocument.Format format=RecipeDocument.Format.JSON, checkedFormat;
    private int tab, part, selected, left, panel, label, waiting, scroll;
    private boolean previewSource;
    private List<FormattedCharSequence> lines=List.of();
    private Button exportButton;
    private final List<ItemStack> previewItems=new ArrayList<>();
    public static Component tr(String key,Object... args) { return Component.translatable("editor.modular_machinery_reborn."+key,args); }
    public RecipeEditorScreen(JsonObject state) {
        super(tr("title")); this.state=state; token=UUID.fromString(state.get("token").getAsString()); dimension=state.get("dimension").getAsString();
        root=RecipeDocument.read(state.get("source").getAsString());
        state.getAsJsonArray("machines").forEach(v->machines.add(v.getAsString())); state.getAsJsonArray("absent").forEach(v->absent.add(v.getAsString()));
    }
    public static void accept(JsonObject state, boolean open) {
        Minecraft mc=Minecraft.getInstance();
        if (mc.player==null || mc.level==null) return;
        if (open) { if (mc.level.dimension().location().toString().equals(state.get("dimension").getAsString())) mc.setScreen(new RecipeEditorScreen(state)); }
        else if (mc.screen instanceof RecipeEditorScreen screen && screen.token.toString().equals(state.get("token").getAsString())) screen.update(state);
    }
    private void update(JsonObject next) {
        state=next; waiting=0;
        String kind=state.get("kind").getAsString();
        if (kind.equals("valid") && reviewMatches(state,recipeId,root.toString(),format)) {
            checkedSource=state.get("reviewedSource").getAsString(); checkedId=recipeId; checkedFormat=format; tab=3; scroll=0; message=tr("valid").getString();previewItems.clear();
            if(root.has("requirements"))for(JsonElement element:root.getAsJsonArray("requirements"))try{
                JsonObject req=element.getAsJsonObject();if(RecipeDocument.kind(req).equals("item")){
                    var parsed=(com.reborn.modularmachinery.recipe.ItemRequirement)com.reborn.modularmachinery.recipe.MachineRequirementParser.parse(req,RecipeDocument.id(recipeId));
                    ItemStack stack=parsed.ioType()==com.reborn.modularmachinery.recipe.IOType.OUTPUT?parsed.outputStack().copy():com.reborn.modularmachinery.recipe.LegacyIngredients.firstStack(parsed.ingredient()).copy();
                    if(parsed.displayTag()!=null)stack.setTag(parsed.displayTag());else if(parsed.nbt()!=null)stack.setTag(parsed.nbt().toTag());stack.setCount(parsed.minAmount());previewItems.add(stack);
                }
            }catch(RuntimeException ignored){/* Server source/summary remains available if local tags lag behind a reload. */}
        }
        else if (kind.equals("saved")) { checkedSource=""; message=tr("saved",state.get("message").getAsString()).getString(); }
        else if(kind.equals("valid")){dirty();message=tr("needsValidation").getString();}
        else message=state.get("message").getAsString();
        rebuild();
    }
    public static boolean reviewMatches(JsonObject state,String id,String source,RecipeDocument.Format format){return state.has("reviewedId")&&id.equals(state.get("reviewedId").getAsString())&&source.equals(state.get("reviewedSource").getAsString())&&format.name().equals(state.get("reviewedFormat").getAsString());}
    private JsonArray requirements() {
        if (!root.has("requirements")) root.add("requirements",new JsonArray());
        return root.getAsJsonArray("requirements");
    }
    private JsonObject current() { JsonArray array=requirements(); selected=Math.max(0,Math.min(selected,array.size()-1)); return array.isEmpty()?null:array.get(selected).getAsJsonObject(); }
    private static String text(JsonObject json,String key) { if (!json.has(key)) return ""; JsonElement v=json.get(key); return v.isJsonPrimitive()?v.getAsString():v.toString(); }
    private static String editable(JsonElement value){String pretty=RecipeDocument.JSON.toJson(value);return pretty.length()<=RecipeDocument.MAX_SOURCE?pretty:value.toString();}
    private Map<String,String> values() { Map<String,String> values=new LinkedHashMap<>(); fields.forEach((key,box)->values.put(key,box.getValue())); multiline.forEach((key,box)->values.put(key,box.getValue())); return values; }
    private boolean collect() {
        try {
            Map<String,String> values=values();
            if (tab==0 && fields.containsKey("id")) {
                String id=values.remove("id").trim(); RecipeDocument.id(id);
                JsonObject patched=RecipeForm.root(root,values); root=patched; recipeId=id;
            } else if (tab==1 && current()!=null && RecipeDocument.TYPES.contains(RecipeDocument.kind(current()))) {
                JsonObject patched=RecipeForm.requirement(current(),values); requirements().set(selected,patched);
            } else if (tab==2 && multiline.containsKey("source")) root=RecipeDocument.read(multiline.get("source").getValue());
            return true;
        } catch (RuntimeException error) { message=String.valueOf(error.getMessage()); return false; }
    }
    private void change(Runnable action) { if (waiting==0 && collect()) { action.run(); scroll=0; rebuild(); } }
    private void rebuild() { clearWidgets(); init(); }
    @Override public void resize(Minecraft mc,int width,int height) { if (!collect()) { /* Preserve malformed source while resizing. */ savedValues=values(); } super.resize(mc,width,height); }
    private Map<String,String> savedValues;
    public static int top(int height) { return Math.min(58,Math.max(44,height-142)); }
    public static int rowHeight(int height) { return Math.max(20,Math.min(24,(height-top(height)-68)/6)); }
    private int y(int row) { return top(height)+row*rowHeight(height); }
    @Override protected void init() {
        fields.clear(); multiline.clear(); panel=Math.max(200,Math.min(620,width-24)); left=(width-panel)/2; label=Math.min(140,panel/3);
        int quarter=panel/4;
        for (int i=0;i<4;i++) { int page=i; button(i*quarter,25,quarter-2,tr("tab."+i),()->change(()->tab=page)); }
        if (tab==0) {
            field("id",recipeId,0); field("machine",text(root,"machine"),1);
            button(0,y(2),panel,tr("machineNext",machines.size()),()->pick(machines,text(root,"machine"),value->root.addProperty("machine",value)));
            field("recipeTime",root.has("recipeTime")?text(root,"recipeTime"):"100",3); field("max-parallelism",text(root,"max-parallelism"),4);
            button(0,y(5),panel,tr("format",format.name()),()->change(()->format=format==RecipeDocument.Format.JSON?RecipeDocument.Format.KUBEJS:RecipeDocument.Format.JSON));
        } else if (tab==1) {
            int fifth=panel/5;
            button(0,y(0),fifth-2,tr("previous"),()->change(()->selected=Math.max(0,selected-1)));
            button(fifth,y(0),fifth-2,tr("next"),()->change(()->selected=Math.min(requirements().size()-1,selected+1)));
            button(2*fifth,y(0),fifth-2,tr("add"),()->change(()-> { if(requirements().size()<RecipeDocument.MAX_REQUIREMENTS) {requirements().add(RecipeDocument.requirement("item")); selected=requirements().size()-1;} }));
            button(3*fifth,y(0),fifth-2,tr("remove"),()->change(()-> { if(current()!=null)requirements().remove(selected); }));
            button(4*fifth,y(0),panel-4*fifth,tr("part",part+1),()->change(()->part=(part+1)%3));
            JsonObject req=current();
            if (req!=null && RecipeDocument.TYPES.contains(RecipeDocument.kind(req))) {
                String kind=RecipeDocument.kind(req);
                if (part==0) {
                    button(0,y(1),panel/2-2,tr("kind",kind,absent.contains(kind)?"*":""),()->change(()-> {
                        minecraft.setScreen(new RecipeResourcePicker(this,RecipeDocument.TYPES,"",value->{JsonObject next=RecipeDocument.requirement(value);next.add("io-type",current().get("io-type").deepCopy());requirements().set(selected,next);}));
                    })).setTooltip(Tooltip.create(tr("kindHelp")));
                    button(panel/2+2,y(1),panel/2-2,tr("io",text(req,"io-type")),()->change(()->current().addProperty("io-type",text(current(),"io-type").equals("input")?"output":"input")));
                    String key=RecipeDocument.resourceKey(kind);
                    if(!key.isEmpty())field(key,text(req,key),2); else if(kind.equals("compressed_air"))field("pressure",text(req,"pressure"),2);
                    String amountKey=RecipeDocument.amountKey(kind), value=text(req,amountKey);
                    if(value.isEmpty()) value=kind.equals("ember")?text(req,"ember"):kind.equals("compressed_air")?text(req,"amount"):"1";
                    field(amountKey,value,3);
                    button(0,y(4),panel/2-2,tr("perTick",RecipeDocument.perTick(req)?"✓":"–"),()->change(()->RecipeDocument.perTick(current(),!RecipeDocument.perTick(current())))).active=waiting==0&&!List.of("energy","item").contains(kind);
                    button(panel/2+2,y(4),panel/2-2,tr("search"),()->search(kind));
                    button(0,y(5),panel,tr("hand"),()->change(()-> {if(minecraft.player!=null)dropItem(minecraft.player.getMainHandItem());}));
                } else if(part==1) {
                    if(List.of("item","fluid","gas").contains(kind))field("chance",text(req,"chance"),1);
                    field("selector-tag",text(req,"selector-tag"),2);
                    if(kind.equals("item") || List.of("aura","will","mana","lifeessence","ember").contains(kind)) {
                        String min=kind.equals("item")?"minAmount":kind.equals("aura")?"min-aura":"min";
                        String max=kind.equals("item")?"maxAmount":kind.equals("aura")?"max-aura":"max";
                        field(min,text(req,min),3); field(max,text(req,max),4);
                    }
                    button(0,y(5),panel,tr("fixed",req.has("parallelize-unaffected")&&req.get("parallelize-unaffected").getAsBoolean()?"✓":"–"),()->change(()->current().addProperty("parallelize-unaffected",!(current().has("parallelize-unaffected")&&current().get("parallelize-unaffected").getAsBoolean()))));
                } else if(kind.equals("item")) {
                    multi("nbt",req.has("nbt")?editable(req.get("nbt")):"",y(1)+12,Math.max(24,rowHeight(height)*2-14));
                    multi("nbt-display",req.has("nbt-display")?editable(req.get("nbt-display")):"",y(3)+12,Math.max(24,rowHeight(height)*2-14));
                }
            } else if(req!=null) button(0,y(2),panel,tr("rawHelp"),()->change(()->tab=2));
        } else if(tab==2) {
            multi("source",editable(root),top(height)+12,Math.max(35,height-top(height)-64));
            button(0,height-49,panel/2-2,tr("paste"),()->{try{String source=minecraft.keyboardHandler.getClipboard();RecipeDocument.read(source);multiline.get("source").setValue(source);}catch(RuntimeException error){message=String.valueOf(error.getMessage());}});
            button(panel/2+2,height-49,panel/2-2,tr("copy"),()->minecraft.keyboardHandler.setClipboard(multiline.get("source").getValue()));
        } else {
            String body=previewSource?state.get("preview").getAsString():tr("baseHelp").getString()+"\n";
            if(!previewSource)for(JsonElement line:state.getAsJsonArray("summary"))body+=line.getAsString()+"\n";
            lines=font.split(Component.literal(body),panel-12);
            button(0,height-49,panel/2-2,tr(previewSource?"summary":"source"),()->{previewSource=!previewSource;scroll=0;rebuild();});
            button(panel/2+2,height-49,panel/2-2,tr("copy"),()->minecraft.keyboardHandler.setClipboard(state.get("preview").getAsString()));
        }
        button(0,height-25,panel/2-2,tr("validate"),()->send(RecipeEditorService.Action.VALIDATE));
        exportButton=button(panel/2+2,height-25,panel/2-2,tr("export"),()->send(RecipeEditorService.Action.EXPORT));exportButton.active=waiting==0&&state.get("ready").getAsBoolean()&&checkedId.equals(recipeId)&&checkedSource.equals(root.toString())&&checkedFormat==format;
        if(savedValues!=null) { savedValues.forEach((key,value)-> {if(fields.containsKey(key))fields.get(key).setValue(value); if(multiline.containsKey(key))multiline.get(key).setValue(value);}); savedValues=null; }
    }
    private Button button(int x,int y,int w,Component name,Runnable action) { Button b=addRenderableWidget(Button.builder(name,ignored->action.run()).bounds(left+x,y,w,20).build()); b.active=waiting==0; return b; }
    private void dirty(){checkedSource="";if(exportButton!=null)exportButton.active=false;}
    private void field(String key,String value,int row) { EditBox box=addRenderableWidget(new EditBox(font,left+label,y(row)+1,panel-label,18,tr(key)));box.setMaxLength(key.equals("id")?256:1024);box.setValue(value);box.setResponder(v->dirty());box.active=waiting==0;fields.put(key,box);box.setTooltip(Tooltip.create(tr(key+"Help"))); }
    private void multi(String key,String value,int y,int height) { MultiLineEditBox box=addRenderableWidget(new MultiLineEditBox(font,left,y,panel,height,Component.empty(),tr(key)));box.setCharacterLimit(RecipeDocument.MAX_SOURCE);box.setValue(value);box.setValueListener(v->dirty());box.active=waiting==0;multiline.put(key,box); }
    private void pick(Collection<String> choices,String query,java.util.function.Consumer<String> choose){if(waiting==0&&collect())minecraft.setScreen(new RecipeResourcePicker(this,choices,query,value->{choose.accept(value);dirty();}));}
    private void search(String kind) {
        String key=RecipeDocument.resourceKey(kind); EditBox field=fields.get(key); if(field==null)return;
        String query=field.getValue(); List<String> ids=new ArrayList<>();
        if(kind.equals("item")){ForgeRegistries.ITEMS.getKeys().forEach(id->ids.add(id.toString()));net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagNames().forEach(tag->ids.add("#"+tag.location()));}
        else if(kind.equals("fluid"))ForgeRegistries.FLUIDS.getKeys().forEach(id->ids.add(id.toString()));
        else if(kind.equals("aura"))ids.addAll(List.of("naturesaura:overworld","naturesaura:nether","naturesaura:end"));
        else if(kind.equals("will"))ids.addAll(List.of("default","corrosive","destructive","vengeful","steadfast"));
        if(ids.isEmpty()){message=tr("searchHelp").getString();return;}
        pick(ids,query,value->current().addProperty(key,value));
    }
    public Rect2i dropArea() { EditBox box=fields.get(current()==null?"":RecipeDocument.resourceKey(RecipeDocument.kind(current()))); return tab==1&&part==0&&waiting==0&&box!=null?new Rect2i(box.getX(),box.getY(),box.getWidth(),box.getHeight()):new Rect2i(0,0,0,0); }
    public boolean accepts(String kind) { return tab==1&&part==0&&waiting==0&&current()!=null&&RecipeDocument.kind(current()).equals(kind); }
    public Rect2i editorArea(){return new Rect2i(left,0,panel,height);}
    public void dropItem(ItemStack stack) {
        if(!accepts("item")||stack.isEmpty()||!collect())return;
        JsonObject req=current(); req.addProperty("item",ForgeRegistries.ITEMS.getKey(stack.getItem()).toString());
        req.remove("nbt");req.remove("nbt-display"); if(stack.hasTag())req.add("nbt",LiteralNbt.encode(stack.getTag()));
        if(!fields.isEmpty()) { EditBox item=fields.get("item"); if(item!=null)item.setValue(text(req,"item")); }
        rebuild();
    }
    public void dropFluid(FluidStack stack) { if(accepts("fluid")&&!stack.isEmpty()) {fields.get("fluid").setValue(ForgeRegistries.FLUIDS.getKey(stack.getFluid()).toString());} }
    public void dropGas(String id) { if(accepts("gas"))fields.get("gas").setValue(id); }
    private void send(RecipeEditorService.Action action) {
        if(waiting>0 || !collect())return;
        if(action==RecipeEditorService.Action.EXPORT&&(!checkedId.equals(recipeId)||!checkedSource.equals(root.toString())||checkedFormat!=format)){message=tr("needsValidation").getString();dirty();return;}
        try {
            RecipeDocument.read(root.toString()); RecipeDocument.id(recipeId);
            ModNetwork.CHANNEL.sendToServer(new RecipeEditorActionPacket(token,state.get("revision").getAsLong(),action,recipeId,root.toString(),format));waiting=1;message=tr("waiting").getString();rebuild();
        } catch(RuntimeException error) {message=String.valueOf(error.getMessage());}
    }
    @Override public void tick() {fields.values().forEach(EditBox::tick);multiline.values().forEach(MultiLineEditBox::tick);if(minecraft.player==null||minecraft.level==null||!dimension.equals(minecraft.level.dimension().location().toString()))onClose();if(waiting>0&&++waiting>100){waiting=0;message=tr("timeout").getString();rebuild();} }
    @Override public void onClose() {if(minecraft.getConnection()!=null)ModNetwork.CHANNEL.sendToServer(new RecipeEditorActionPacket(token,0,RecipeEditorService.Action.CLOSE,"","",format));super.onClose();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public boolean mouseScrolled(double x,double y,double delta){if(tab==3){scroll=Math.max(0,Math.min(Math.max(0,lines.size()-(height-top(height)-64)/10),scroll-(int)(delta*3)));return true;}return super.mouseScrolled(x,y,delta);}
    @Override public void render(GuiGraphics graphics,int mx,int my,float partial) {
        renderBackground(graphics);graphics.drawCenteredString(font,title,width/2,5,0xFFFFFF);
        graphics.drawString(font,font.plainSubstrByWidth(message,panel),left,height-62,0xFFCC77,false);
        if(tab==0) {for(var e:fields.entrySet())graphics.drawString(font,tr(e.getKey()),left,e.getValue().getY()+4,0xEEEEEE,false);}
        if(tab==1) {
            for(var e:fields.entrySet())graphics.drawString(font,tr(e.getKey()),left,e.getValue().getY()+4,0xEEEEEE,false);
            graphics.drawString(font,tr("position",requirements().isEmpty()?0:selected+1,requirements().size()),left,top(height)-11,0xAAAAAA,false);
            if(part==2)for(var e:multiline.entrySet())graphics.drawString(font,tr(e.getKey()),left,e.getValue().getY()-11,0xDDDDDD,false);
        }
        if(tab==2)graphics.drawString(font,font.plainSubstrByWidth(tr("rawHelp").getString(),panel),left,top(height),0xBBBBBB,false);
        if(tab==3) {
            int offset=!previewSource&&!previewItems.isEmpty()?24:0;
            if(offset>0)for(int i=0;i<Math.min(previewItems.size(),panel/22);i++) {int x=left+i*22;graphics.renderItem(previewItems.get(i),x,top(height));graphics.renderItemDecorations(font,previewItems.get(i),x,top(height));}
            graphics.enableScissor(left,top(height)+offset,left+panel,height-64);try{int visible=Math.max(0,(height-top(height)-offset-64)/10);for(int i=0;i<visible&&scroll+i<lines.size();i++)graphics.drawString(font,lines.get(scroll+i),left+4,top(height)+offset+i*10,0xDDDDDD,false);}finally{graphics.disableScissor();}
            if(offset>0&&my>=top(height)&&my<top(height)+18){int index=(mx-left)/22;if(mx>=left&&index<Math.min(previewItems.size(),panel/22))graphics.renderTooltip(font,previewItems.get(index),mx,my);}
        }
        super.render(graphics,mx,my,partial);
    }
}
