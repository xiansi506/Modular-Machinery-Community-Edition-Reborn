package com.reborn.modularmachinery.recipe;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Keep long energy exact; modifiers retain their existing float aggregation semantics. */
final class EnergyMath {
    private static final BigDecimal MAX = BigDecimal.valueOf(Long.MAX_VALUE);
    private EnergyMath() {}

    static long modified(long value, RecipeModifiers modifiers, IOType io) {
        float add = 0, multiply = 1;
        boolean touched = false;
        for (RecipeModifier modifier : modifiers.modifiers()) {
            if (!modifier.matches(RecipeModifier.Target.ENERGY, io, false)) continue;
            if (modifier.operation() == RecipeModifier.Operation.ADD) add += modifier.value();
            else multiply *= modifier.value();
            touched = true;
        }
        if (!touched || add == 0 && multiply == 1) return value;
        if (!Float.isFinite(add) || !Float.isFinite(multiply)) {
            // Retain the established handling of non-finite modifier aggregates.
            return (long) Math.max(0, Math.floor(((double) value + add) * multiply));
        }
        return floor(BigDecimal.valueOf(value).add(exact(add)).multiply(exact(multiply)), false);
    }

    static long draw(long perCopy, int copies, float durationMultiplier) {
        if (perCopy <= 0) return 0;
        copies = Math.max(1, copies);
        float factor = durationMultiplier > 0 ? durationMultiplier : 1;
        if (factor == 1) return perCopy > Long.MAX_VALUE / copies ? Long.MAX_VALUE : perCopy * copies;
        if (Float.isInfinite(factor)) return Long.MAX_VALUE;
        // Do not saturate the intermediate product: a fractional duration factor may bring it back in range.
        return floor(BigDecimal.valueOf(perCopy).multiply(BigDecimal.valueOf(copies)).multiply(exact(factor)), true);
    }

    private static BigDecimal exact(float value) {
        return new BigDecimal((double) value);
    }

    private static long floor(BigDecimal value, boolean atLeastOne) {
        if (value.signum() <= 0) return 0;
        if (value.compareTo(MAX) >= 0) return Long.MAX_VALUE;
        long result = value.setScale(0, RoundingMode.FLOOR).longValueExact();
        return atLeastOne ? Math.max(1, result) : result;
    }
}
