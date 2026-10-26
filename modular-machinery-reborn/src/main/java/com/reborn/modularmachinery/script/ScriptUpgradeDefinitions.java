package com.reborn.modularmachinery.script;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Definitions are staged in the machine registry event, consumed by the normal upgrade loader. */
public final class ScriptUpgradeDefinitions {
    private static final Map<ResourceLocation, JsonElement> FILES = new LinkedHashMap<>();
    private static Set<ResourceLocation> previousTypes = Set.of();
    private ScriptUpgradeDefinitions() {}
    public static void beginCycle() {
        previousTypes = FILES.values().stream().filter(JsonElement::isJsonObject).map(JsonElement::getAsJsonObject)
                .filter(j -> j.has("name")).map(j -> id(j.get("name").getAsString())).collect(java.util.stream.Collectors.toSet());
        FILES.clear();
    }
    public static ResourceLocation id(String name) {
        return new ResourceLocation(name.contains(":") ? name : "modular_machinery_reborn:" + name);
    }
    public static void stage(ResourceLocation file, JsonObject body) { FILES.put(file, body.deepCopy()); }
    public static Map<ResourceLocation, JsonElement> files() { return new LinkedHashMap<>(FILES); }
    public static Set<ResourceLocation> previousTypes() { return previousTypes; }
}
