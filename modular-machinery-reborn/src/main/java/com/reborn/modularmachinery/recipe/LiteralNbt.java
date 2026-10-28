package com.reborn.modularmachinery.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;

/** Lossless tool snapshots. The array envelope cannot be confused with a legacy compound template. */
public final class LiteralNbt {
    private static final String FORMAT = "mmr:literal_nbt_v1";
    private LiteralNbt() {}

    public static boolean isEncoded(JsonElement json) {
        return json != null && json.isJsonArray() && !json.getAsJsonArray().isEmpty()
                && json.getAsJsonArray().get(0).isJsonPrimitive()
                && json.getAsJsonArray().get(0).getAsJsonPrimitive().isString()
                && FORMAT.equals(json.getAsJsonArray().get(0).getAsString());
    }

    public static JsonArray encode(CompoundTag tag) {
        JsonArray encoded = new JsonArray();
        encoded.add(FORMAT);
        encoded.add(tag.toString());
        if (!tag.equals(decode(encoded, "construct tool"))) {
            throw new JsonParseException("The block entity NBT cannot be exported without changing its types.");
        }
        return encoded;
    }

    public static CompoundTag decode(JsonElement json, Object where) {
        if (!isEncoded(json) || json.getAsJsonArray().size() != 2
                || !json.getAsJsonArray().get(1).isJsonPrimitive()
                || !json.getAsJsonArray().get(1).getAsJsonPrimitive().isString()) {
            throw new JsonParseException("Malformed literal NBT snapshot in " + where);
        }
        try {
            return TagParser.parseTag(json.getAsJsonArray().get(1).getAsString());
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException error) {
            throw new JsonParseException("Invalid literal NBT snapshot in " + where, error);
        }
    }
}
