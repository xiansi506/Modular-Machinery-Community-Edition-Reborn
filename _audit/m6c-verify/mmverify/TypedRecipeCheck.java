package mmverify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.recipe.EnergyRequirement;
import com.reborn.modularmachinery.recipe.FluidRequirement;
import com.reborn.modularmachinery.recipe.IOType;
import com.reborn.modularmachinery.recipe.ItemRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirementParser;
import com.reborn.modularmachinery.recipe.RequirementTypes;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Q6-B（0.65.0）：typed 配方 API。
 *
 * <p>两件事必须同时成立，而它们很容易互相破坏：
 *
 * <ul>
 *   <li>脚本写 {@code event.recipes.modular_machinery_reborn.machine('alloy_furnace').time(200)...}
 *       时，KubeJS 通过 schema 的构造器把 {@code machine} 键值交给 {@code MachineTargetComponent}，
 *       再由 {@code MachineRecipeJS#serialize} 用 plain builder 拼出与数据包同形的 JSON；</li>
 *   <li>脚本继续写 {@code machine({...})}（0.27.x 起就有、KGS-配方指南 里已确认可用）以及
 *       {@code event.custom(...)} 时，JSON 必须原样保留，不能被 schema 的 required key 拒掉。</li>
 * </ul>
 *
 * <p>本类不 import KubeJS：红跑要能在没有 typed API 的旧源码上编译，所以 builder 与
 * schema/JS 全部走反射；只有既有生产类（解析器、需求实现）直接引用。KubeJS 的 schema 与 JS
 * 通过 {@link KubeJSBindingCheck.ChildLoader} 真正加载，不是源码扫描。
 */
final class TypedRecipeCheck {

    private static final String BUILDER = "com.reborn.modularmachinery.recipe.MachineRecipeJsonBuilder";
    private static final String SCHEMA = "com.reborn.modularmachinery.kubejs.MachineRecipeSchema";
    private static final String JS = "com.reborn.modularmachinery.kubejs.MachineRecipeJS";
    private static final ResourceLocation RECIPE = new ResourceLocation("mmverify", "typed_recipe_check");

    private TypedRecipeCheck() {
    }

    static void typedRecipes() {
        section("Q6-B typed 配方 API（0.65.0）");
        run("the plain builder shapes", TypedRecipeCheck::plainBuilderShapes);
        run("the builder refusals", TypedRecipeCheck::builderRefusals);
        run("the real readers", TypedRecipeCheck::parsedByTheRealReaders);
        run("the KubeJS schema", TypedRecipeCheck::kubeJsSchema);
        run("the wiring", TypedRecipeCheck::wiring);
    }

    private static void run(String what, Runnable body) {
        try {
            body.run();
        } catch (Throwable broken) {
            report(what + " could not be checked: " + broken, false);
        }
    }

    // ------------------------------------------------------------------ 1. plain builder

    private static void plainBuilderShapes() {
        Object builder = builder();
        JsonObject minimal = (JsonObject) invoke(builder, "build", new Class<?>[] {String.class},
                "alloy_furnace");
        check("a fresh builder writes the machine id", "alloy_furnace",
                minimal.get("machine").getAsString());
        check("...defaults the recipe time to 100", 100, minimal.get("recipeTime").getAsInt());
        check("...omits max-parallelism so the machine ceiling applies", false,
                minimal.has("max-parallelism"));
        check("...and starts with no requirements", 0,
                minimal.getAsJsonArray("requirements").size());

        builder = builder();
        invoke(builder, "time", new Class<?>[] {int.class}, 240);
        invoke(builder, "parallelism", new Class<?>[] {int.class}, 6);
        invoke(builder, "registryName", new Class<?>[] {String.class}, "fast_alloy");
        JsonObject configured = (JsonObject) invoke(builder, "build", new Class<?>[] {String.class},
                "alloy_furnace");
        check("time(int) writes recipeTime", 240, configured.get("recipeTime").getAsInt());
        check("parallelism(int) writes max-parallelism", 6,
                configured.get("max-parallelism").getAsInt());
        check("registryName(String) writes registryName", "fast_alloy",
                configured.get("registryName").getAsString());

        builder = builder();
        invoke(builder, "itemInput", new Class<?>[] {String.class, int.class}, "minecraft:iron_ore", 2);
        invoke(builder, "itemOutput", new Class<?>[] {String.class, int.class}, "minecraft:iron_ingot", 1);
        invoke(builder, "itemOutput", new Class<?>[] {String.class, int.class, int.class, float.class},
                "minecraft:gold_nugget", 1, 3, 0.25F);
        invoke(builder, "fluidInput", new Class<?>[] {String.class, int.class}, "minecraft:water", 500);
        invoke(builder, "fluidInput", new Class<?>[] {String.class, int.class, boolean.class},
                "minecraft:lava", 10, true);
        invoke(builder, "fluidOutput", new Class<?>[] {String.class, int.class, boolean.class},
                "minecraft:water", 20, true);
        invoke(builder, "energyInput", new Class<?>[] {long.class}, 40L);
        invoke(builder, "energyOutput", new Class<?>[] {long.class}, 15L);
        JsonObject items = (JsonObject) invoke(builder, "build", new Class<?>[] {String.class},
                "alloy_furnace");
        JsonArray requirements = items.getAsJsonArray("requirements");
        check("eight requirement calls produce eight array entries", 8, requirements.size());
        check("...the first item input names the item type and io", "modular_machinery_reborn:item",
                requirements.get(0).getAsJsonObject().get("type").getAsString());
        check("...with io-type input", "input",
                requirements.get(0).getAsJsonObject().get("io-type").getAsString());
        check("...and the requested amount", 2,
                requirements.get(0).getAsJsonObject().get("amount").getAsInt());
        check("...the random output carries minAmount/maxAmount/chance", "1..3@0.25",
                requirements.get(2).getAsJsonObject().get("minAmount").getAsInt() + ".."
                        + requirements.get(2).getAsJsonObject().get("maxAmount").getAsInt() + "@"
                        + requirements.get(2).getAsJsonObject().get("chance").getAsFloat());
        check("...the per-tick fluid input writes perTick", true,
                requirements.get(4).getAsJsonObject().get("perTick").getAsBoolean());
        check("...the energy requirement writes energyPerTick", 40L,
                requirements.get(6).getAsJsonObject().get("energyPerTick").getAsLong());

        builder = builder();
        invoke(builder, "catalyst", new Class<?>[] {String.class, int.class}, "minecraft:diamond", 1);
        invoke(builder, "catalystEntry", new Class<?>[] {String.class, int.class}, "#forge:gems/emerald", 2);
        invoke(builder, "catalystChance", new Class<?>[] {float.class}, 0.5F);
        invoke(builder, "catalystModifier", new Class<?>[] {String.class, String.class, int.class,
                float.class}, "duration", "input", 1, 0.5F);
        invoke(builder, "catalystTooltip", new Class<?>[] {String.class}, "Speeds this recipe up");
        JsonObject catalyst = (JsonObject) invoke(builder, "build", new Class<?>[] {String.class},
                "alloy_furnace");
        JsonObject catalystJson = catalyst.getAsJsonArray("requirements").get(0).getAsJsonObject();
        check("a catalyst writes the catalyst type and input io", "modular_machinery_reborn:catalyst",
                catalystJson.get("type").getAsString());
        check("...keeps both entries in order", "minecraft:diamond,#forge:gems/emerald",
                catalystJson.getAsJsonArray("items").get(0).getAsJsonObject().get("item").getAsString()
                        + "," + catalystJson.getAsJsonArray("items").get(1).getAsJsonObject()
                        .get("item").getAsString());
        check("...applies a group chance", 0.5F, catalystJson.get("chance").getAsFloat());
        check("...writes the modifier exactly as the reader expects", "duration/input/1/0.5",
                catalystJson.getAsJsonArray("modifiers").get(0).getAsJsonObject().get("target").getAsString()
                        + "/" + catalystJson.getAsJsonArray("modifiers").get(0).getAsJsonObject()
                        .get("io").getAsString()
                        + "/" + catalystJson.getAsJsonArray("modifiers").get(0).getAsJsonObject()
                        .get("operation").getAsInt()
                        + "/" + catalystJson.getAsJsonArray("modifiers").get(0).getAsJsonObject()
                        .get("multiplier").getAsFloat());
        check("...and keeps the tooltip", "Speeds this recipe up",
                catalystJson.getAsJsonArray("tooltip").get(0).getAsString());
    }

    private static void builderRefusals() {
        expectRefusal("time(0)", "time", new Class<?>[] {int.class}, 0);
        expectRefusal("parallelism(0)", "parallelism", new Class<?>[] {int.class}, 0);
        expectRefusal("itemInput(..., 0)", "itemInput", new Class<?>[] {String.class, int.class},
                "minecraft:stone", 0);
        expectRefusal("itemOutput(..., 3, 2, 1.0)", "itemOutput",
                new Class<?>[] {String.class, int.class, int.class, float.class},
                "minecraft:stone", 3, 2, 1.0F);
        expectRefusal("catalyst(..., 1.5)", "catalyst",
                new Class<?>[] {String.class, int.class, float.class}, "minecraft:diamond", 1, 1.5F);
        expectRefusal("catalystEntry without catalyst", "catalystEntry",
                new Class<?>[] {String.class, int.class}, "minecraft:diamond", 1);
        expectRefusal("catalystModifier without catalyst", "catalystModifier",
                new Class<?>[] {String.class, String.class, int.class, float.class},
                "duration", "input", 1, 0.5F);
        expectRefusal("build with a blank machine id", "build", new Class<?>[] {String.class}, "");
    }

    private static void expectRefusal(String label, String method, Class<?>[] types, Object... args) {
        Object builder = builder();
        try {
            invoke(builder, method, types, args);
            report("builder refuses " + label + " instead of writing a recipe the readers will reject",
                    false);
        } catch (RuntimeException expected) {
            report("builder refuses " + label + " instead of writing a recipe the readers will reject",
                    true);
        }
    }

    // ------------------------------------------------------------------ 2. the real readers

    private static void parsedByTheRealReaders() {
        Object builder = builder();
        invoke(builder, "itemInput", new Class<?>[] {String.class, int.class}, "minecraft:iron_ore", 2);
        invoke(builder, "itemOutput", new Class<?>[] {String.class, int.class, int.class, float.class},
                "minecraft:gold_nugget", 1, 3, 0.25F);
        invoke(builder, "fluidInput", new Class<?>[] {String.class, int.class, boolean.class},
                "minecraft:water", 10, true);
        invoke(builder, "energyInput", new Class<?>[] {long.class}, 40L);
        invoke(builder, "catalyst", new Class<?>[] {String.class, int.class}, "minecraft:diamond", 1);
        invoke(builder, "catalystModifier", new Class<?>[] {String.class, String.class, int.class,
                float.class}, "duration", "input", 1, 0.5F);
        JsonObject body = (JsonObject) invoke(builder, "build", new Class<?>[] {String.class},
                "alloy_furnace");
        JsonArray requirements = body.getAsJsonArray("requirements");

        MachineRequirement itemInput = MachineRequirementParser.parse(
                requirements.get(0).getAsJsonObject(), RECIPE);
        check("the real parser accepts a builder item input as item/input", "item/input",
                itemInput.type().id().getPath() + "/"
                        + itemInput.ioType().name().toLowerCase(java.util.Locale.ROOT));
        check("...with the amount the builder wrote", 2, ((ItemRequirement) itemInput).amount());

        MachineRequirement randomOutput = MachineRequirementParser.parse(
                requirements.get(1).getAsJsonObject(), RECIPE);
        ItemRequirement output = (ItemRequirement) randomOutput;
        check("...and the random output range/chance survive", "true 1..3 0.25",
                output.hasAmountRange() + " " + output.minAmount() + ".." + output.maxAmount()
                        + " " + output.chance());

        MachineRequirement fluidInput = MachineRequirementParser.parse(
                requirements.get(2).getAsJsonObject(), RECIPE);
        check("the real parser accepts the per-tick fluid input", true, fluidInput.perTick());
        check("...with its amount", 10, ((FluidRequirement) fluidInput).amount());

        MachineRequirement energyInput = MachineRequirementParser.parse(
                requirements.get(3).getAsJsonObject(), RECIPE);
        check("the real parser accepts the energy input", 40L,
                ((EnergyRequirement) energyInput).energyPerTick());

        MachineRequirement catalyst = MachineRequirementParser.parse(
                requirements.get(4).getAsJsonObject(), RECIPE);
        check("the real parser accepts the catalyst", "catalyst",
                catalyst.type().id().getPath());
        check("...and folds its modifier into the description", true,
                catalyst.describe().contains("+1 modifiers"));

        check("RequirementTypes still exposes the catalyst id", "catalyst",
                RequirementTypes.byName("modular_machinery_reborn:catalyst").id().getPath());
    }

    // ------------------------------------------------------------------ 3. KubeJS schema

    private static void kubeJsSchema() {
        Path kubeJs = KubeJSBindingCheck.kubeJsDeobfJar();
        Path rhino = KubeJSBindingCheck.rhinoDeobfJar();
        report("the deobfuscated KubeJS jar is available for the schema check: " + kubeJs, kubeJs != null);
        if (kubeJs == null || rhino == null) {
            return;
        }
        Path stub = KubeJSBindingCheck.platformStub(kubeJs);
        report("the Architectury platform stand-in could be compiled: " + stub, stub != null);
        if (stub == null) {
            return;
        }
        try (KubeJSBindingCheck.ChildLoader loader = new KubeJSBindingCheck.ChildLoader(new java.net.URL[] {
                stub.toUri().toURL(), kubeJs.toUri().toURL(), rhino.toUri().toURL()})) {
            Class<?> schemaClass = Class.forName(SCHEMA, true, loader);
            Class<?> schemaBase = Class.forName("dev.latvian.mods.kubejs.recipe.schema.RecipeSchema",
                    true, loader);
            Class<?> jsClass = Class.forName(JS, true, loader);
            Object schema = schemaClass.getField("SCHEMA").get(null);
            Object keys = schemaBase.getField("keys").get(schema);
            check("the schema declares exactly one key", 1, java.lang.reflect.Array.getLength(keys));
            Object key = java.lang.reflect.Array.get(keys, 0);
            check("...named machine", "machine",
                    key.getClass().getField("name").get(key));
            check("...and its RecipeJS is MachineRecipeJS", true,
                    schemaBase.getField("recipeType").get(schema) == jsClass);

            Class<?> namespaceClass = Class.forName("dev.latvian.mods.kubejs.recipe.schema.RecipeNamespace",
                    true, loader);
            Object namespace = namespaceClass.getConstructor(String.class)
                    .newInstance("modular_machinery_reborn");
            namespaceClass.getMethod("register", String.class, schemaBase)
                    .invoke(namespace, "machine", schema);
            Object schemaType = namespaceClass.getMethod("get", Object.class)
                    .invoke(namespace, "machine");
            check("registering the schema creates a RecipeSchemaType", true, schemaType != null);

            Class<?> valueMapClass = Class.forName("dev.latvian.mods.kubejs.recipe.component.ComponentValueMap",
                    true, loader);
            Object valueMap = valueMapClass.getConstructor(int.class).newInstance(1);
            Map<Object, Object> values = castMap(valueMap);
            values.put(key, "alloy_furnace");

            Object constructors = schemaBase.getMethod("constructors").invoke(schema);
            Object constructor = constructors.getClass().getMethod("get", int.class)
                    .invoke(constructors, 1);
            check("the one-argument call has a constructor", true, constructor != null);
            Object factory = constructor.getClass().getMethod("factory").invoke(constructor);
            Object constructorKeys = constructor.getClass().getMethod("keys").invoke(constructor);
            Class<?> typeFunctionClass = Class.forName("dev.latvian.mods.kubejs.recipe.RecipeTypeFunction",
                    true, loader);
            Class<?> schemaTypeClass = Class.forName("dev.latvian.mods.kubejs.recipe.schema.RecipeSchemaType",
                    true, loader);
            Class<?> recipesEventClass = Class.forName("dev.latvian.mods.kubejs.recipe.RecipesEventJS",
                    true, loader);
            Object typeFunction = typeFunctionClass
                    .getConstructor(recipesEventClass, schemaTypeClass)
                    .newInstance(null, schemaType);
            Object js = factory.getClass().getMethod("create", typeFunctionClass, schemaTypeClass,
                            constructorKeys.getClass(), valueMapClass)
                    .invoke(factory, typeFunction, schemaType, constructorKeys, valueMap);

            invoke(js, "time", new Class<?>[] {int.class}, 200);
            invoke(js, "itemInput", new Class<?>[] {String.class, int.class}, "minecraft:iron_ore", 2);
            invoke(js, "itemOutput", new Class<?>[] {String.class, int.class}, "minecraft:iron_ingot", 1);
            invoke(js, "energyInput", new Class<?>[] {long.class}, 40L);
            invoke(js, "catalyst", new Class<?>[] {String.class, int.class}, "minecraft:diamond", 1);
            invoke(js, "catalystModifier", new Class<?>[] {String.class, String.class, int.class,
                    float.class}, "duration", "input", 1, 0.5F);
            invoke(js, "serialize", new Class<?>[0]);
            JsonObject typed = (JsonObject) jsClass.getField("json").get(js);
            check("a typed script call serializes the machine id it was given", "alloy_furnace",
                    typed.get("machine").getAsString());
            check("...with the typed time", 200, typed.get("recipeTime").getAsInt());
            check("...and four requirements", 4, typed.getAsJsonArray("requirements").size());
            check("...including the catalyst", "modular_machinery_reborn:catalyst",
                    typed.getAsJsonArray("requirements").get(3).getAsJsonObject().get("type").getAsString());
            check("...and the correct recipe type", "modular_machinery_reborn:machine",
                    typed.get("type").getAsString());

            JsonObject raw = JsonParser.parseString("""
                    {"machine":"alloy_furnace","recipeTime":80,"requirements":[
                      {"type":"modular_machinery_reborn:item","io-type":"input",
                       "item":"minecraft:stone","amount":1}
                    ]}
                    """).getAsJsonObject();
            Class<?> locationClass = Class.forName("net.minecraft.resources.ResourceLocation", true, loader);
            Object rawRecipe = schemaClass.getMethod("deserialize", typeFunctionClass, locationClass,
                            JsonObject.class)
                    .invoke(schema, typeFunction, null, raw);
            invoke(rawRecipe, "serialize", new Class<?>[0]);
            JsonObject rawOut = (JsonObject) jsClass.getField("json").get(rawRecipe);
            check("a raw machine({...}) call keeps its requirements", 1,
                    rawOut.getAsJsonArray("requirements").size());
            check("...keeps its recipe time", 80, rawOut.get("recipeTime").getAsInt());
            check("...and receives the recipe type it was missing", "modular_machinery_reborn:machine",
                    rawOut.get("type").getAsString());

            JsonObject complete = raw.deepCopy();
            complete.addProperty("type", "modular_machinery_reborn:machine");
            Object completeRecipe = schemaClass.getMethod("deserialize", typeFunctionClass, locationClass,
                            JsonObject.class)
                    .invoke(schema, typeFunction, null, complete);
            invoke(completeRecipe, "serialize", new Class<?>[0]);
            check("a complete raw recipe is not rewritten", complete,
                    jsClass.getField("json").get(completeRecipe));
        } catch (ReflectiveOperationException | java.io.IOException broken) {
            Throwable cause = broken;
            while (cause instanceof java.lang.reflect.InvocationTargetException
                    && cause.getCause() != null) {
                cause = cause.getCause();
            }
            java.io.StringWriter trace = new java.io.StringWriter();
            cause.printStackTrace(new java.io.PrintWriter(trace));
            System.out.println("--- typed schema failure ---");
            System.out.println(trace);
            report("the typed schema could be driven at all — "
                    + cause.getClass().getName() + ": " + cause.getMessage(), false);
        }
    }

    // ------------------------------------------------------------------ 4. wiring

    private static void wiring() {
        try {
            String plugin = source("kubejs/ModularMachineryKubeJSPlugin.java");
            check("the plugin registers MachineRecipeSchema.SCHEMA", true,
                    plugin.contains("MachineRecipeSchema.SCHEMA"));
            check("...and no longer registers JsonRecipeSchema", false,
                    plugin.contains("JsonRecipeSchema"));
            String js = source("kubejs/MachineRecipeJS.java");
            check("MachineRecipeJS.serialize delegates typed recipes to the plain builder", true,
                    js.contains("builder.build(machine.toString())"));
            String component = source("kubejs/MachineTargetComponent.java");
            check("the component accepts a JsonObject for the raw shape", true,
                    component.contains("isJsonObject()"));
            String builder = source("recipe/MachineRecipeJsonBuilder.java");
            check("the JSON builder does not depend on KubeJS", false,
                    builder.toLowerCase(java.util.Locale.ROOT).contains("kubejs"));
        } catch (Exception broken) {
            report("the typed API wiring could not be read: " + broken, false);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Object builder() {
        try {
            return Class.forName(BUILDER).getConstructor().newInstance();
        } catch (ReflectiveOperationException missing) {
            throw new IllegalStateException("MachineRecipeJsonBuilder is missing", missing);
        }
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object... args) {
        try {
            Method reflected = target.getClass().getMethod(method, types);
            return reflected.invoke(target, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(method + " failed: " + cause, cause);
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException(method + " is missing: " + broken, broken);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> castMap(Object value) {
        return (Map<Object, Object>) value;
    }

    private static String source(String relative) throws Exception {
        Path path = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                + relative);
        return Files.readString(path)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void check(String what, String expected, String actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void check(String what, boolean expected, boolean actual) {
        ParallelCraftCheck.report(what, expected == actual);
    }

    private static void check(String what, Object expected, Object actual) {
        ParallelCraftCheck.report(what, java.util.Objects.equals(expected, actual));
    }

    private static void report(String what, boolean ok) {
        ParallelCraftCheck.report(what, ok);
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }
}
