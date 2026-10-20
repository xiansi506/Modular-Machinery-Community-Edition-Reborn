package com.reborn.modularmachinery.kubejs;

import dev.latvian.mods.kubejs.recipe.schema.RecipeSchema;

/**
 * The schema registered for {@code modular_machinery_reborn:machine}. Its id must match the real
 * recipe serializer, because {@code RecipeSchemaType#getSerializer()} looks the serializer up by
 * that id.
 */
public final class MachineRecipeSchema extends RecipeSchema {

    public static final MachineRecipeSchema SCHEMA = new MachineRecipeSchema();

    public MachineRecipeSchema() {
        super(MachineRecipeJS.class, MachineRecipeJS::new, MachineRecipeJS.MACHINE);
    }
}
