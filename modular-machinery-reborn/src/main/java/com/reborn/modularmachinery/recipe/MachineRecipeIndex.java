package com.reborn.modularmachinery.recipe;

import com.reborn.modularmachinery.ModularMachineryReborn;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;

import java.lang.reflect.Field;
import java.lang.ref.WeakReference;
import java.util.*;

/** Static definition/order cache, separate from inventory-dependent recipe simulation. */
public final class MachineRecipeIndex {
    private record Index(WeakReference<Object> version, Map<ResourceLocation, List<MachineRecipe>> byMachine) {}
    private static final Map<RecipeManager, Index> INDEXES = new WeakHashMap<>();
    private static final Field RECIPES = recipeTableField();
    private MachineRecipeIndex() {}

    private static Field recipeTableField() {
        // Verified against Forge 1.20.1: both apply() and replaceRecipes() replace this map.
        // Reading its identity avoids getRecipes(), which allocates and scans the full table.
        for (String name : new String[]{"recipes", "f_44007_"}) {
            try {
                Field field = RecipeManager.class.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
        ModularMachineryReborn.LOGGER.warn("Recipe table access unavailable; using uncached recipe order");
        return null;
    }

    public static synchronized List<MachineRecipe> forMachine(RecipeManager manager, ResourceLocation machine) {
        Object version;
        try { version = RECIPES == null ? null : RECIPES.get(manager); }
        catch (IllegalAccessException | RuntimeException unavailable) { version = null; }
        if (version == null) return RecipeOrder.forMachine(manager.getAllRecipesFor(ModRecipeTypes.MACHINE.get()), machine);
        Index index = INDEXES.get(manager);
        if (index == null || index.version().get() != version) {
            Map<ResourceLocation, List<MachineRecipe>> grouped = new HashMap<>();
            for (MachineRecipe recipe : manager.getAllRecipesFor(ModRecipeTypes.MACHINE.get())) {
                for (ResourceLocation id : new LinkedHashSet<>(recipe.machineIds()))
                    grouped.computeIfAbsent(id, ignored -> new ArrayList<>()).add(recipe);
            }
            grouped.replaceAll((id, recipes) -> {
                recipes.sort(RecipeOrder.COMPARATOR);
                return List.copyOf(recipes);
            });
            index = new Index(new WeakReference<>(version), Map.copyOf(grouped));
            INDEXES.put(manager, index);
        }
        return index.byMachine().getOrDefault(machine, List.of());
    }
}
