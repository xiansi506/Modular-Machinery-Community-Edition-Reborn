package com.reborn.modularmachinery.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.Consumer;

/** Registry ID search remains available without JEI. Values are chosen explicitly, never auto-replaced. */
public final class RecipeResourcePicker extends Screen {
    private final Screen parent;
    private final List<String> values;
    private final Consumer<String> choose;
    private String query;
    private int page,left,panel,rows;
    private EditBox search;
    private List<String> matches=List.of();
    public RecipeResourcePicker(Screen parent,Collection<String> values,String query,Consumer<String> choose) {
        super(RecipeEditorScreen.tr("search"));this.parent=parent;this.values=values.stream().distinct().sorted().toList();this.query=query;this.choose=choose;
    }
    @Override protected void init(){
        panel=Math.min(600,width-24);left=(width-panel)/2;rows=Math.max(1,(height-105)/22);
        search=addRenderableWidget(new EditBox(font,left,30,panel,20,title));search.setMaxLength(256);search.setValue(query);search.setResponder(value->{query=value;page=0;refresh();});refresh();
        setInitialFocus(search);
    }
    private void refresh(){
        clearWidgets();addRenderableWidget(search);
        matches=values.stream().filter(value->value.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();page=Math.min(page,Math.max(0,(matches.size()-1)/rows));
        for(int row=0;row<rows&&page*rows+row<matches.size();row++){
            String value=matches.get(page*rows+row);addRenderableWidget(Button.builder(Component.literal(value),b->{choose.accept(value);minecraft.setScreen(parent);}).bounds(left,60+row*22,panel,20).build());
        }
        addRenderableWidget(Button.builder(RecipeEditorScreen.tr("previous"),b->{page=Math.max(0,page-1);refresh();}).bounds(left,height-28,panel/3-2,20).build());
        addRenderableWidget(Button.builder(RecipeEditorScreen.tr("next"),b->{if((page+1)*rows<matches.size())page++;refresh();}).bounds(left+panel/3,height-28,panel/3-2,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),b->onClose()).bounds(left+2*panel/3,height-28,panel-2*panel/3,20).build());
    }
    @Override public void tick(){search.tick();}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics graphics,int x,int y,float partial){renderBackground(graphics);graphics.drawCenteredString(font,title,width/2,10,0xFFFFFF);graphics.drawString(font,(page+1)+" / "+Math.max(1,(matches.size()+rows-1)/rows)+" ("+matches.size()+")",left,height-42,0xAAAAAA,false);super.render(graphics,x,y,partial);}
}
