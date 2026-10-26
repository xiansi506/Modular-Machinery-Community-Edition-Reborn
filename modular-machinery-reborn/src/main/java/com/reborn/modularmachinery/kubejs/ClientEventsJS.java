package com.reborn.modularmachinery.kubejs;

import com.reborn.modularmachinery.api.ControllerGuiInfoEvent;
import com.reborn.modularmachinery.api.event.ControllerModelGetEvent;
import com.reborn.modularmachinery.api.event.ControllerModelAnimationEvent;
import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.machine.GeoModelSpec;
import com.reborn.modularmachinery.script.ClientControllerView;
import dev.latvian.mods.kubejs.event.*;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.script.ScriptTypeHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import java.util.ArrayList;
import java.util.List;

/** Client-only script handlers; public payloads contain neither renderer nor GeckoLib classes. */
public final class ClientEventsJS {
    public static final String GROUP_NAME = "MachineClientEvents";
    private static final EventGroup GROUP = EventGroup.of(GROUP_NAME);
    private static final EventHandler GUI = GROUP.client("guiRender", () -> GuiEventJS.class).extra(Extra.ID);
    private static final EventHandler MODEL = GROUP.client("modelGet", () -> ModelEventJS.class).extra(Extra.ID);
    private static final EventHandler ANIMATION = GROUP.client("modelAnimation", () -> AnimationEventJS.class).extra(Extra.ID);
    private static boolean bridgeRegistered;
    private ClientEventsJS() {}
    public static EventGroup group() { return GROUP; }
    public static void register() { GROUP.register(); }
    public static void registerBridge() {
        if (bridgeRegistered) return;
        bridgeRegistered = true;
        MinecraftForge.EVENT_BUS.addListener((ControllerGuiInfoEvent event) -> {
            ResourceLocation id = event.controller().machineId();
            if (id == null || !GUI.hasListeners(id)) return;
            GuiEventJS js = new GuiEventJS(event);
            if (post(GUI, id, js)) js.commit();
        });
        MinecraftForge.EVENT_BUS.addListener((ControllerModelGetEvent event) -> {
            if (event.machineId() == null || !MODEL.hasListeners(event.machineId())) return;
            if (!post(MODEL, event.machineId(), new ModelEventJS(event))) event.reset();
        });
        MinecraftForge.EVENT_BUS.addListener((ControllerModelAnimationEvent event) -> {
            if (event.machineId() == null || !ANIMATION.hasListeners(event.machineId())) return;
            if (!post(ANIMATION, event.machineId(), new AnimationEventJS(event))) event.reset();
        });
    }

    private static boolean post(EventHandler handler, ResourceLocation machine, EventJS event) {
        boolean[] failed = {false};
        // KubeJS 6 logs an ERROR exit but can return PASS. Track callback exceptions explicitly so a
        // partially written model/animation or staged GUI batch is never accepted after an error.
        EventResult result = handler.post((ScriptTypeHolder) ScriptType.CLIENT, machine, event,
                (js, container, error) -> { failed[0] = true; return error; });
        return !failed[0] && !result.error();
    }

    public static class ControllerEventJS extends EventJS {
        private final MachineControllerBlockEntity controller;
        protected ControllerEventJS(MachineControllerBlockEntity controller) { this.controller = controller; }
        public ClientControllerView getController() { return new ClientControllerView(controller); }
        public ResourceLocation getMachine() { return controller.machineId(); }
        public BlockPos getPos() { return controller.getBlockPos(); }
        public net.minecraft.world.level.Level getWorld() { return controller.getLevel(); }
    }
    public static final class GuiEventJS extends ControllerEventJS {
        private final ControllerGuiInfoEvent event;
        private final List<Component> staged = new ArrayList<>();
        public GuiEventJS(ControllerGuiInfoEvent event) { super(event.controller()); this.event = event; }
        public void addInfo(String... lines) { for (String line : lines) staged.add(Component.literal(line)); }
        public void addTranslatedInfo(String key, String... args) { staged.add(Component.translatable(key, (Object[]) args)); }
        /** The original setter appended rather than replacing previously supplied lines. */
        public void setExtraInfo(String... lines) { addInfo(lines); }
        public String[] getExtraInfo() {
            List<String> lines = new ArrayList<>(); event.extraInfo().forEach(line -> lines.add(line.getString()));
            staged.forEach(line -> lines.add(line.getString())); return lines.toArray(String[]::new);
        }
        void commit() { staged.forEach(event::addInfo); }
    }
    public static final class ModelEventJS extends ControllerEventJS {
        private final ControllerModelGetEvent event;
        public ModelEventJS(ControllerModelGetEvent event) { super(event.controller()); this.event = event; }
        public String getModelName() { return event.getModelName(); }
        public void setModelName(String name) { event.setModelName(name); }
        public GeoModelSpec getDefaultModel() { return event.getDefaultModel(); }
        public void setModel(Object spec) { event.setModel(ClientModelsJS.parse(spec)); }
    }
    public static final class AnimationEventJS extends ControllerEventJS {
        private final ControllerModelAnimationEvent event;
        public AnimationEventJS(ControllerModelAnimationEvent event) { super(event.controller()); this.event = event; }
        public void addAnimation(String name) { event.addAnimation(name); }
        public void addAnimation(String name, boolean loop) { event.addAnimation(name, loop); }
        public void setAnimation(String name) { event.setAnimation(name); }
        public void setAnimation(String name, boolean loop) { event.setAnimation(name, loop); }
        public void clearAnimations() { event.clearAnimations(); }
        public List<ControllerModelAnimationEvent.AnimationStep> getAnimations() { return event.getAnimations(); }
        public String getCurrentModelName() { return event.getCurrentModelName(); }
        public String getCurrentAnimationName() { return event.getCurrentAnimationName(); }
        public double getAnimationTick() { return event.getAnimationTick(); }
        public int getAnimationState() { return event.getAnimationState(); }
        public float getLimbSwing() { return event.getLimbSwing(); }
        public float getLimbSwingAmount() { return event.getLimbSwingAmount(); }
        public float getPartialTick() { return event.getPartialTick(); }
        public boolean isMoving() { return event.isMoving(); }
        public int getPlayState() { return event.getPlayState(); }
        public void setPlayState(int state) { event.setPlayState(state); }
        public double getTransitionLengthTicks() { return event.getTransitionLengthTicks(); }
        public void setTransitionLengthTicks(double ticks) { event.setTransitionLengthTicks(ticks); }
        public double getAnimationSpeed() { return event.getAnimationSpeed(); }
        public void setAnimationSpeed(double speed) { event.setAnimationSpeed(speed); }
    }
}
