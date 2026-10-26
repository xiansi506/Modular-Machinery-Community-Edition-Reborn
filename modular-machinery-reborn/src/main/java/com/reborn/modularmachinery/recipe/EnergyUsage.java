package com.reborn.modularmachinery.recipe;

/** Nominal FE/t of held recipes, not a meter of the energy actually transferred on the previous tick. */
public record EnergyUsage(long input, long output) {
    public static final EnergyUsage ZERO = new EnergyUsage(0L, 0L);

    /** Reads settlement's rate calculation without changing any requirement's execution state. */
    public static EnergyUsage of(MachineRecipe recipe, RecipeModifiers modifiers, int parallelism) {
        long input = 0L, output = 0L;
        float durationMultiplier = modifiers.durationMultiplier(recipe.recipeTime());
        for (MachineRequirement requirement : recipe.requirements()) {
            if (requirement instanceof EnergyRequirement energy) {
                long rate = energy.perTickDraw(modifiers, parallelism, durationMultiplier);
                if (energy.ioType() == IOType.INPUT) input = add(input, rate);
                else output = add(output, rate);
            }
        }
        return new EnergyUsage(input, output);
    }

    public EnergyUsage plus(EnergyUsage other) {
        return new EnergyUsage(add(input, other.input), add(output, other.output));
    }

    public long[] toArray() { return new long[]{input, output}; }

    private static long add(long a, long b) {
        return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
    }
}
