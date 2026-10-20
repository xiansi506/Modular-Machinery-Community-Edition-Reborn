package com.reborn.modularmachinery.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The JSON reader for {@code catalyst}.
 *
 * <p>The original never read a catalyst from JSON: {@code RequirementTypeCatalyst#createRequirement} returned
 * {@code null} unconditionally, so the only way to declare one was the CraftTweaker {@code RecipePrimer}. This
 * type therefore has no legacy JSON shape to preserve; it is a new, deliberately small schema:
 *
 * <pre>{@code
 * { "type": "modular_machinery_reborn:catalyst", "io-type": "input",
 *   "items": [ { "item": "minecraft:diamond", "amount": 1 } ],
 *   "modifiers": [ { "target": "duration", "io": "input", "operation": 1, "multiplier": 0.5 } ],
 *   "tooltip": [ "Speeds this recipe up" ] }
 * }</pre>
 *
 * <p>{@code items} uses the exact entry grammar of {@code ingredient_array_input} ({@code item} or {@code #tag},
 * an optional per-entry {@code amount}, and a requirement-level {@code amount} as fallback), so a pack author
 * learns one shape for both. {@code chance}, {@code nbt} and {@code amount} are read the same way too.
 */
public final class CatalystParser {

    static final Set<String> KEYS = Set.of("items", "amount", "chance", "nbt", "modifiers", "tooltip");

    private CatalystParser() {
    }

    public static CatalystRequirement parse(JsonObject json, IOType ioType, Object where) {
        if (ioType != IOType.INPUT) {
            throw new JsonParseException("A catalyst in " + where + " is written with \"io-type\": "
                    + "\"output\", but a catalyst is an <input>: the original's RequirementCatalyst extends "
                    + "RequirementIngredientArray and was only ever built as an input. Write \"io-type\": "
                    + "\"input\".");
        }
        IngredientArrayParser.rejectUnknownKeys(json, KEYS, where,
                "A catalyst understands 'items' (required), 'amount', 'chance' and 'nbt' (all applying to the "
                        + "whole group), plus 'modifiers' (an array of recipe modifiers) and 'tooltip' (an array "
                        + "of strings).");

        List<IngredientArrayEntry> entries = IngredientArrayParser.readEntries(json, where, "catalyst");
        float chance = IngredientArrayParser.readChance(json, where);
        NbtPredicate nbt = IngredientArrayParser.readNbt(json, where);

        List<RecipeModifier> modifiers = new ArrayList<>();
        JsonElement rawModifiers = json.get("modifiers");
        if (rawModifiers != null) {
            if (!rawModifiers.isJsonArray()) {
                throw new JsonParseException("'modifiers' in " + where + " must be an array of modifier "
                        + "objects, e.g. [{\"target\": \"duration\", \"io\": \"input\", "
                        + "\"operation\": 1, \"multiplier\": 0.5}].");
            }
            JsonArray array = rawModifiers.getAsJsonArray();
            for (JsonElement element : array) {
                modifiers.add(RecipeModifier.parse(element, where));
            }
        }

        List<String> tooltips = new ArrayList<>();
        JsonElement rawTooltip = json.get("tooltip");
        if (rawTooltip != null) {
            if (!rawTooltip.isJsonArray()) {
                throw new JsonParseException("'tooltip' in " + where + " must be an array of strings, e.g. "
                        + "[\"Speeds this recipe up\"].");
            }
            JsonArray array = rawTooltip.getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    throw new JsonParseException("Every entry of 'tooltip' in " + where + " must be a string.");
                }
                tooltips.add(element.getAsString());
            }
        }

        return new CatalystRequirement(entries, chance, nbt, modifiers, tooltips);
    }

    // ------------------------------------------------------------------ network

    static void write(FriendlyByteBuf buf, CatalystRequirement catalyst) {
        // Same first field as every other requirement body: the parser reads the IO type before handing the rest
        // to readBody (MachineRequirementParser:183-184), so a body that skipped it would desync the whole packet.
        buf.writeUtf(catalyst.ioType().name());
        buf.writeFloat(catalyst.chance());
        NbtPredicate nbt = catalyst.nbt();
        buf.writeBoolean(nbt != null);
        if (nbt != null) {
            buf.writeUtf(nbt.source());
        }
        buf.writeVarInt(catalyst.entryCount());
        for (int index = 0; index < catalyst.entryCount(); index++) {
            buf.writeBoolean(catalyst.entryIsTag(index));
            catalyst.entryIngredient(index).toNetwork(buf);
            buf.writeUtf(catalyst.entryItemId(index));
            buf.writeVarInt(catalyst.entryAmount(index));
        }
        buf.writeVarInt(catalyst.modifiers().size());
        for (RecipeModifier modifier : catalyst.modifiers()) {
            RecipeModifier.write(buf, modifier);
        }
        buf.writeVarInt(catalyst.tooltips().size());
        for (String tooltip : catalyst.tooltips()) {
            buf.writeUtf(tooltip);
        }
    }

    static CatalystRequirement read(FriendlyByteBuf buf) {
        // The IO type was already read by MachineRequirementParser#read and is always INPUT for a catalyst, so the
        // body starts at chance. (write still emits it — every requirement body starts with that field on the wire.)
        float chance = buf.readFloat();
        NbtPredicate nbt = buf.readBoolean() ? NbtPredicate.parse(buf.readUtf(), "catalyst") : null;
        int entryCount = buf.readVarInt();
        List<IngredientArrayEntry> entries = new ArrayList<>(entryCount);
        for (int index = 0; index < entryCount; index++) {
            boolean tagged = buf.readBoolean();
            net.minecraft.world.item.crafting.Ingredient ingredient =
                    net.minecraft.world.item.crafting.Ingredient.fromNetwork(buf);
            String itemId = buf.readUtf();
            int amount = buf.readVarInt();
            // Entries carry no per-entry chance for a catalyst: the JSON parser only ever builds them with 1.0F,
            // and the requirement rolls the group's chance once in apply(). Keeping that shape here means a
            // round-tripped requirement compares equal to the parsed one.
            entries.add(tagged
                    ? IngredientArrayEntry.ofTag(ingredient, itemId, amount, 1.0F)
                    : IngredientArrayEntry.ofItem(LegacyIngredients.firstStack(ingredient), amount, 1.0F));
        }
        int modifierCount = buf.readVarInt();
        List<RecipeModifier> modifiers = new ArrayList<>(modifierCount);
        for (int index = 0; index < modifierCount; index++) {
            modifiers.add(RecipeModifier.read(buf));
        }
        int tooltipCount = buf.readVarInt();
        List<String> tooltips = new ArrayList<>(tooltipCount);
        for (int index = 0; index < tooltipCount; index++) {
            tooltips.add(buf.readUtf());
        }
        return new CatalystRequirement(entries, chance, nbt, modifiers, tooltips);
    }
}
