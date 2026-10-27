package com.reborn.modularmachinery.recipe;

/** Integer execution bounds and trigger positions share the final, rounded recipe duration. */
public final class RecipeTiming {
    private RecipeTiming() {}

    public static int duration(double ticks) {
        if (Double.isNaN(ticks) || ticks >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return ticks <= 1 ? 1 : (int) Math.round(ticks);
    }

    /** Progress is zero based. Even a one-tick craft must execute its delayed requirements. */
    public static int triggerTick(int baseDuration, int trigger, int finalDuration) {
        double scaled = (double) trigger * finalDuration / baseDuration;
        return (int) Math.min(finalDuration - 1L, Math.max(0L, Math.round(scaled)));
    }
}
