package com.reborn.modularmachinery.magic;

import com.reborn.modularmachinery.compat.*;
import net.minecraft.resources.ResourceLocation;

/** Constructors and schema registration never resolve an optional API. */
public record MagicModule(String modId) implements CompatModule {
    public ResourceLocation id() { return new ResourceLocation("modular_machinery_reborn", modId); }
    public String requiresModid() { return modId; }
    public void register(CompatRegistry registry) {
        for (MagicFamily family : MagicFamily.values()) if (family.modId.equals(modId) && family != MagicFamily.AIR) CompatRegistry.requirement(MagicRequirements.type(family));
        if (modId.equals("pneumaticcraft")) CompressedAirRequirement.register();
    }
}
