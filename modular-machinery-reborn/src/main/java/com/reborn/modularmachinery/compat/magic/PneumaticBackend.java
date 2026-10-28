package com.reborn.modularmachinery.compat.magic;

import com.reborn.modularmachinery.magic.*;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import java.util.List;
import static com.reborn.modularmachinery.compat.magic.ApiReflection.*;

/** PNC 6.0.23 public factory/API. Its native handler owns dispersion, vacuum and overpressure. */
public final class PneumaticBackend implements MagicBackend {
    private final MagicHatchBlockEntity hatch;
    private final Object handler;
    private final Class<?> api = type("me.desht.pneumaticcraft.api.tileentity.IAirHandlerMachine");
    private final Capability<?> capability = (Capability<?>)field(type("me.desht.pneumaticcraft.api.PNCCapabilities"), "AIR_HANDLER_MACHINE_CAPABILITY");
    private LazyOptional<Object> view;
    private boolean valid = true;
    public PneumaticBackend(MagicHatchBlockEntity hatch) {
        this.hatch = hatch;
        Class<?> registry = type("me.desht.pneumaticcraft.api.PneumaticRegistry");
        Object instance = call(registry, null, "getInstance", new Class<?>[0]);
        Object factory = call(type("me.desht.pneumaticcraft.api.PneumaticRegistry$IPneumaticCraftInterface"), instance,
                "getAirHandlerMachineFactory", new Class<?>[0]);
        handler = call(type("me.desht.pneumaticcraft.api.tileentity.IAirHandlerMachineFactory"), factory,
                "createTierTwoAirHandler", new Class<?>[]{int.class}, MagicConfig.airVolume());
        view = LazyOptional.of(() -> handler);
    }
    public MagicFamily family() { return MagicFamily.AIR; }
    public boolean input() { return hatch.input(); }
    public Object airHandler() { return handler; }
    public double air() { return ((Number)call(api, handler, "getAir", new Class<?>[0])).doubleValue(); }
    public double volume() { return ((Number)call(api, handler, "getVolume", new Class<?>[0])).doubleValue(); }
    public double pressure() { return ((Number)call(api, handler, "getPressure", new Class<?>[0])).doubleValue(); }
    public double dangerPressure() { return ((Number)call(api, handler, "getDangerPressure", new Class<?>[0])).doubleValue(); }
    public double criticalPressure() { return ((Number)call(api, handler, "getCriticalPressure", new Class<?>[0])).doubleValue(); }
    public LazyOptional<?> capability(Capability<?> queried) { return queried == capability ? view : null; }
    @Override public void neighborChanged() { call(api, handler, "setConnectedFaces", new Class<?>[]{List.class}, List.of(Direction.values())); }
    @Override public void load() { neighborChanged(); }
    @Override public void tick() {
        double before = air();
        if (!input()) {
            List<?> connected = (List<?>)call(api, handler, "getConnectedAirHandlers", new Class<?>[]{BlockEntity.class}, hatch);
            call(api, handler, "setSideLeaking", new Class<?>[]{Direction.class}, connected.isEmpty() ? Direction.UP : null);
        }
        call(api, handler, "tick", new Class<?>[]{BlockEntity.class}, hatch);
        if (before != air()) hatch.changed();
    }
    @Override public void clientTick() { call(api, handler, "tick", new Class<?>[]{BlockEntity.class}, hatch); }
    private CompoundTag data() { return ((CompoundTag)call(handler, "serializeNBT", new Class<?>[0])).copy(); }
    private void restore(CompoundTag tag) { call(handler, "deserializeNBT", new Class<?>[]{CompoundTag.class}, tag.copy()); }
    @Override public void save(CompoundTag tag) {
        tag.put("pneumatic", data());
        tag.putInt("pneumaticVolume", ((Number)call(api, handler, "getBaseVolume", new Class<?>[0])).intValue());
    }
    @Override public void read(CompoundTag tag) {
        if (tag.contains("pneumatic")) restore(tag.getCompound("pneumatic"));
        // Server configuration is authoritative; clients must use the server's synchronized volume.
        int base = MagicConfig.airVolume();
        if (hatch.getLevel() != null && hatch.getLevel().isClientSide && tag.contains("pneumaticVolume"))
            base = Math.max(1, Math.min(1000000, tag.getInt("pneumaticVolume")));
        call(api, handler, "setBaseVolume", new Class<?>[]{int.class}, base);
    }
    @Override public void invalidate() { valid = false; view.invalidate(); }
    @Override public void revive() { valid = true; view = LazyOptional.of(() -> handler); neighborChanged(); }
    private record State(CompoundTag data, double volume) {}
    public Access access(String variant) {
        if (!variant.isEmpty()) return null;
        return new Access() {
            public Object identity() { return handler; }
            public double amount() { return air(); }
            public double volume() { return PneumaticBackend.this.volume(); }
            public double minimum() { return -volume(); }
            public double maximum() { return Math.min(Integer.MAX_VALUE, Math.floor(volume()*dangerPressure())); }
            public boolean valid() { return valid && !hatch.isRemoved() && hatch.getLevel() != null && !hatch.getLevel().isClientSide; }
            public double move(double delta, boolean simulate) {
                if (!valid() || !Double.isFinite(delta) || delta != Math.rint(delta)) return 0;
                double allowed = delta >= 0 ? Math.min(delta, Math.max(0, maximum()-amount())) : -Math.min(-delta, Math.max(0, amount()-minimum()));
                if (allowed == 0) return 0;
                if (!simulate) {
                    double before = air();
                    call(api, handler, "addAir", new Class<?>[]{int.class}, (int)allowed);
                    hatch.changed(); return air()-before;
                }
                return allowed;
            }
            public Object snapshot() { return new State(data(), volume()); }
            public boolean unchanged(Object snapshot) { State before = (State)snapshot; return valid() && volume() == before.volume() && data().equals(before.data()); }
            public void restore(Object snapshot) { PneumaticBackend.this.restore(((State)snapshot).data()); hatch.changed(); }
        };
    }
}
