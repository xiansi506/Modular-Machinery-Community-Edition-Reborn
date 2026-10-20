package com.reborn.modularmachinery.kubejs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import dev.latvian.mods.kubejs.recipe.RecipeJS;
import dev.latvian.mods.kubejs.recipe.component.RecipeComponent;
import dev.latvian.mods.kubejs.util.JsonIO;

import java.util.Map;

/**
 * The single typed key of {@link MachineRecipeSchema}: either a machine id (typed mode) or a
 * complete recipe JSON object (raw mode).
 *
 * <p>The raw form has to stay on the same key because KubeJS resolves a one-argument call through
 * the schema's constructor before it considers the raw {@code Map}/{@code JsonObject} path. A
 * string selects the typed builder; an object is copied verbatim and later handed to the same
 * {@code MachineSerializer} that reads datapack recipes.
 */
public final class MachineTargetComponent implements RecipeComponent<Object> {

    @Override
    public Class<?> componentClass() {
        return Object.class;
    }

    @Override
    public String componentType() {
        return "machine";
    }

    @Override
    public JsonElement write(RecipeJS recipe, Object value) {
        if (value instanceof CharSequence id) {
            return new JsonPrimitive(id.toString());
        }
        if (value instanceof JsonElement element) {
            return element;
        }
        return JsonIO.of(value);
    }

    @Override
    public Object read(RecipeJS recipe, Object value) {
        if (value instanceof CharSequence id) {
            String text = id.toString().trim();
            if (text.isEmpty()) {
                throw new JsonParseException("The machine key must be a non-empty machine id or a recipe "
                        + "JSON object.");
            }
            return text;
        }
        JsonElement element = value instanceof JsonElement json ? json : JsonIO.of(value);
        if (element == null || !element.isJsonObject()) {
            throw new JsonParseException("The machine key must be a machine id (for the typed API) or a "
                    + "recipe JSON object (for the raw API), but got " + value + ".");
        }
        return element.deepCopy();
    }
}
