package com.reborn.modularmachinery.magic;

import net.minecraft.resources.ResourceLocation;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/** Only registered optional items are named in loaded loot data. */
public final class MagicLootTables {
    private MagicLootTables() {}
    public static Map<ResourceLocation,byte[]> generate(Predicate<String> loaded) {
        Map<ResourceLocation,byte[]> result = new LinkedHashMap<>();
        for (MagicFamily family : MagicFamily.values()) if (loaded.test(family.modId)) for (boolean input : new boolean[]{true,false}) {
            String name = family.path() + (input ? "_input_hatch" : "_output_hatch");
            String json = "{\"type\":\"minecraft:block\",\"pools\":[{\"rolls\":1,\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"modular_machinery_reborn:"+name+"\"}],\"conditions\":[{\"condition\":\"minecraft:survives_explosion\"}]}]}";
            result.put(new ResourceLocation("modular_machinery_reborn","loot_tables/blocks/"+name+".json"),json.getBytes(StandardCharsets.UTF_8));
        }
        return result;
    }
}
