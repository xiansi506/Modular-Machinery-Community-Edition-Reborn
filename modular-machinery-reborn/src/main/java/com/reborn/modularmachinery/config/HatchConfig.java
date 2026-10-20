package com.reborn.modularmachinery.config;

import com.reborn.modularmachinery.block.HatchTier;
import net.minecraftforge.common.ForgeConfigSpec;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A4: the three hatch numbers the original exposed to its config file.
 *
 * <p>The original read them in {@code FluidHatchSize#loadFromConfig} ({@code :43-47}) and
 * {@code EnergyHatchData#loadFromConfig} ({@code :67-85}):
 *
 * <ul>
 *   <li>{@code fluidhatch.size.<TIER>} — tank size in mB, range {@code 1 .. Integer.MAX_VALUE};</li>
 *   <li>{@code energyhatch.size.<TIER>} — stored FE, range {@code 1 .. Long.MAX_VALUE};</li>
 *   <li>{@code energyhatch.limit.<TIER>} — FE per tick, range {@code 1 .. Long.MAX_VALUE - 1}.</li>
 * </ul>
 *
 * <p>The category and key spelling are the original's: category {@code fluidhatch.size} /
 * {@code energyhatch.size} / {@code energyhatch.limit}, key = the enum constant in upper case. They are kept
 * verbatim for the same reason the other key paths are (see {@link ModConfig}'s class comment): a pack author
 * who knows the 1.12.2 file should recognise them.
 *
 * <p><b>The energy range is narrower than the original's, deliberately.</b> The original stored a {@code long}
 * and accepted up to {@code Long.MAX_VALUE}; this port stores FE in Forge's {@code EnergyStorage}, whose
 * capacity and transfer rate are {@code int}s (the original's own {@code TileEnergyHatch} had the same
 * constraint on the transfer side, but not on storage). Rather than accept a larger number and silently clamp
 * it in {@code MachineHatchBlockEntity}, the key's own range stops at {@code Integer.MAX_VALUE}; Forge reports
 * an out-of-range value in the config file instead of this mod quietly doing something else. Lifting the limit
 * is the separate item F7 (a long-backed storage), not something this key should pretend to support.
 *
 * <p>Item bus sizes are <b>not</b> configurable because the original did not make them so either:
 * {@code ItemBusSize} carried a {@code defaultConfigSize} argument and a {@code //TODO configurable} comment
 * ({@code ItemBusSize.java:32-36}), but no config reader. This port keeps that line.
 *
 * <p>Gas shares the fluid hatch's tank, exactly as the original's {@code HybridGasTank} shared
 * {@code FluidHatchSize#getSize()}; there is consequently no separate gas key.
 */
public final class HatchConfig {

    /** The original's category for the fluid tank size ({@code FluidHatchSize.java:45}). */
    private static final String FLUID_SIZE_ROOT = "fluidhatch.size";
    /** The original's category for the energy hatch's stored FE ({@code EnergyHatchData.java:69}). */
    private static final String ENERGY_SIZE_ROOT = "energyhatch.size";
    /** The original's category for the energy hatch's FE per tick ({@code EnergyHatchData.java:71}). */
    private static final String ENERGY_LIMIT_ROOT = "energyhatch.limit";

    private static final Map<HatchTier, ForgeConfigSpec.IntValue> FLUID_CAPACITY =
            new EnumMap<>(HatchTier.class);
    private static final Map<HatchTier, ForgeConfigSpec.IntValue> ENERGY_CAPACITY =
            new EnumMap<>(HatchTier.class);
    private static final Map<HatchTier, ForgeConfigSpec.IntValue> ENERGY_TRANSFER =
            new EnumMap<>(HatchTier.class);

    private HatchConfig() {
    }

    /**
     * Defines the hatch keys inside {@link ModConfig}'s one builder.
     *
     * <p>Every key uses an explicit path list. {@code ForgeConfigSpec.Builder} accumulates paths across
     * {@code pop()}, which is how 0.20.0 produced {@code parallel-controller.parallel-controller.normal...};
     * a list leaves nothing to inherit (the same reasoning as {@link ModConfig}'s class comment).
     */
    public static void define(ForgeConfigSpec.Builder builder) {
        builder.comment("Defines the tank size for each fluid hatch tier (the original's fluidhatch.size).",
                "Range 1 .. 2147483647 mB. A value above 2147483647 is rejected by this range: the tank itself is",
                "an int-backed Forge FluidTank, so accepting a larger number would only mean clamping it silently.",
                "Gas hatches share this tank, as in the original; there is no separate gas key.");
        for (HatchTier tier : HatchTier.FLUID_LADDER) {
            FLUID_CAPACITY.put(tier, builder.defineInRange(
                    List.of(FLUID_SIZE_ROOT, tier.name()), tier.fluidCapacity(), 1, Integer.MAX_VALUE));
        }

        builder.comment("Defines the stored FE of each energy hatch tier (the original's energyhatch.size).",
                "Range 1 .. 2147483647. The original accepted up to 9223372036854775807, but its 1.20.1",
                "counterpart, Forge EnergyStorage, stores an int; values above 2147483647 are rejected here",
                "rather than clamped silently. Lifting this is item F7, not a hidden feature of this key.");
        for (HatchTier tier : HatchTier.ENERGY_LADDER) {
            ENERGY_CAPACITY.put(tier, builder.defineInRange(
                    List.of(ENERGY_SIZE_ROOT, tier.name()), (int) tier.energyCapacity(), 1, Integer.MAX_VALUE));
        }

        builder.comment("Defines the FE per tick of each energy hatch tier (the original's energyhatch.limit).",
                "Range 1 .. 2147483647, for the same int-backed reason as energyhatch.size. The original used",
                "the same number for accepting and emitting; this port does too (see MachineHatchBlockEntity).");
        for (HatchTier tier : HatchTier.ENERGY_LADDER) {
            ENERGY_TRANSFER.put(tier, builder.defineInRange(
                    List.of(ENERGY_LIMIT_ROOT, tier.name()), (int) tier.energyTransfer(), 1, Integer.MAX_VALUE));
        }
    }

    /** Tank size in mB for a fluid hatch of {@code tier}; falls back to the tier's compiled default pre-config. */
    public static int fluidHatchCapacity(HatchTier tier) {
        return read(FLUID_CAPACITY.get(tier), tier.fluidCapacity());
    }

    /** Stored FE for an energy hatch of {@code tier}; falls back to the tier's compiled default pre-config. */
    public static int energyHatchCapacity(HatchTier tier) {
        return read(ENERGY_CAPACITY.get(tier), (int) tier.energyCapacity());
    }

    /** FE per tick for an energy hatch of {@code tier}; falls back to the tier's compiled default pre-config. */
    public static int energyHatchTransfer(HatchTier tier) {
        return read(ENERGY_TRANSFER.get(tier), (int) tier.energyTransfer());
    }

    /**
     * One number from the file, or the tier's own default while the file has not been read.
     *
     * <p>The fallback matters because a block entity can be constructed before {@code CONFIG_LOAD} on a
     * dedicated server's first tick of world loading in some mod-loading orders, and a zero capacity there
     * would make the hatch silently useless. It is the same guard the parallel/upgrade accessors use.
     */
    private static int read(@Nullable ForgeConfigSpec.IntValue value, int fallback) {
        if (value == null || !ModConfig.SPEC.isLoaded()) {
            return fallback;
        }
        return value.get();
    }
}
