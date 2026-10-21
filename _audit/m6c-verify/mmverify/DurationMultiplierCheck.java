package mmverify;

import com.reborn.modularmachinery.config.ModConfig;
import com.reborn.modularmachinery.recipe.EnergyRequirement;
import com.reborn.modularmachinery.recipe.IOType;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RecipeModifiers;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Section B11 — does a {@code duration} modifier still change per-tick energy when the config says it should not?
 *
 * <p>The original made this a switch: {@code Config.enableDurationMultiplier} defaults to {@code false}
 * ({@code Config.java:92}, despite the field initialiser at {@code :44} saying {@code true}), and
 * {@code RecipeCraftingContext#getDurationMultiplier} returns {@code 1f} while it is off
 * ({@code :196-199}). This port applied the compensation unconditionally and its comment claimed the
 * original default was {@code true}. The section is written before the fix and reaches every new member
 * reflectively, so it compiles against 0.76.0 and reports FAIL instead of stopping at javac.
 */
final class DurationMultiplierCheck {

    private static final ResourceLocation RECIPE_ID =
            new ResourceLocation("mmverify", "duration_multiplier_demo");

    private DurationMultiplierCheck() {
    }

    static void durationMultiplierConfig() {
        section("B11. duration modifiers and per-tick energy follow the original's default-off switch");

        ForgeConfigSpec.ConfigValue<?> value = durationValue();
        report("ModConfig declares ENABLE_DURATION_MULTIPLIER_VALUE", value != null);
        if (value != null) {
            report("...at general.enable-duration-multiplier",
                    List.of("general", "enable-duration-multiplier").equals(value.getPath()));
            report("...with the original's default false", Boolean.FALSE.equals(value.getDefault()));
        }

        Method accessor = noArgAccessor();
        report("ModConfig has a public static enableDurationMultiplier()", accessor != null);
        if (accessor != null) {
            report("...which is false while the config file has not been read",
                    Boolean.FALSE.equals(invokeStatic(accessor)));
        }

        RecipeModifiers half = durationModifiers(0.5F);
        // 0.76.0 returns 2.0 here even though the config is not loaded, because the config was never consulted.
        report("the config-aware durationMultiplier() returns 1.0 by default (config off)",
                close(half.durationMultiplier(100), 1.0F));

        Method twoArg = twoArgDurationMultiplier();
        report("RecipeModifiers has durationMultiplier(int, boolean)", twoArg != null);
        if (twoArg != null) {
            report("...disabled: a x0.5 duration modifier leaves the per-tick multiplier at 1.0",
                    close(asFloat(invoke(twoArg, half, 100, false)), 1.0F));
            report("...enabled: the same modifier yields 2.0", close(asFloat(invoke(twoArg, half, 100, true)), 2.0F));
            RecipeModifiers slow = durationModifiers(2.0F);
            report("...disabled: a x2 duration modifier also leaves it at 1.0",
                    close(asFloat(invoke(twoArg, slow, 100, false)), 1.0F));
            report("...enabled: a x2 duration modifier yields 0.5",
                    close(asFloat(invoke(twoArg, slow, 100, true)), 0.5F));
        }

        MachineRecipe recipe = recipe();
        EnergyRequirement energy = energy(recipe);
        recipe.applyDurationMultiplier(half);
        report("applyDurationMultiplier(modifiers) leaves energy at 1.0 while the config is off",
                close(energy.durationMultiplier(), 1.0F));
        check("the craft duration still halves: the switch is about energy, not about duration",
                50L, recipe.duration(half));

        Method applyTwoArg = applyTwoArg();
        report("MachineRecipe has applyDurationMultiplier(RecipeModifiers, boolean)", applyTwoArg != null);
        if (applyTwoArg != null) {
            invoke(applyTwoArg, recipe, half, false);
            report("...false leaves the energy requirement at 1.0",
                    close(energy(recipe).durationMultiplier(), 1.0F));
            invoke(applyTwoArg, recipe, half, true);
            report("...true restores the old compensation (2.0)",
                    close(energy(recipe).durationMultiplier(), 2.0F));
        }
    }

    private static ForgeConfigSpec.ConfigValue<?> durationValue() {
        try {
            Field field = ModConfig.class.getDeclaredField("ENABLE_DURATION_MULTIPLIER_VALUE");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof ForgeConfigSpec.ConfigValue<?> configValue ? configValue : null;
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static Method noArgAccessor() {
        try {
            return ModConfig.class.getMethod("enableDurationMultiplier");
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static Method twoArgDurationMultiplier() {
        try {
            return RecipeModifiers.class.getMethod("durationMultiplier", int.class, boolean.class);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static Method applyTwoArg() {
        try {
            return MachineRecipe.class.getMethod("applyDurationMultiplier", RecipeModifiers.class, boolean.class);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static RecipeModifiers durationModifiers(float value) {
        return RecipeModifiers.of(List.of(new RecipeModifier(RecipeModifier.Target.DURATION, null, value,
                RecipeModifier.Operation.MULTIPLY, false)));
    }

    private static MachineRecipe recipe() {
        return new MachineRecipe(RECIPE_ID, "demo", "duration_multiplier_demo", 100,
                List.of(new EnergyRequirement(IOType.INPUT, 100L)));
    }

    private static EnergyRequirement energy(MachineRecipe recipe) {
        for (var requirement : recipe.requirements()) {
            if (requirement instanceof EnergyRequirement energy) {
                return energy;
            }
        }
        throw new IllegalStateException("the test recipe has no energy requirement");
    }

    private static Object invokeStatic(Method method) {
        try {
            return method.invoke(null);
        } catch (InvocationTargetException wrapped) {
            return wrapped.getCause() == null ? wrapped : wrapped.getCause();
        } catch (ReflectiveOperationException failure) {
            return failure;
        }
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            return wrapped.getCause() == null ? wrapped : wrapped.getCause();
        } catch (ReflectiveOperationException failure) {
            return failure;
        }
    }

    private static float asFloat(Object value) {
        return value instanceof Number number ? number.floatValue() : Float.NaN;
    }

    private static boolean close(float actual, float expected) {
        return Math.abs(actual - expected) < 1.0E-4F;
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }
}
