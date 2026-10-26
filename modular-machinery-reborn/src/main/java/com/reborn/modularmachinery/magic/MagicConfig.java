package com.reborn.modularmachinery.magic;

import com.reborn.modularmachinery.config.ModConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import java.util.List;

public final class MagicConfig {
    private static ForgeConfigSpec.IntValue mana;
    private MagicConfig() {}
    public static void define(ForgeConfigSpec.Builder builder) {
        mana = builder.comment("Mana capacity per magic hatch; zero disables storage. Reload the chunk after changing.")
                .defineInRange(List.of("mods.botania", "Mana Capacity"), 100000, 0, Integer.MAX_VALUE);
    }
    public static int manaCapacity() { return mana == null || !ModConfig.SPEC.isLoaded() ? 100000 : mana.get(); }
}
