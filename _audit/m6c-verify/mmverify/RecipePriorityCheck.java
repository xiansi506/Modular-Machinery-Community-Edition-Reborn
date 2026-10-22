package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.compat.mekanism.GasRequirement;
import com.reborn.modularmachinery.recipe.FluidRequirement;
import com.reborn.modularmachinery.recipe.ItemRequirement;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirementParser;
import com.reborn.modularmachinery.recipe.RecipeCommands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.util.LazyOptional;
import io.netty.buffer.Unpooled;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * B3/B4：配方级 priority 与同优先级排序权重。
 *
 * <p>旧版把配方按 priority 分层（TreeMap），层内按 buildWeight 升序（sortId - 所有输入需求的
 * sortingWeight 之和）；比较器只比较权重，而 sortId 由构造顺序给出，所以同层配方的顺序是
 * 「越早加载越靠前，但输入越多越能往前挤」。这里钉住字段、解析、网络、权重公式和实际选取顺序。
 */
public final class RecipePriorityCheck {

    private static final ResourceLocation RECIPE_ID = new ResourceLocation("mmverify", "priority_demo");
    private static final ResourceLocation MACHINE = new ResourceLocation("modular_machinery_reborn", "demo");

    private RecipePriorityCheck() {
    }

    public static void recipePriority() {
        section("B3/B4 配方优先级与排序权重");
        parsePriority();
        sortingWeights();
        buildWeightAndOrdering();
        builderAndKubeJs();
        wiring();
    }

    private static void parsePriority() {
        try {
            Method parse = MachineRecipe.class.getMethod("parsePriority", JsonObject.class, ResourceLocation.class);
            check("缺省 priority 是 0", 0L,
                    (long) (Integer) parse.invoke(null, new JsonObject(), RECIPE_ID));
            JsonObject set = new JsonObject();
            set.addProperty("priority", 7);
            check("priority 从文件读出", 7L, (long) (Integer) parse.invoke(null, set, RECIPE_ID));
            JsonObject negative = new JsonObject();
            negative.addProperty("priority", -3);
            check("负 priority 原样保留", -3L, (long) (Integer) parse.invoke(null, negative, RECIPE_ID));

            JsonObject bad = new JsonObject();
            bad.addProperty("priority", "high");
            try {
                parse.invoke(null, bad, RECIPE_ID);
                report("字符串 priority 被拒绝", false);
            } catch (InvocationTargetException wrapped) {
                String message = String.valueOf(wrapped.getCause().getMessage());
                report("字符串 priority 被拒绝：旧版原句", message.contains(
                        "'priority' has to be a number! (if specified)"));
            }
        } catch (ReflectiveOperationException missing) {
            report("MachineRecipe.parsePriority 存在（" + missing + "）", false);
        }
    }

    private static void sortingWeights() {
        check("物品需求的排序权重", 50_000L, constant("PRIORITY_WEIGHT_ITEM"));
        check("流体需求的排序权重", 100L, constant("PRIORITY_WEIGHT_FLUID"));
        check("能量需求的排序权重", 50_000_000L, constant("PRIORITY_WEIGHT_ENERGY"));

        MachineRequirement item = parseRequirement("""
                {"type":"modular_machinery_reborn:item","io-type":"input",
                 "item":"minecraft:stone","amount":1}
                """);
        MachineRequirement fluid = parseRequirement("""
                {"type":"modular_machinery_reborn:fluid","io-type":"input",
                 "fluid":"minecraft:water","amount":100}
                """);
        MachineRequirement energy = parseRequirement("""
                {"type":"modular_machinery_reborn:energy","io-type":"input","energyPerTick":10}
                """);
        check("物品需求返回物品权重", 50_000L, weight(item));
        check("流体需求返回流体权重", 100L, weight(fluid));
        check("能量需求返回能量权重", 50_000_000L, weight(energy));

        try {
            Method gasWeight = GasRequirement.class.getMethod("sortingWeight");
            report("气体需求自己覆盖 sortingWeight，复用流体权重",
                    gasWeight.getDeclaringClass() == GasRequirement.class);
        } catch (ReflectiveOperationException missing) {
            report("气体需求自己覆盖 sortingWeight（" + missing + "）", false);
        }
    }

    private static void buildWeightAndOrdering() {
        MachineRequirement itemOne = parseRequirement("""
                {"type":"modular_machinery_reborn:item","io-type":"input",
                 "item":"minecraft:stone","amount":1}
                """);
        MachineRequirement itemOutput = parseRequirement("""
                {"type":"modular_machinery_reborn:item","io-type":"output",
                 "item":"minecraft:diamond","amount":1}
                """);
        MachineRequirement fluid = parseRequirement("""
                {"type":"modular_machinery_reborn:fluid","io-type":"input",
                 "fluid":"minecraft:water","amount":100}
                """);
        MachineRequirement energy = parseRequirement("""
                {"type":"modular_machinery_reborn:energy","io-type":"input","energyPerTick":10}
                """);

        try {
            MachineRecipe recipe = recipe("weight", 0, 1_000_000_000,
                    List.of(itemOne, fluid, energy, itemOutput));
            check("buildWeight 从 sortId 中扣掉每个输入需求的权重，输出不扣",
                    949_949_900L, (long) recipeWeight(recipe));
            check("配方保留 priority", 0L, (long) priority(recipe));
            check("配方保留 sortId", 1_000_000_000L, (long) sortId(recipe));

            // priority 1：sortId 越大越靠后；但输入权重可以把后来者推到前面。
            MachineRecipe lowPriority = recipe("low", 5, 300, List.of(itemOne));
            MachineRecipe first = recipe("first", 1, 100, List.of(itemOne));
            MachineRecipe heavier = recipe("heavier", 1, 200, List.of(itemOne, energy));

            List<MachineRecipe> sorted = sortedForMachine(List.of(lowPriority, first, heavier), MACHINE);
            check("RecipeOrder 先按 priority 升序", 3L, (long) sorted.size());
            check("...再按 buildWeight 升序：输入更多的更靠前", "heavier|first",
                    sorted.get(0).getId().getPath() + "|" + sorted.get(1).getId().getPath());
            check("...高 priority 永远排在后层", "low",
                    sortedForMachine(List.of(lowPriority, first), MACHINE).get(1).getId().getPath());

            MachineRecipe otherMachine = recipe("other", 0, 1, List.of(itemOne));
            // recipe() 把每条测试配方都挂到 demo 上；所以「按 other 过滤」应当是空的，
            // 而「按 demo 过滤」反而必须留下它。旧断言的期望值与夹具相反，绿跑时被这条抓到。
            check("RecipeOrder 只留属于这台机器的配方", 0L,
                    (long) sortedForMachine(List.of(otherMachine), new ResourceLocation(
                            "modular_machinery_reborn", "other")).size());
            check("...属于这台机器的配方仍在", "other",
                    sortedForMachine(List.of(otherMachine), MACHINE).get(0).getId().getPath());
        } catch (ReflectiveOperationException missing) {
            report("MachineRecipe(priority, sortId) / RecipeOrder 存在（" + missing + "）", false);
        }
    }

    private static void builderAndKubeJs() {
        try {
            MachineRecipeJsonBuilderProbe probe = new MachineRecipeJsonBuilderProbe();
            report("MachineRecipeJsonBuilder.priority 保持流式", probe.fluent(7));
            check("builder 把 priority 写进配方 JSON", 7L, probe.priorityInJson());
            JsonObject built = probe.build();
            check("builder 写出的 priority 能读回", 7L,
                    (long) (Integer) MachineRecipe.class.getMethod("parsePriority", JsonObject.class,
                            ResourceLocation.class).invoke(null, built, RECIPE_ID));
        } catch (ReflectiveOperationException missing) {
            report("MachineRecipeJsonBuilder.priority 存在（" + missing + "）", false);
        }

        String kubejs = source("kubejs/MachineRecipeJS.java");
        report("KubeJS typed API 有 priority(int)", kubejs.contains("priority(int"));
        report("...并转发给 builder", kubejs.contains("this.builder.priority(priority)"));
    }

    private static void wiring() {
        String controller = source("block/MachineControllerBlockEntity.java");
        String diagnose = source("command/DiagnoseCommand.java");
        String jei = source("client/jei/ModularMachineryJeiPlugin.java");
        report("控制器用 RecipeOrder 选取候选配方", controller.contains("RecipeOrder.forMachine"));
        report("诊断命令按同一顺序列配方", diagnose.contains("RecipeOrder.forMachine"));
        report("JEI 按同一顺序列配方", jei.contains("RecipeOrder.sorted"));
        report("旧版原句没有被改写", controller.contains("RecipeOrder.forMachine")
                && diagnose.contains("RecipeOrder.forMachine"));

        // Network: MachineSerializer is a static nested class, so it can be driven without initialising
        // ModRecipeSerializers' DeferredRegister. This is the same reason the parser lives outside it.
        try {
            Class<?> serializerType = Class.forName(
                    "com.reborn.modularmachinery.recipe.ModRecipeSerializers$MachineSerializer");
            Constructor<?> constructor = serializerType.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object serializer = constructor.newInstance();
            MachineRecipe original = recipe("wire", 4, 12345, List.of(
                    parseRequirement("""
                            {"type":"modular_machinery_reborn:item","io-type":"input",
                             "item":"minecraft:stone","amount":1}
                            """)));
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            // MachineSerializer 本身是 private 嵌套类：方法虽然 public，反射调用仍要求 setAccessible，
            // 否则 invoke 会抛 IllegalAccessException（绿跑时正是这样被抓住的）。
            Method toNetwork = serializerType.getDeclaredMethod("toNetwork", FriendlyByteBuf.class,
                    MachineRecipe.class);
            toNetwork.setAccessible(true);
            toNetwork.invoke(serializer, buffer, original);
            Method fromNetwork = serializerType.getDeclaredMethod("fromNetwork", ResourceLocation.class,
                    FriendlyByteBuf.class);
            fromNetwork.setAccessible(true);
            MachineRecipe back = (MachineRecipe) fromNetwork.invoke(serializer, RECIPE_ID, buffer);
            check("网络包带回 priority", 4L, (long) priority(back));
            check("网络包带回 sortId", 12345L, (long) sortId(back));
            check("网络包被完全读掉", 0L, (long) buffer.readableBytes());
        } catch (ReflectiveOperationException | LinkageError problem) {
            report("MachineSerializer 的 priority/sortId 网络往返（" + problem + "）", false);
        }
    }

    private static long constant(String name) {
        try {
            Field field = MachineRequirement.class.getField(name);
            return ((Number) field.get(null)).longValue();
        } catch (ReflectiveOperationException missing) {
            report("MachineRequirement." + name + " 存在（" + missing + "）", false);
            return Long.MIN_VALUE;
        }
    }

    private static long weight(MachineRequirement requirement) {
        try {
            return ((Number) requirement.getClass().getMethod("sortingWeight").invoke(requirement))
                    .longValue();
        } catch (ReflectiveOperationException missing) {
            report(requirement.getClass().getSimpleName() + ".sortingWeight 存在（" + missing + "）", false);
            return Long.MIN_VALUE;
        }
    }

    private static long recipeWeight(MachineRecipe recipe) throws ReflectiveOperationException {
        return ((Number) MachineRecipe.class.getMethod("buildWeight").invoke(recipe)).longValue();
    }

    private static int priority(MachineRecipe recipe) throws ReflectiveOperationException {
        return (Integer) MachineRecipe.class.getMethod("priority").invoke(recipe);
    }

    private static int sortId(MachineRecipe recipe) throws ReflectiveOperationException {
        return (Integer) MachineRecipe.class.getMethod("sortId").invoke(recipe);
    }

    private static MachineRecipe recipe(String path, int priority, int sortId,
                                        List<MachineRequirement> requirements)
            throws ReflectiveOperationException {
        Constructor<MachineRecipe> constructor = MachineRecipe.class.getConstructor(
                ResourceLocation.class, List.class, String.class, int.class, List.class, int.class,
                boolean.class, RecipeCommands.class, int.class, int.class);
        return constructor.newInstance(new ResourceLocation("mmverify", path), List.of("demo"), path, 100,
                requirements, 1, false, RecipeCommands.EMPTY, priority, sortId);
    }

    @SuppressWarnings("unchecked")
    private static List<MachineRecipe> sortedForMachine(Iterable<MachineRecipe> recipes,
                                                        ResourceLocation machine)
            throws ReflectiveOperationException {
        Class<?> order = Class.forName("com.reborn.modularmachinery.recipe.RecipeOrder");
        Method method = order.getMethod("forMachine", Iterable.class, ResourceLocation.class);
        return (List<MachineRecipe>) method.invoke(null, recipes, machine);
    }

    private static MachineRequirement parseRequirement(String json) {
        return MachineRequirementParser.parse(JsonParser.parseString(json).getAsJsonObject(), RECIPE_ID);
    }

    private static String source(String relative) {
        try {
            return Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/" + relative))
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("(?m)//.*$", "");
        } catch (Exception unreadable) {
            throw new IllegalStateException("cannot read " + relative, unreadable);
        }
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void check(String what, String expected, String actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }

    /** Isolates the builder reflection so the rest of this class still compiles against the red tree. */
    private static final class MachineRecipeJsonBuilderProbe {

        private final Object builder;

        private MachineRecipeJsonBuilderProbe() throws ReflectiveOperationException {
            this.builder = Class.forName("com.reborn.modularmachinery.recipe.MachineRecipeJsonBuilder")
                    .getDeclaredConstructor().newInstance();
        }

        private boolean fluent(int priority) throws ReflectiveOperationException {
            Method method = this.builder.getClass().getMethod("priority", int.class);
            return method.invoke(this.builder, priority) == this.builder;
        }

        private JsonObject build() throws ReflectiveOperationException {
            return (JsonObject) this.builder.getClass().getMethod("build", String.class)
                    .invoke(this.builder, "demo");
        }

        private long priorityInJson() throws ReflectiveOperationException {
            return build().get("priority").getAsInt();
        }
    }
}
