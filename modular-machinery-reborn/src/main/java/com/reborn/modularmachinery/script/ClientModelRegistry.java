package com.reborn.modularmachinery.script;

import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.api.event.ControllerModelGetEvent;
import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.machine.GeoModelSpec;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import javax.annotation.Nullable;
import java.util.*;

/** Client presentation data only: definitions contain no world, script closure or optional-library object. */
public final class ClientModelRegistry {
    public record Selection(String name, @Nullable GeoModelSpec spec) {}
    private static final Map<ResourceLocation, GeoModelSpec> MODELS = new LinkedHashMap<>();
    private static final Set<String> WARNED = new HashSet<>();
    private static long generation;
    private ClientModelRegistry() {}
    public static void beginReload() { MODELS.clear(); invalidateCaches(); }
    public static void invalidateCaches() { generation++; WARNED.clear(); }
    public static long generation() { return generation; }
    public static void register(String name, GeoModelSpec spec) { MODELS.put(id(name), Objects.requireNonNull(spec)); generation++; }
    public static void remove(String name) { MODELS.remove(id(name)); generation++; }
    public static boolean contains(String name) { return MODELS.containsKey(id(name)); }
    public static List<String> names() { return MODELS.keySet().stream().map(ResourceLocation::toString).toList(); }
    private static ResourceLocation id(String name) {
        ResourceLocation id = name == null ? null : ResourceLocation.tryParse(name);
        if (id == null || name.isBlank()) throw new IllegalArgumentException("Invalid client model name: " + name);
        return id;
    }
    public static Selection resolve(MachineControllerBlockEntity controller, @Nullable GeoModelSpec fallback) {
        ControllerModelGetEvent event = new ControllerModelGetEvent(controller, fallback);
        try { MinecraftForge.EVENT_BUS.post(event); }
        catch (RuntimeException | LinkageError error) { warn("event:" + controller.machineId(), error); return new Selection("", fallback); }
        if (event.getModel() != null) return new Selection(event.getModel().model().toString(), event.getModel());
        if (event.getModelName().isEmpty()) return new Selection("", fallback);
        ResourceLocation name = ResourceLocation.tryParse(event.getModelName());
        GeoModelSpec model = name == null ? null : MODELS.get(name);
        if (model == null) {
            warn("missing:" + event.getModelName(), new IllegalArgumentException("Unknown client model: " + event.getModelName()));
            return new Selection("", fallback);
        }
        return new Selection(name.toString(), model);
    }
    private static void warn(String key, Throwable error) {
        if (WARNED.add(key)) ModularMachineryReborn.LOGGER.warn("Client model selection failed for {}; using definition model", key, error);
    }
}
