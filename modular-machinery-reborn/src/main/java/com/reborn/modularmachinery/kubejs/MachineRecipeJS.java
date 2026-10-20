package com.reborn.modularmachinery.kubejs;

import com.google.gson.JsonObject;
import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.recipe.MachineRecipeJsonBuilder;
import dev.latvian.mods.kubejs.recipe.RecipeJS;
import dev.latvian.mods.kubejs.recipe.RecipeKey;

/**
 * The {@link RecipeJS} behind {@code event.recipes.modular_machinery_reborn.machine(...)}.
 *
 * <p>Two shapes share one argument:
 *
 * <pre>{@code
 * // typed: the builder fills in machine/recipeTime/requirements
 * event.recipes.modular_machinery_reborn.machine('alloy_furnace')
 *     .time(200)
 *     .itemInput('minecraft:iron_ore', 2)
 *     .itemOutput('minecraft:iron_ingot', 1)
 *
 * // raw: the object is the same body a datapack file would contain
 * event.recipes.modular_machinery_reborn.machine({
 *     machine: 'alloy_furnace',
 *     recipeTime: 200,
 *     requirements: [ ... ]
 * })
 * }</pre>
 *
 * <p>{@link #deserialize(boolean)} is intentionally empty: datapack recipes and
 * {@code event.custom(...)} already carry a complete JSON body and do not have a
 * {@code machine} key at the top level, so requiring the schema key there would reject them.
 * {@link #serialize()} decides which shape the current recipe is from the key's type.
 */
public final class MachineRecipeJS extends RecipeJS {

    public static final RecipeKey<Object> MACHINE = new RecipeKey<>(new MachineTargetComponent(), "machine");

    private static final String RECIPE_TYPE = ModularMachineryReborn.MOD_ID + ":machine";

    private final MachineRecipeJsonBuilder builder = new MachineRecipeJsonBuilder();

    public MachineRecipeJS() {
    }

    @Override
    public void deserialize(boolean merge) {
        // Raw recipes keep their own body; there is nothing to read from a machine key.
    }

    @Override
    public void serialize() {
        JsonObject output;
        if (this.json != null && this.json.has("requirements")) {
            output = this.json.deepCopy();
        } else {
            Object target = getValue(MACHINE);
            if (target instanceof CharSequence machine) {
                output = this.builder.build(machine.toString());
            } else if (target instanceof JsonObject raw) {
                output = raw.deepCopy();
            } else {
                throw new IllegalStateException("A modular_machinery_reborn:machine recipe needs either a "
                        + "machine id (typed API) or a recipe JSON object (raw API).");
            }
        }
        if (!output.has("type")) {
            output.addProperty("type", RECIPE_TYPE);
        }
        this.json = output;
    }

    public MachineRecipeJS time(int ticks) {
        this.builder.time(ticks);
        return this;
    }

    public MachineRecipeJS parallelism(int max) {
        this.builder.parallelism(max);
        return this;
    }

    public MachineRecipeJS registryName(String name) {
        this.builder.registryName(name);
        return this;
    }

    public MachineRecipeJS requirement(JsonObject requirement) {
        this.builder.requirement(requirement);
        return this;
    }

    public MachineRecipeJS itemInput(String item, int amount) {
        this.builder.itemInput(item, amount);
        return this;
    }

    public MachineRecipeJS itemOutput(String item, int amount) {
        this.builder.itemOutput(item, amount);
        return this;
    }

    public MachineRecipeJS itemOutput(String item, int minAmount, int maxAmount, float chance) {
        this.builder.itemOutput(item, minAmount, maxAmount, chance);
        return this;
    }

    public MachineRecipeJS fluidInput(String fluid, int amount) {
        this.builder.fluidInput(fluid, amount);
        return this;
    }

    public MachineRecipeJS fluidInput(String fluid, int amount, boolean perTick) {
        this.builder.fluidInput(fluid, amount, perTick);
        return this;
    }

    public MachineRecipeJS fluidOutput(String fluid, int amount) {
        this.builder.fluidOutput(fluid, amount);
        return this;
    }

    public MachineRecipeJS fluidOutput(String fluid, int amount, boolean perTick) {
        this.builder.fluidOutput(fluid, amount, perTick);
        return this;
    }

    public MachineRecipeJS energyInput(long perTick) {
        this.builder.energyInput(perTick);
        return this;
    }

    public MachineRecipeJS energyOutput(long perTick) {
        this.builder.energyOutput(perTick);
        return this;
    }

    public MachineRecipeJS catalyst(String item, int amount) {
        this.builder.catalyst(item, amount);
        return this;
    }

    public MachineRecipeJS catalyst(String item, int amount, float chance) {
        this.builder.catalyst(item, amount, chance);
        return this;
    }

    public MachineRecipeJS catalystEntry(String item, int amount) {
        this.builder.catalystEntry(item, amount);
        return this;
    }

    public MachineRecipeJS catalystChance(float chance) {
        this.builder.catalystChance(chance);
        return this;
    }

    public MachineRecipeJS catalystModifier(String target, String io, int operation, float multiplier) {
        this.builder.catalystModifier(target, io, operation, multiplier);
        return this;
    }

    public MachineRecipeJS catalystTooltip(String text) {
        this.builder.catalystTooltip(text);
        return this;
    }
}
