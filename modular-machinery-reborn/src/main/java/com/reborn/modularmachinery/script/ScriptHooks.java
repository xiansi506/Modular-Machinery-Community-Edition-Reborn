package com.reborn.modularmachinery.script;

import com.mojang.logging.LogUtils;
import com.reborn.modularmachinery.api.event.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Server callbacks contain no dependency on a scripting engine. Tokens survive recipe copies and saves;
 * functions are rebuilt by the current server-script reload and are never sent to clients. */
public final class ScriptHooks {
    @FunctionalInterface public interface ItemChecker {
        boolean test(MachineControllerAccess controller, ItemStack stack);
    }
    @FunctionalInterface public interface ItemModifier {
        ItemStack apply(MachineControllerAccess controller, ItemStack stack);
    }
    @FunctionalInterface public interface BlockChecker {
        boolean test(Level world, BlockPos pos, BlockState state, CompoundTag nbt);
    }
    private record Key(boolean recipe, ResourceLocation id, String event) {}
    private static final Map<Key, List<Consumer<MachineEvent>>> EVENTS = new ConcurrentHashMap<>();
    private static final Map<String, ItemChecker> ITEMS = new ConcurrentHashMap<>();
    private static final Map<String, ItemModifier> MODIFIERS = new ConcurrentHashMap<>();
    private static final Map<String, BlockChecker> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<Key, List<java.util.function.BiConsumer<MachineEvent, MachineControllerAccess.UpgradeAccess>>> UPGRADES = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Set<String> NAMES = Set.of("structureFormed", "structureUpdated", "machineTick",
            "recipeCheck", "recipeStart", "recipeTick", "recipeFinish", "recipeFailure",
            "factoryRecipeStart", "factoryRecipeTick", "factoryRecipeFinish", "factoryRecipeFailure",
            "resultChance", "smartInterfaceUpdate");

    private ScriptHooks() {}
    public static void clear() {
        EVENTS.clear(); ITEMS.clear(); MODIFIERS.clear(); BLOCKS.clear(); UPGRADES.clear(); REPORTED.clear();
    }
    public static void validateEvent(String name) {
        if (!NAMES.contains(name)) throw new IllegalArgumentException("Unknown machine event '" + name
                + "'; expected one of " + NAMES);
    }
    public static void events(boolean recipe, ResourceLocation id, String event, List<Consumer<MachineEvent>> handlers) {
        validateEvent(event);
        EVENTS.put(new Key(recipe, id, event), List.copyOf(handlers));
    }
    public static void replaceScope(boolean recipe, ResourceLocation id) {
        EVENTS.keySet().removeIf(key -> key.recipe() == recipe && java.util.Objects.equals(key.id(), id));
    }
    public static void replaceUpgrade(ResourceLocation id) { UPGRADES.keySet().removeIf(key -> key.id().equals(id)); }
    public static void itemChecker(String key, ItemChecker checker) { ITEMS.put(key, checker); }
    public static void itemModifier(String key, ItemModifier modifier) { MODIFIERS.put(key, modifier); }
    public static void blockChecker(String key, BlockChecker checker) { BLOCKS.put(key, checker); }
    public static void upgradeEvents(ResourceLocation id, String event, List<java.util.function.BiConsumer<MachineEvent, MachineControllerAccess.UpgradeAccess>> handlers) {
        validateEvent(event); UPGRADES.put(new Key(false, id, event), List.copyOf(handlers));
    }

    public static boolean checkItem(String key, MachineControllerAccess controller, ItemStack stack) {
        try {
            ItemChecker checker = ITEMS.get(key);
            if (checker == null) throw new IllegalStateException("The current scripts did not define this item checker");
            return checker.test(controller, stack.copy());
        } catch (Exception error) { report(key, error); return false; }
    }
    /** Null signals an error, distinct from EMPTY (an intentional zero amount). */
    public static ItemStack modifyItem(String key, MachineControllerAccess controller, ItemStack stack) {
        try {
            ItemModifier modifier = MODIFIERS.get(key);
            if (modifier == null) throw new IllegalStateException("The current scripts did not define this item modifier");
            ItemStack result = modifier.apply(controller, stack.copy());
            if (result == null) throw new IllegalArgumentException("Item modifier must return an ItemStack");
            return result.copy();
        } catch (Exception error) { report(key, error); return null; }
    }
    public static boolean checkBlock(String key, Level world, BlockPos pos, BlockState state, CompoundTag nbt) {
        try {
            BlockChecker checker = BLOCKS.get(key);
            if (checker == null) throw new IllegalStateException("The current scripts did not define this block checker");
            return checker.test(world, pos.immutable(), state, nbt.copy());
        } catch (Exception error) { report(key, error); return false; }
    }
    public static void dispatch(MachineEvent event) {
        String name = name(event);
        if (name == null) return;
        invoke(new Key(false, event.machineId(), name), event);
        if (event instanceof RecipeEvent recipe) invoke(new Key(true, recipe.recipe().getId(), name), event);
        if (!UPGRADES.isEmpty()) for (var upgrade : new MachineControllerAccess(event.controller()).getMachineUpgrades()) {
            var key = new Key(false, upgrade.getId(), name);
            for (var handler : UPGRADES.getOrDefault(key, List.of())) {
                try { handler.accept(event, upgrade); }
                catch (Exception error) {
                    report(key.toString(), error);
                    if (event instanceof RecipeCheckEvent check) check.setFailed("Upgrade script callback failed");
                    else if (event instanceof RecipeTickEvent tick) tick.preventProgressing("Upgrade script callback failed");
                    else if (event.isCancelable()) event.setCanceled(true);
                }
            }
        }
    }
    private static void invoke(Key key, MachineEvent event) {
        for (Consumer<MachineEvent> handler : EVENTS.getOrDefault(key, List.of())) {
            try { handler.accept(event); }
            catch (Exception error) {
                report(key.toString(), error);
                if (event instanceof RecipeCheckEvent check) check.setFailed("Script callback failed: " + key.event());
                else if (event instanceof RecipeTickEvent tick) tick.preventProgressing("Script callback failed: " + key.event());
                else if (event.isCancelable()) event.setCanceled(true);
            }
        }
    }
    public static String name(MachineEvent event) {
        if (event instanceof FactoryRecipeStartEvent) return "factoryRecipeStart";
        if (event instanceof FactoryRecipeTickEvent) return "factoryRecipeTick";
        if (event instanceof FactoryRecipeFinishEvent) return "factoryRecipeFinish";
        if (event instanceof FactoryRecipeFailureEvent) return "factoryRecipeFailure";
        if (event instanceof RecipeCheckEvent) return "recipeCheck";
        if (event instanceof RecipeStartEvent) return "recipeStart";
        if (event instanceof RecipeTickEvent) return "recipeTick";
        if (event instanceof RecipeFinishEvent) return "recipeFinish";
        if (event instanceof RecipeFailureEvent) return "recipeFailure";
        if (event instanceof ResultChanceCreateEvent) return "resultChance";
        if (event instanceof SmartInterfaceUpdateEvent) return "smartInterfaceUpdate";
        if (event instanceof MachineStructureFormedEvent) return "structureFormed";
        if (event instanceof MachineStructureUpdateEvent) return "structureUpdated";
        if (event instanceof MachineTickEvent) return "machineTick";
        return null;
    }
    private static void report(String key, Exception error) {
        if (REPORTED.add(key)) LogUtils.getLogger().error("Machine script '{}' failed (reported once per reload)", key, error);
    }
}
