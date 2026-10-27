package com.reborn.modularmachinery.client.jei;

import com.reborn.modularmachinery.client.preview.PreviewLayout;
import com.reborn.modularmachinery.client.preview.PreviewPanel;
import com.reborn.modularmachinery.client.preview.StructurePreviews;
import mezz.jei.api.gui.ingredient.IRecipeSlotDrawable;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Updates real JEI slots before JEI draws them; display overrides preserve the search index. */
final class PreviewIngredientSlots {
    static final int HIDDEN = -10000;
    static final String COMPONENT = "preview_component_";
    static final String SELECTED = "preview_selected_";
    private final PreviewPanel panel;
    private final Map<String, IRecipeSlotDrawable> slots = new HashMap<>();
    private final Map<String, Display> displayed = new HashMap<>();
    private record Display(ItemStack stack, int x, int y) {}

    /** JEI can retain several layouts for one machine while navigating recipe history. */
    static final class Layouts {
        private final Map<IRecipeSlotDrawable, PreviewIngredientSlots> layouts = new LinkedHashMap<>(16, .75F, true);
        PreviewIngredientSlots get(PreviewPanel panel, List<IRecipeSlotDrawable> slots) {
            var key = slots.stream().filter(slot -> slot.getSlotName().orElse("").equals(COMPONENT + 0))
                    .findFirst().orElse(null);
            if (key == null) return null;
            var result = layouts.computeIfAbsent(key, ignored -> new PreviewIngredientSlots(panel, slots));
            if (layouts.size() > 32) layouts.remove(layouts.keySet().iterator().next());
            return result;
        }
    }

    PreviewIngredientSlots(PreviewPanel panel, List<IRecipeSlotDrawable> slots) {
        this.panel = panel;
        for (var slot : slots) slot.getSlotName().ifPresent(name -> this.slots.put(name, slot));
        sync();
    }

    void sync() {
        var components = panel.components();
        int shown = StructurePreviews.visibleComponentCount(components.size());
        for (int i = 0; i < PreviewLayout.COMPONENT_CAPACITY; i++) {
            update(COMPONENT + i, i < shown ? components.get(i) : ItemStack.EMPTY,
                    PreviewLayout.componentItemX(i), PreviewLayout.componentItemY(i));
        }
        var selected = panel.selectedIngredients();
        for (int i = 0; i < 7; i++) {
            var entry = i < selected.size() ? selected.get(i) : null;
            update(SELECTED + i, entry == null ? ItemStack.EMPTY : entry.stack(),
                    entry == null ? HIDDEN : entry.x(), entry == null ? HIDDEN : entry.y());
        }
    }

    private void update(String name, ItemStack stack, int x, int y) {
        var slot = slots.get(name);
        if (slot == null) return;
        if (stack.isEmpty()) { x = HIDDEN; y = HIDDEN; }
        Display previous = displayed.get(name);
        if (previous != null && previous.x == x && previous.y == y
                && ItemStack.matches(previous.stack, stack)
                // JEI clears overrides when its ingredient cycler advances. Our own snapshot can
                // still match while the actual slot has reverted to the indexed/focused item.
                && ItemStack.matches(slot.getDisplayedItemStack().orElse(ItemStack.EMPTY), stack)) return;
        slot.setPosition(x, y);
        // JEI reuses the override acceptor, so replace it rather than append stale counts/alternatives.
        slot.clearDisplayOverrides();
        // An empty override intentionally hides a seeded slot.
        var override = slot.createDisplayOverrides();
        if (!stack.isEmpty()) override.addItemStack(stack.copy());
        displayed.put(name, new Display(stack.copy(), x, y));
    }
}
