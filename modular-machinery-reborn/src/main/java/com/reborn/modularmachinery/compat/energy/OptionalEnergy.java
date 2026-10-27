package com.reborn.modularmachinery.compat.energy;

import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.energy.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fml.ModList;
import java.lang.reflect.*;
import java.util.*;

/** Two optional API boundaries, verified against Flux 1.20 and Brandon's Core 3.2.1.302. No optional linkage. */
public final class OptionalEnergy {
    private OptionalEnergy() {}
    private record Api(Capability<?> capability, Class<?> type, String receive, String extract, String stored, String capacity,
                       Map<String,Method> methods) {}
    private static final class Loaded {
        static final List<Api> APIS = load();
    }
    private static List<Api> apis() {
        return ModList.get() == null ? List.of() : Loaded.APIS;
    }
    private static boolean enabled(Api api) {
        return !api.receive.equals("receiveEnergyL")
                || com.reborn.modularmachinery.config.ModConfig.enableFluxNetworksIntegration();
    }
    private static List<Api> load() {
        List<Api> result = new ArrayList<>();
        if (ModList.get().isLoaded("fluxnetworks")) add(result, "sonar.fluxnetworks.api.FluxCapabilities", "FN_ENERGY_STORAGE",
                "sonar.fluxnetworks.api.energy.IFNEnergyStorage", "receiveEnergyL", "extractEnergyL", "getEnergyStoredL", "getMaxEnergyStoredL");
        if (ModList.get().isLoaded("draconicevolution")) add(result, "com.brandon3055.brandonscore.capability.CapabilityOP", "OP",
                "com.brandon3055.brandonscore.api.power.IOPStorage", "receiveOP", "extractOP", "getOPStored", "getMaxOPStored");
        return List.copyOf(result);
    }
    private static void add(List<Api> out, String owner, String field, String name, String receive, String extract, String stored, String capacity) {
        try {
            Class<?> type = Class.forName(name);
            Map<String,Method> methods = new HashMap<>();
            methods.put(receive,type.getMethod(receive,long.class,boolean.class));
            methods.put(extract,type.getMethod(extract,long.class,boolean.class));
            for (String getter : List.of(stored,capacity,"canExtract","canReceive")) methods.put(getter,type.getMethod(getter));
            out.add(new Api((Capability<?>) Class.forName(owner).getField(field).get(null), type, receive, extract, stored, capacity,Map.copyOf(methods)));
        } catch (ReflectiveOperationException | LinkageError error) {
            ModularMachineryReborn.LOGGER.warn("Optional energy API {} could not be initialized; FE remains available", name, error);
        }
    }

    public static final class Handle {
        private final IEnergyStorage storage;
        private final Map<Capability<?>, LazyOptional<?>> views = new IdentityHashMap<>();
        private boolean valid = true;
        public Handle(IEnergyStorage storage) { this.storage = storage; }
        public LazyOptional<?> get(Capability<?> capability) {
            for (Api api : apis()) if (api.capability == capability) {
                if (!valid || !enabled(api)) return LazyOptional.empty();
                return views.computeIfAbsent(capability, ignored -> LazyOptional.of(() -> adapter(api.type, storage)));
            }
            return null;
        }
        public void invalidate() { valid = false; views.values().forEach(LazyOptional::invalidate); views.clear(); }
    }

    /** Creates an actual interface instance, not a bundled copy of a third-party API. */
    public static Object adapter(Class<?> type, IEnergyStorage storage) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> switch (method.getName()) {
            case "receiveEnergyL", "receiveOP" -> EnergyAccess.receive(storage, (long) args[0], (boolean) args[1]);
            case "extractEnergyL", "extractOP" -> EnergyAccess.extract(storage, (long) args[0], (boolean) args[1]);
            case "getEnergyStoredL", "getOPStored" -> EnergyAccess.stored(storage);
            case "getMaxEnergyStoredL", "getMaxOPStored" -> EnergyAccess.capacity(storage);
            case "receiveEnergy" -> storage.receiveEnergy((int) args[0], (boolean) args[1]);
            case "extractEnergy" -> storage.extractEnergy((int) args[0], (boolean) args[1]);
            case "getEnergyStored" -> storage.getEnergyStored();
            case "getMaxEnergyStored" -> storage.getMaxEnergyStored();
            case "canReceive" -> storage.canReceive();
            case "canExtract" -> storage.canExtract();
            case "maxReceive" -> storage instanceof EnergyPort port ? port.maxReceiveL() : storage instanceof LongEnergyStorage nativeStorage ? nativeStorage.maxReceiveL() : (long) Integer.MAX_VALUE;
            case "maxExtract" -> storage instanceof EnergyPort port ? port.maxExtractL() : storage instanceof LongEnergyStorage nativeStorage ? nativeStorage.maxExtractL() : (long) Integer.MAX_VALUE;
            case "getIOInfo" -> null;
            case "modifyEnergyStored" -> {
                long delta = (long) args[0];
                yield storage instanceof EnergyPort port ? port.modify(delta) : storage instanceof LongEnergyStorage nativeStorage ? nativeStorage.modify(delta)
                        : delta >= 0 ? EnergyAccess.receive(storage, delta, false)
                        : EnergyAccess.extract(storage, delta == Long.MIN_VALUE ? Long.MAX_VALUE : -delta, false);
            }
            case "toString" -> "Modular Machinery " + type.getSimpleName() + " adapter";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> {
                if (!method.isDefault()) throw new UnsupportedOperationException(method.toString());
                yield InvocationHandler.invokeDefault(proxy, method, args);
            }
        });
    }

    /** Prefer a single long capability over its FE facade, so output is never sent through both. */
    public static IEnergyStorage find(BlockEntity entity, Direction side) {
        for (Api api : apis()) {
            if (!enabled(api)) continue;
            Object target = entity.getCapability(api.capability, side).orElse(null);
            if (target != null) return new External(api, target);
        }
        return null;
    }
    private record External(Api api, Object target) implements LongEnergyAccess {
        private Object invoke(String name, Object... args) {
            try { return api.methods.get(name).invoke(target, args); }
            catch (InvocationTargetException error) {
                if (error.getCause() instanceof RuntimeException cause) throw cause;
                throw new IllegalStateException(error.getCause());
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        @Override public long receiveEnergyL(long amount, boolean simulate) { return ((Number) invoke(api.receive, amount, simulate)).longValue(); }
        @Override public long extractEnergyL(long amount, boolean simulate) { return ((Number) invoke(api.extract, amount, simulate)).longValue(); }
        @Override public long getEnergyStoredL() { return Math.max(0, ((Number) invoke(api.stored)).longValue()); }
        @Override public long getMaxEnergyStoredL() { return Math.max(0, ((Number) invoke(api.capacity)).longValue()); }
        @Override public boolean canExtract() { return (boolean) invoke("canExtract"); }
        @Override public boolean canReceive() { return (boolean) invoke("canReceive"); }
    }
}
