package com.reborn.modularmachinery.authoring;

import com.google.gson.*;
import java.util.Map;

/** Transactional form patches: parse all values on a copy, preserving unrepresented fields. */
public final class RecipeForm {
    private RecipeForm() {}
    public static JsonObject root(JsonObject original, Map<String,String> fields) {
        JsonObject next = original.deepCopy();
        String machine = fields.get("machine").trim();
        next.add("machine",machine.startsWith("[") ? JsonParser.parseString(machine) : new JsonPrimitive(machine));
        putNumber(next,"recipeTime",fields.get("recipeTime")); putNumber(next,"max-parallelism",fields.get("max-parallelism"));
        RecipeDocument.integer(next,"recipeTime",1,Integer.MAX_VALUE); RecipeDocument.integer(next,"max-parallelism",1,Integer.MAX_VALUE);
        return next;
    }
    public static JsonObject requirement(JsonObject original, Map<String,String> fields) {
        JsonObject next = original.deepCopy(); String kind = RecipeDocument.kind(next);
        for (var entry : fields.entrySet()) {
            String key = entry.getKey(), value = entry.getValue().trim();
            if (key.equals("nbt") || key.equals("nbt-display")) {
                if (value.isEmpty()) next.remove(key); else next.add(key,RecipeDocument.read("{\"value\":"+value+"}").get("value"));
            } else if (key.equals(RecipeDocument.resourceKey(kind)) || key.equals("selector-tag")) {
                if (value.isEmpty()) next.remove(key); else next.addProperty(key,value);
            } else putNumber(next,key,value);
        }
        return next;
    }
    public static void putNumber(JsonObject json, String key, String value) {
        if (value.trim().isEmpty()) json.remove(key); else json.addProperty(key,RecipeDocument.number(value));
    }
}
