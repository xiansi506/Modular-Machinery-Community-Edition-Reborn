package com.reborn.modularmachinery.machine;

/**
 * The original's structure-check schedule ({@code TileMultiblockMachineController#canCheckStructure},
 * {@code :351-370}), lifted out of the block entity so the formula can be exercised without a world.
 *
 * <p>The original had three config keys and this class is the one place their interaction is expressed:
 * a base delay, the "delayed" switch, and a ceiling. A machine that has not formed backs off by five ticks per
 * failed check; a formed machine also adds the recipe-search retry backoff, whose zero-retry base is twenty
 * ticks. Both are clamped to the ceiling. The original computed the formed interval as
 * {@code min(structureCheckDelay + currentRecipeSearchDelay(), maxStructureCheckDelay)} where
 * {@code currentRecipeSearchDelay() = min(20 + retries * 5, 100)}; {@link #recipeSearchDelay(int)} keeps that
 * inner formula in one place.
 *
 * <p><b>What this class deliberately does not do.</b> It does not read config, does not know about block
 * entities and does not track world changes. {@link #shouldCheck} is a pure function of the six inputs, so a
 * caller can log the exact inputs when the behaviour looks wrong. The controller supplies the area-change
 * flag from {@link StructureChangeTracker}; the config accessors live in
 * {@code com.reborn.modularmachinery.config.ModConfig}.
 */
public final class StructureCheckScheduler {

    /** The original's own config defaults ({@code TileMultiblockMachineController.java:122-123}). */
    public static final int DEFAULT_DELAY = 30;
    public static final int DEFAULT_MAX_DELAY = 100;
    public static final boolean DEFAULT_DELAYED = true;

    /** The original's ceiling on {@code currentRecipeSearchDelay()} ({@code :372-374}). */
    public static final int MAX_RECIPE_SEARCH_DELAY = 100;
    /** The zero-retry value of {@code currentRecipeSearchDelay()} ({@code :372-374}). */
    public static final int BASE_RECIPE_SEARCH_DELAY = 20;

    private StructureCheckScheduler() {
    }

    /**
     * A normalized schedule. The constructor is private so every instance goes through {@link #of}, which
     * applies the original's fallback: when the base delay is not below the ceiling, the original logged
     * "structure-check-delay is bigger than or equal max-structure-check-delay!, use default value..." and
     * reset both numbers to 30 and 100 ({@code TileMultiblockMachineController.java:184-188}).
     */
    public record Settings(int delay, boolean delayed, int maxDelay) {

        public Settings {
            if (delay < 1) {
                delay = DEFAULT_DELAY;
            }
            if (maxDelay < 2) {
                maxDelay = DEFAULT_MAX_DELAY;
            }
            if (delay >= maxDelay) {
                // The original's warning belongs to the config reader; this is the same fallback it applied.
                delay = DEFAULT_DELAY;
                maxDelay = DEFAULT_MAX_DELAY;
            }
        }

        public static Settings of(int delay, boolean delayed, int maxDelay) {
            return new Settings(delay, delayed, maxDelay);
        }
    }

    /** The same normalization as {@link Settings#of}, named for callers that read a config file. */
    public static Settings of(int delay, boolean delayed, int maxDelay) {
        return Settings.of(delay, delayed, maxDelay);
    }

    /**
     * {@code currentRecipeSearchDelay()} from the original: the formed machine's extra wait grows by five ticks
     * for every failed recipe search, from a base of twenty, and never exceeds 100.
     */
    public static int recipeSearchDelay(int recipeSearchFailures) {
        int failures = Math.max(0, recipeSearchFailures);
        long value = BASE_RECIPE_SEARCH_DELAY + (long) failures * 5L;
        return (int) Math.min(value, MAX_RECIPE_SEARCH_DELAY);
    }

    /** The interval the original used for a machine that is not formed: {@code min(delay + failures*5, max)}. */
    public static int unformedInterval(int structureFailures, Settings settings) {
        int failures = Math.max(0, structureFailures);
        long value = (long) settings.delay() + (long) failures * 5L;
        return (int) Math.min(value, settings.maxDelay());
    }

    /** The interval the original used for a formed machine: {@code min(delay + currentRecipeSearchDelay(), max)}. */
    public static int formedInterval(int recipeSearchFailures, Settings settings) {
        long value = (long) settings.delay() + recipeSearchDelay(recipeSearchFailures);
        return (int) Math.min(value, settings.maxDelay());
    }

    /**
     * The original's {@code canCheckStructure()} as a pure predicate.
     *
     * <p>The order matters and is copied from the original: a controller that has never checked checks at
     * once; the {@code delayed=false} mode is a plain modulo on the base delay and <b>ignores area changes
     * entirely</b> (the original returned there, before the area test); a formed machine checks immediately
     * when its area changed, otherwise on the formed interval; an unformed machine never uses the area shortcut.
     *
     * @param ticksExisted         the controller's age in ticks (the original's {@code ticksExisted})
     * @param lastStructureCheckTick the tick of the last check, or a negative value when there was none
     * @param formed               whether the controller currently has a structure
     * @param areaChanged          whether a block inside the last formed bounds changed since the last server tick
     * @param failures             failed recipe searches while formed, failed structure checks while unformed
     * @param settings             the normalized config values
     */
    public static boolean shouldCheck(long ticksExisted, int lastStructureCheckTick, boolean formed,
            boolean areaChanged, int failures, Settings settings) {
        if (lastStructureCheckTick < 0) {
            return true;
        }
        if (!settings.delayed()) {
            return ticksExisted % settings.delay() == 0;
        }
        if (formed) {
            if (areaChanged) {
                return true;
            }
            return ticksExisted % formedInterval(failures, settings) == 0;
        }
        return ticksExisted % unformedInterval(failures, settings) == 0;
    }
}
