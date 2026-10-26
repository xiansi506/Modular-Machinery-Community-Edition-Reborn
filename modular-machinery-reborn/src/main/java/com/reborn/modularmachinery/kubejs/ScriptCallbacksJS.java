package com.reborn.modularmachinery.kubejs;

import com.reborn.modularmachinery.api.event.*;
import com.reborn.modularmachinery.script.ScriptHooks;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Functions stay on the server; stable definition ids select handlers after a reload. */
final class ScriptCallbacksJS {
    private final Map<String, List<Consumer<MachineEvent>>> handlers = new LinkedHashMap<>();
    boolean isEmpty() { return handlers.isEmpty(); }
    void add(String event, Consumer<LifecycleEventsJS.ControllerEventJS> callback) {
        ScriptHooks.validateEvent(event);
        handlers.computeIfAbsent(event, ignored -> new ArrayList<>()).add(backing -> invoke(() -> callback.accept(wrap(backing))));
    }
    static void invoke(Runnable callback) {
        try { callback.run(); }
        catch (Exception error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause())
                if (cause instanceof dev.latvian.mods.kubejs.event.EventExit) return;
            throw error;
        }
    }
    void bind(boolean recipe, ResourceLocation id) {
        ScriptHooks.replaceScope(recipe, id);
        handlers.forEach((event, callbacks) -> ScriptHooks.events(recipe, id, event, callbacks));
    }
    static LifecycleEventsJS.ControllerEventJS wrap(MachineEvent event) {
        if (event instanceof RecipeCheckEvent check) return new LifecycleEventsJS.RecipeCheckEventJS(check);
        if (event instanceof FactoryRecipeTickEvent tick) return new LifecycleEventsJS.FactoryRecipeTickEventJS(tick);
        if (event instanceof FactoryRecipeFailureEvent failure) return new LifecycleEventsJS.FactoryFailureEventJS(failure);
        if (event instanceof FactoryRecipeEvent recipe) return new LifecycleEventsJS.FactoryRecipeEventJS(recipe);
        if (event instanceof RecipeTickEvent tick) return new LifecycleEventsJS.RecipeTickEventJS(tick);
        if (event instanceof RecipeFailureEvent failure) return new LifecycleEventsJS.RecipeFailureEventJS(failure);
        if (event instanceof ResultChanceCreateEvent chance) return new LifecycleEventsJS.ResultChanceEventJS(chance);
        if (event instanceof RecipeEvent recipe) return new LifecycleEventsJS.RecipeEventJS(recipe);
        if (event instanceof MachineTickEvent tick) return new LifecycleEventsJS.MachineTickEventJS(tick);
        if (event instanceof SmartInterfaceUpdateEvent update) return new LifecycleEventsJS.SmartInterfaceEventJS(update);
        return new LifecycleEventsJS.ControllerEventJS(event);
    }
}
