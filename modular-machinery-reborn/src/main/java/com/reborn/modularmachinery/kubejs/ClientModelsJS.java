package com.reborn.modularmachinery.kubejs;

import com.google.gson.JsonObject;
import com.reborn.modularmachinery.machine.GeoModelSpec;
import com.reborn.modularmachinery.script.ClientModelRegistry;
import dev.latvian.mods.kubejs.util.JsonIO;
import java.util.List;

/** Bound only while evaluating client_scripts. */
public final class ClientModelsJS {
    public void register(String name, Object spec) { ClientModelRegistry.register(name, parse(spec)); }
    public void remove(String name) { ClientModelRegistry.remove(name); }
    public boolean contains(String name) { return ClientModelRegistry.contains(name); }
    public List<String> getNames() { return ClientModelRegistry.names(); }
    static GeoModelSpec parse(Object spec) {
        JsonObject root = new JsonObject(); root.add("geo-model", JsonIO.of(spec));
        GeoModelSpec result = GeoModelSpec.parse(root, "client script model");
        if (result == null) throw new IllegalArgumentException("Client model requires model and texture resources");
        return result;
    }
}
