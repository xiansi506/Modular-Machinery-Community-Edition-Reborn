package com.reborn.modularmachinery.script;

import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RecipeModifiers;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** State owned by one controller or one factory slot, never by a shared recipe definition. */
public final class MachineScriptState {
    private CompoundTag data = new CompoundTag();
    private final Map<String, RecipeModifiers> modifiers = new LinkedHashMap<>();
    private final Map<String, RecipeModifiers> permanent = new LinkedHashMap<>();
    private String status = "";
    private final Map<String, List<net.minecraft.resources.ResourceLocation>> coreThreads = new LinkedHashMap<>();
    public void coreThread(String name, List<net.minecraft.resources.ResourceLocation> recipes) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Core thread name must not be blank");
        coreThreads.put(name, List.copyOf(recipes));
    }
    public void removeCoreThread(String name) { coreThreads.remove(name); }
    public List<com.reborn.modularmachinery.factory.FactoryThreadModel.CoreThreadSpec> coreThreads() {
        return coreThreads.entrySet().stream().map(e -> new com.reborn.modularmachinery.factory.FactoryThreadModel.CoreThreadSpec(e.getKey(), e.getValue())).toList();
    }

    public CompoundTag data() { return data; }
    public void data(CompoundTag data) { this.data = data.copy(); }
    public String status() { return status; }
    public void status(String text) { status = text == null ? "" : text; }
    public void add(String name, RecipeModifier modifier, boolean persistent) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Modifier name must not be blank");
        modifiers.remove(name); permanent.remove(name);
        (persistent ? permanent : modifiers).put(name, RecipeModifiers.of(List.of(modifier)));
    }
    public boolean has(String name) { return modifiers.containsKey(name) || permanent.containsKey(name); }
    public void remove(String name) { modifiers.remove(name); permanent.remove(name); }
    public void clearCraft() { modifiers.clear(); }
    public boolean isEmpty() { return data.isEmpty() && modifiers.isEmpty() && permanent.isEmpty() && status.isEmpty() && coreThreads.isEmpty(); }
    public RecipeModifiers modifiers() {
        RecipeModifiers all = RecipeModifiers.EMPTY;
        for (RecipeModifiers value : permanent.values()) all = all.andThen(value);
        for (RecipeModifiers value : modifiers.values()) all = all.andThen(value);
        return all;
    }
    public CompoundTag save() {
        CompoundTag out = new CompoundTag();
        out.put("data", data.copy()); out.putString("status", status);
        out.put("modifiers", saveMap(modifiers)); out.put("permanent", saveMap(permanent));
        CompoundTag threads = new CompoundTag();
        coreThreads.forEach((name, recipes) -> { var ids = new net.minecraft.nbt.ListTag(); recipes.forEach(id -> ids.add(net.minecraft.nbt.StringTag.valueOf(id.toString()))); threads.put(name, ids); });
        out.put("coreThreads", threads);
        return out;
    }
    private static CompoundTag saveMap(Map<String, RecipeModifiers> values) {
        CompoundTag tag = new CompoundTag();
        values.forEach((key, value) -> tag.put(key, value.save()));
        return tag;
    }
    public void load(CompoundTag tag) {
        data = tag.getCompound("data").copy(); status = tag.getString("status");
        loadMap(modifiers, tag.getCompound("modifiers")); loadMap(permanent, tag.getCompound("permanent"));
        coreThreads.clear();
        CompoundTag threads = tag.getCompound("coreThreads");
        for (String name : threads.getAllKeys()) {
            var ids = threads.getList(name, Tag.TAG_STRING); var recipes = new java.util.ArrayList<net.minecraft.resources.ResourceLocation>();
            for (int i = 0; i < ids.size(); i++) { var id = net.minecraft.resources.ResourceLocation.tryParse(ids.getString(i)); if (id != null) recipes.add(id); }
            coreThread(name, recipes);
        }
    }
    private static void loadMap(Map<String, RecipeModifiers> values, CompoundTag tag) {
        values.clear();
        for (String key : tag.getAllKeys()) values.put(key, RecipeModifiers.load(tag.getList(key, Tag.TAG_COMPOUND)));
    }
}
