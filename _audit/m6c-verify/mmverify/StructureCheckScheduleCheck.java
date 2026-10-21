package mmverify;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * A10 — structure checks must be scheduleable instead of hard-wired to every 10 ticks.
 *
 * <p>Written before the production classes exist and driven through reflection, so the red run
 * compiles and reports FAIL instead of stopping at javac.
 */
final class StructureCheckScheduleCheck {

    private static final String SCHEDULER =
            "com.reborn.modularmachinery.machine.StructureCheckScheduler";
    private static final String TRACKER =
            "com.reborn.modularmachinery.machine.StructureChangeTracker";
    private static final String CONFIG =
            "com.reborn.modularmachinery.config.ModConfig";

    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation("mmverify", "a10"));

    private StructureCheckScheduleCheck() {
    }

    static void structureCheckSchedule() {
        section("A10. structure-check delay is configurable and follows the original formula");

        Class<?> scheduler = find(SCHEDULER);
        report("A10: StructureCheckScheduler exists", scheduler != null);
        Method factory = scheduler == null ? null : method(scheduler, "of",
                int.class, boolean.class, int.class);
        report("A10: StructureCheckScheduler.of(int, boolean, int) exists", factory != null);
        if (scheduler == null || factory == null) {
            configDefaults();
            tracker();
            return;
        }

        Class<?> settingsClass = factory.getReturnType();
        Method delay = method(settingsClass, "delay");
        Method delayed = method(settingsClass, "delayed");
        Method maxDelay = method(settingsClass, "maxDelay");
        Method shouldCheck = method(scheduler, "shouldCheck", long.class, int.class,
                boolean.class, boolean.class, int.class, settingsClass);
        report("A10: the settings record exposes delay()/delayed()/maxDelay()",
                delay != null && delayed != null && maxDelay != null);
        report("A10: StructureCheckScheduler.shouldCheck(...) exists", shouldCheck != null);
        if (delay == null || delayed == null || maxDelay == null || shouldCheck == null) {
            configDefaults();
            tracker();
            return;
        }

        Object defaults = call(factory, null, 30, true, 100);
        report("A10: default settings are 30 ticks / delayed / max 100", defaults != null
                && intValue(delay, defaults) == 30
                && Boolean.TRUE.equals(call(delayed, defaults))
                && intValue(maxDelay, defaults) == 100);

        Object invalid = call(factory, null, 200, true, 100);
        report("A10: delay >= max is replaced by the original's 30/100 fallback", invalid != null
                && intValue(delay, invalid) == 30
                && intValue(maxDelay, invalid) == 100);

        Object off = call(factory, null, 30, false, 100);
        report("A10: delayed=false checks every 30 ticks, not at tick 29",
                off != null && !boolValue(shouldCheck, 29L, 1, false, false, 0, off)
                        && boolValue(shouldCheck, 30L, 1, false, false, 0, off));
        report("A10: delayed=false ignores an area change (the original returned before that check)",
                off != null && !boolValue(shouldCheck, 29L, 1, true, true, 0, off));

        report("A10: a brand-new controller checks on its first tick", boolValue(
                shouldCheck, 1L, -1, false, false, 0, defaults));

        report("A10: unformed machine uses delay + 5 per failed check (30, then 35)",
                !boolValue(shouldCheck, 29L, 1, false, false, 0, defaults)
                        && boolValue(shouldCheck, 30L, 1, false, false, 0, defaults)
                        && !boolValue(shouldCheck, 34L, 1, false, false, 1, defaults)
                        && boolValue(shouldCheck, 35L, 1, false, false, 1, defaults));
        report("A10: the unformed interval stops at maxDelay (30 + 20*5 -> 100)",
                !boolValue(shouldCheck, 99L, 1, false, false, 20, defaults)
                        && boolValue(shouldCheck, 100L, 1, false, false, 20, defaults));

        report("A10: a formed machine starts at delay + the original's 20-tick recipe base (50)",
                !boolValue(shouldCheck, 49L, 1, true, false, 0, defaults)
                        && boolValue(shouldCheck, 50L, 1, true, false, 0, defaults));
        report("A10: formed interval grows by 5 per failed recipe search (30 + 20 + 4*5 = 70)",
                !boolValue(shouldCheck, 69L, 1, true, false, 4, defaults)
                        && boolValue(shouldCheck, 70L, 1, true, false, 4, defaults));
        report("A10: formed interval is capped at maxDelay",
                !boolValue(shouldCheck, 99L, 1, true, false, 20, defaults)
                        && boolValue(shouldCheck, 100L, 1, true, false, 20, defaults));
        report("A10: a formed machine checks immediately when its area changed",
                boolValue(shouldCheck, 49L, 1, true, true, 0, defaults));
        report("A10: an unformed machine does not use the area-change shortcut",
                !boolValue(shouldCheck, 31L, 1, false, true, 0, defaults));

        Object nearlyMax = call(factory, null, 90, true, 100);
        report("A10: a valid pair whose sum would exceed max is clamped (90 + 20 -> 100)",
                nearlyMax != null && boolValue(shouldCheck, 100L, 1, true, false, 0, nearlyMax));

        configDefaults();
        tracker();
    }

    private static void configDefaults() {
        Class<?> config = find(CONFIG);
        Method delay = config == null ? null : method(config, "structureCheckDelay");
        Method delayed = config == null ? null : method(config, "delayedStructureCheck");
        Method max = config == null ? null : method(config, "maxStructureCheckDelay");
        report("A10: ModConfig exposes structureCheckDelay()/delayedStructureCheck()/maxStructureCheckDelay()",
                delay != null && delayed != null && max != null);
        if (delay == null || delayed == null || max == null) {
            return;
        }
        report("A10: before the config file is loaded the three keys fall back to 30/true/100",
                intValue(delay, null) == 30 && Boolean.TRUE.equals(call(delayed, null))
                        && intValue(max, null) == 100);
    }

    private static void tracker() {
        Class<?> tracker = find(TRACKER);
        report("A10: StructureChangeTracker exists", tracker != null);
        Method mark = tracker == null ? null : method(tracker, "mark",
                ResourceKey.class, BlockPos.class);
        Method changed = tracker == null ? null : method(tracker, "isAreaChanged",
                ResourceKey.class, BlockPos.class, BlockPos.class);
        Method clear = tracker == null ? null : method(tracker, "clearAll");
        report("A10: the tracker exposes mark/isAreaChanged/clearAll",
                mark != null && changed != null && clear != null);
        if (mark == null || changed == null || clear == null) {
            return;
        }

        try {
            call(clear, null);
            call(mark, null, DIMENSION, new BlockPos(0, 64, 0));
            report("A10: a changed position inside the structure's bounds is noticed",
                    boolValue(changed, DIMENSION, new BlockPos(-1, 63, -1),
                            new BlockPos(1, 65, 1)));
            report("A10: a changed position outside the bounds is not noticed",
                    !boolValue(changed, DIMENSION, new BlockPos(2, 63, -1),
                            new BlockPos(3, 65, 1)));

            call(clear, null);
            call(mark, null, DIMENSION, new BlockPos(-4, 64, -5));
            report("A10: negative coordinates are compared inclusively (-5..-3 x, -6..-4 z)",
                    boolValue(changed, DIMENSION, new BlockPos(-5, 63, -6),
                            new BlockPos(-3, 65, -4)));
            report("A10: a position just outside a negative bound is not noticed",
                    !boolValue(changed, DIMENSION, new BlockPos(-2, 63, -6),
                            new BlockPos(-3, 65, -4)));

            call(clear, null);
            report("A10: clearAll() forgets every change", !boolValue(changed, DIMENSION,
                    new BlockPos(-5, 63, -6), new BlockPos(5, 65, 6)));
        } catch (Throwable failure) {
            report("A10: tracker operations did not throw: " + failure, false);
        } finally {
            call(clear, null);
        }
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }

    private static Class<?> find(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method found = owner.getMethod(name, parameters);
            found.setAccessible(true);
            return found;
        } catch (Throwable failure) {
            return null;
        }
    }

    private static Object call(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException(String.valueOf(failure.getCause()), failure.getCause());
        } catch (Throwable failure) {
            throw new IllegalStateException(String.valueOf(failure), failure);
        }
    }

    private static boolean boolValue(Method method, Object... arguments) {
        try {
            Object value = call(method, null, arguments);
            return Boolean.TRUE.equals(value);
        } catch (Throwable failure) {
            return false;
        }
    }

    private static int intValue(Method method, Object target, Object... arguments) {
        try {
            Object value = call(method, target, arguments);
            return value instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
        } catch (Throwable failure) {
            return Integer.MIN_VALUE;
        }
    }
}
