package com.reborn.modularmachinery.magic;

import com.reborn.modularmachinery.config.ModConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import java.util.List;

public final class MagicConfig {
    private static ForgeConfigSpec.IntValue mana, airVolume;
    private static ForgeConfigSpec.DoubleValue ember;
    private MagicConfig() {}
    public static void define(ForgeConfigSpec.Builder builder) {
        mana = builder.comment("Mana capacity per magic hatch; zero disables storage. Reload the chunk after changing.")
                .defineInRange(List.of("mods.botania", "Mana Capacity"), 100000, 0, Integer.MAX_VALUE);
        airVolume = builder.comment("Base volume in mL for the tier-two PneumaticCraft hatch. Reload the block entity after changing.")
                .defineInRange(List.of("mods.pneumaticcraft", "Air Volume"), 16000, 1, 1000000);
        ember = builder.comment("Ember capacity per Embers Rekindled hatch. Reload the block entity after changing.")
                .defineInRange(List.of("mods.embers", "Ember Capacity"), 256000d, 0d, 1000000000d);
    }
    public static int manaCapacity() { return mana == null || !ModConfig.SPEC.isLoaded() ? 100000 : mana.get(); }
    public static int airVolume() { return airVolume == null || !ModConfig.SPEC.isLoaded() ? 16000 : airVolume.get(); }
    public static double emberCapacity() { return ember == null || !ModConfig.SPEC.isLoaded() ? 256000d : ember.get(); }
}
