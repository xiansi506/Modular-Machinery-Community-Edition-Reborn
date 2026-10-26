package com.reborn.modularmachinery.magic;

import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.*;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import java.util.*;

public final class MagicRequirement extends MachineRequirement {
    public final MagicFamily family;
    public final double amount, minimum, maximum;
    public final String variant;
    private float durationMultiplier = 1;
    public void setDurationMultiplier(float value) { durationMultiplier = Float.isFinite(value) && value >= 0 ? value : 1; }
    public MagicRequirement(IOType io, MagicFamily family, double amount, String variant,
                            double minimum, double maximum, boolean perTick) {
        super(io, perTick);
        if (!Double.isFinite(amount) || amount < 0 || !Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum)
            throw new IllegalArgumentException("Invalid magic amount or bounds");
        if (family != MagicFamily.WILL && (amount != Math.floor(amount) || amount > Integer.MAX_VALUE))
            throw new IllegalArgumentException("This magic resource uses integer units in 0..2147483647");
        if (family != MagicFamily.WILL && (minimum != Math.floor(minimum) || maximum != Math.floor(maximum)
                || minimum < Integer.MIN_VALUE || maximum > Integer.MAX_VALUE)) throw new IllegalArgumentException("Integer magic bounds must fit a signed int");
        this.family = family; this.amount = amount; this.variant = variant;
        if ((family == MagicFamily.MANA || family == MagicFamily.LIFEESSENCE) && !variant.isEmpty()) throw new IllegalArgumentException("This magic resource has no variant");
        if (family == MagicFamily.WILL && !List.of("default","corrosive","destructive","vengeful","steadfast").contains(variant)) throw new IllegalArgumentException("Invalid will-type");
        if (family == MagicFamily.AURA && (variant.isEmpty() || net.minecraft.resources.ResourceLocation.tryParse(variant) == null)) throw new IllegalArgumentException("Invalid aura-type");
        this.minimum = minimum; this.maximum = maximum;
    }
    @Override public RequirementType type() { return MagicRequirements.type(family); }
    @Override public RecipeModifier.Target modifierTarget() { return RecipeModifier.Target.valueOf(family.name()); }
    @Override public TankKind tankKind() { return TankKind.MAGIC; }
    @Override public int tankTint() { return family.color; }
    private double total(RecipeModifiers modifiers, int count) {
        double value = Math.max(0, modifiers.apply(this, amount, false));
        if (perTick()) value *= durationMultiplier;
        if (family != MagicFamily.WILL) value = Math.floor(value);
        return value * count;
    }
    private boolean transfer(HatchCollection ports, RecipeModifiers modifiers, int count, boolean simulate) {
        double remaining = total(modifiers, count);
        if (!Double.isFinite(remaining)) return false;
        double tolerance = Math.min(remaining*1e-12, Math.ulp(remaining)*8);
        if (remaining == 0 && amount > 0) return true;
        Set<Object> seen = new HashSet<>();
        boolean found = false;
        for (MagicPort port : ports.magicPorts()) {
            if (port.family() != family || port.input() != (ioType() == IOType.INPUT)) continue;
            MagicPort.Access access = port.access(variant);
            if (access == null || !access.valid() || !seen.add(access.identity())) continue;
            double value = access.amount();
            if (!Double.isFinite(value)) continue;
            double available = ioType() == IOType.INPUT ? value - Math.max(minimum, access.minimum())
                    : Math.min(maximum, access.maximum()) - value;
            if (family != MagicFamily.WILL) available = Math.min(available, Integer.MAX_VALUE);
            if (available < 0) continue;
            found = true;
            double take = Math.min(remaining, available);
            if (!simulate && take > 0) {
                double moved = access.move(ioType() == IOType.INPUT ? -take : take, false);
                if (!MagicPort.same(Math.abs(moved), take)) return false;
            }
            remaining -= take;
            if (remaining <= tolerance) return true;
        }
        return found && remaining <= tolerance;
    }
    @Override public boolean canSatisfy(HatchCollection ports) { return canSatisfy(ports, RecipeModifiers.EMPTY); }
    @Override public boolean canSatisfy(HatchCollection ports, RecipeModifiers modifiers) { return transfer(ports, modifiers, parallelism(), true); }
    @Override public boolean reserve(HatchCollection ports, RecipeModifiers modifiers) { return settle(ports, modifiers); }
    private boolean settle(HatchCollection ports, RecipeModifiers modifiers) {
        if (ports.isTransactionView()) return transfer(ports, modifiers, parallelism(), false);
        ResourceTransaction tx = new ResourceTransaction();
        return transfer(ports.transactionView(tx), modifiers, parallelism(), false) && tx.commit();
    }
    @Override public boolean apply(HatchCollection ports, RandomSource random) { return settle(ports, RecipeModifiers.EMPTY); }
    @Override public boolean tick(HatchCollection ports, RandomSource random) { return settle(ports, RecipeModifiers.EMPTY); }
    @Override public boolean apply(HatchCollection ports, RandomSource random, RecipeModifiers modifiers) { return settle(ports, modifiers); }
    @Override public boolean tick(HatchCollection ports, RandomSource random, RecipeModifiers modifiers) { return settle(ports, modifiers); }
    @Override public int parallelLimit(HatchCollection ports, RecipeModifiers modifiers, int ceiling) {
        if (!isParallelizable()) return transfer(ports, modifiers, 1, true) ? ceiling : 0;
        int low = 0, high = Math.max(0, ceiling);
        while (low < high) { int mid = low + (int)(((long)high - low + 1) / 2); if (transfer(ports, modifiers, mid, true)) low = mid; else high = mid - 1; }
        return low;
    }
    @Override public String startFailure(HatchCollection ports) {
        return ioType() == IOType.INPUT ? "craftcheck.failure.magic.input" : "craftcheck.failure.magic.output";
    }
    @Override public String describe() {
        String name = Component.translatable("magic.modular_machinery_reborn." + family.path()).getString();
        String value = amount == Math.floor(amount) ? String.format(Locale.ROOT, "%.0f", amount) : Double.toString(amount);
        return value + " " + name + (variant.isEmpty() ? "" : " (" + variant + ")") + (perTick() ? "/t" : "");
    }
}
