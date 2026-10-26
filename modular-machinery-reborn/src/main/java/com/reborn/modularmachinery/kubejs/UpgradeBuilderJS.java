package com.reborn.modularmachinery.kubejs;

import com.google.gson.JsonObject;
import com.reborn.modularmachinery.script.*;
import dev.latvian.mods.kubejs.util.JsonIO;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** One script-defined upgrade, with the same validation as a datapack declaration. */
public final class UpgradeBuilderJS {
    @FunctionalInterface public interface Handler {
        void accept(LifecycleEventsJS.ControllerEventJS event, MachineControllerAccess.UpgradeAccess upgrade);
    }
    private final ResourceLocation id;
    private final JsonObject json = new JsonObject();
    private final Map<String, List<BiConsumer<com.reborn.modularmachinery.api.event.MachineEvent, MachineControllerAccess.UpgradeAccess>>> callbacks = new LinkedHashMap<>();
    private final List<String> items = new ArrayList<>();
    UpgradeBuilderJS(String name) { id = ScriptUpgradeDefinitions.id(name); json.addProperty("name", id.toString()); }
    public UpgradeBuilderJS field(String key, Object value) { json.add(key, JsonIO.of(value)); return this; }
    public UpgradeBuilderJS localizedName(String name) { json.addProperty("localizedname", name); return this; }
    public UpgradeBuilderJS item(String item) { items.add(item); return this; }
    public UpgradeBuilderJS on(String event, Handler callback) {
        ScriptHooks.validateEvent(event);
        callbacks.computeIfAbsent(event, ignored -> new ArrayList<>()).add((backing, upgrade) ->
                ScriptCallbacksJS.invoke(() -> callback.accept(ScriptCallbacksJS.wrap(backing), upgrade)));
        return this;
    }
    public void register() {
        com.reborn.modularmachinery.upgrade.UpgradeLoader.readType(id, json);
        ScriptUpgradeDefinitions.stage(id, json);
        for (int n = 0; n < items.size(); n++) {
            JsonObject mapping = new JsonObject(); mapping.addProperty("upgrade", id.toString()); mapping.addProperty("item", items.get(n));
            ScriptUpgradeDefinitions.stage(new ResourceLocation(id.getNamespace(), id.getPath() + "/" + n + ".item"), mapping);
        }
        ScriptHooks.replaceUpgrade(id);
        callbacks.forEach((event, handlers) -> ScriptHooks.upgradeEvents(id, event, handlers));
    }
}
