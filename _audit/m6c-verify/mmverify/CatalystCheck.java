package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.IOType;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirementParser;
import com.reborn.modularmachinery.recipe.NbtPredicate;
import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RecipeModifiers;
import com.reborn.modularmachinery.recipe.RequirementType;
import com.reborn.modularmachinery.recipe.RequirementTypes;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Q6-B（0.65.0）：catalyst 需求类型。
 *
 * <p>旧版的 catalyst 只能从 CraftTweaker 构造：{@code RequirementTypeCatalyst#createRequirement} 对任何
 * JSON 都返回 {@code null}，所以它从来没有 JSON 形状可以照抄。这里钉住本版新增的那一层，以及它与旧版
 * {@code RequirementCatalyst} 相同的那几条语义：
 *
 * <ul>
 *   <li>开工闸门：组里一个可付的条目都不在，配方不能开工；</li>
 *   <li>不随并行复制：旧版 {@code setParallelizeUnaffected(true)} 会把 parallelism 字段锁成 1
 *       （{@code ComponentRequirement.java:471-489}），所以一次只消耗一组的量；</li>
 *   <li>不参与并行上限：{@code parallelLimit} 恒为 1，{@code MachineRecipe#parallelism} 跳过它；</li>
 *   <li>modifier 在开工前折进结算：旧版在 {@code canStartCrafting} 里加进 context。</li>
 * </ul>
 *
 * <p>旧源码红跑必须还能编译，所以 {@code CatalystRequirement} 只通过 {@link MachineRequirementParser} 得到，
 * 它的专有访问器用反射调用；{@code MachineRecipe#catalystModifiers} 同样用反射。
 */
final class CatalystCheck {

    private static final ResourceLocation RECIPE = new ResourceLocation("mmverify", "catalyst_check");

    private CatalystCheck() {
    }

    static void catalysts() {
        section("Q6-B catalyst 需求（0.65.0）");
        run("registration", CatalystCheck::registration);
        run("parsing", CatalystCheck::parsing);
        run("refusals", CatalystCheck::refusals);
        run("gating and consumption", CatalystCheck::gatingAndConsumption);
        run("wire round trip", CatalystCheck::wireRoundTrip);
        run("recipe-level modifiers", CatalystCheck::recipeLevelModifiers);
        run("wiring", CatalystCheck::wiring);
    }

    private static void registration() {
        RequirementType type = RequirementTypes.byName("catalyst");
        report("catalyst is a registered requirement kind", type != null);
        if (type == null) {
            return;
        }
        check("its id is modular_machinery_reborn:catalyst", "modular_machinery_reborn:catalyst",
                type.id().toString());
        check("it is an item-family requirement (for item modifiers)", "ITEM",
                type.modifierTarget().name());
    }

    private static void parsing() {
        MachineRequirement requirement = parse("""
                {
                  "type": "modular_machinery_reborn:catalyst",
                  "io-type": "input",
                  "items": [
                    {"item": "minecraft:diamond", "amount": 2},
                    {"item": "minecraft:emerald"}
                  ],
                  "amount": 3,
                  "chance": 0.5,
                  "nbt": {"foo": "1"},
                  "modifiers": [
                    {"target": "duration", "io": "input", "operation": 1, "multiplier": 0.5}
                  ],
                  "tooltip": ["Speeds this recipe up", "Consumes one group"]
                }
                """);

        check("it is an input", "INPUT", requirement.ioType().name());
        report("it is not a per-tick requirement", !requirement.perTick());
        report("it is a start-phase requirement", requirement.isStartPhase());
        report("it declares itself unaffected by parallelism", requirement.isParallelizeUnaffected());
        report("it is not parallelisable", !requirement.isParallelizable());
        check("its parallelism limit is one group", 1L,
                requirement.parallelLimit(HatchCollection.EMPTY, RecipeModifiers.EMPTY, 64));
        requirement.setParallelism(4);
        check("a later setParallelism leaves it on one group", 1L, requirement.parallelism());

        check("two entries were read", 2L, (long) callInt(requirement, "entryCount"));
        check("the first entry keeps its own amount", 2L, (long) callInt(requirement, "entryAmount", 0));
        check("the second entry falls back to the requirement amount", 3L,
                (long) callInt(requirement, "entryAmount", 1));
        check("the first entry names the diamond", "minecraft:diamond",
                callString(requirement, "entryItemId", 0));
        report("neither entry is a tag", !callBoolean(requirement, "entryIsTag", 0)
                && !callBoolean(requirement, "entryIsTag", 1));

        check("chance is carried", "0.5", Float.toString(callFloat(requirement, "chance")));
        report("the requirement-level NBT template is carried", callObject(requirement, "nbt") != null);

        List<?> modifiers = (List<?>) callObject(requirement, "modifiers");
        check("one modifier was read", 1L, (long) modifiers.size());
        RecipeModifier modifier = (RecipeModifier) modifiers.get(0);
        check("the modifier targets duration", "DURATION", modifier.target().name());
        check("the modifier multiplies", "MULTIPLY", modifier.operation().name());
        check("the multiplier is 0.5", "0.5", Float.toString(modifier.value()));

        List<?> tooltips = (List<?>) callObject(requirement, "tooltips");
        check("two tooltips were read", 2L, (long) tooltips.size());
        check("tooltip order is preserved", "Speeds this recipe up", String.valueOf(tooltips.get(0)));

        check("describe names the group and the modifier count",
                "catalyst [2x minecraft:diamond, 3x minecraft:emerald] (+1 modifiers)",
                requirement.describe());
    }

    private static void refusals() {
        expectReject("an output catalyst is refused", """
                {"type": "modular_machinery_reborn:catalyst", "io-type": "output",
                 "items": [{"item": "minecraft:diamond"}]}
                """, "catalyst is an <input>");
        expectReject("an unknown key is refused", """
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input",
                 "items": [{"item": "minecraft:diamond"}], "colour": "red"}
                """, "colour");
        expectReject("'modifiers' must be an array", """
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input",
                 "items": [{"item": "minecraft:diamond"}], "modifiers": {}}
                """, "modifiers");
        expectReject("'tooltip' must be an array", """
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input",
                 "items": [{"item": "minecraft:diamond"}], "tooltip": "hello"}
                """, "tooltip");
        expectReject("'items' is required", """
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input"}
                """, "items");
        expectReject("an out-of-range chance is refused", """
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input",
                 "items": [{"item": "minecraft:diamond"}], "chance": 2}
                """, "between 0 and 1");
    }

    private static void gatingAndConsumption() {
        ItemStackHandler input = new ItemStackHandler(2);
        HatchCollection ports = HatchCollection.builder().addItemHandler(input, true).build();
        MachineRequirement catalyst = parse("""
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input",
                 "items": [{"item": "minecraft:diamond"}], "amount": 2}
                """);
        MachineRecipe recipe = recipe(catalyst, RecipeModifiers.EMPTY);

        // The original's absent-catalyst branch returned CraftCheck.skipComponent(), and the recipe context
        // counted only isSuccess() results while dropping INVALID_SKIP: the craft ran without a catalyst.
        report("with an empty hatch the recipe still starts", recipe.canStart(ports));
        report("...but the catalyst is not present", !hasCatalyst(catalyst, ports));

        input.setStackInSlot(0, new ItemStack(Items.DIAMOND, 1));
        report("one diamond is not enough for an amount of two, and does not block the craft",
                recipe.canStart(ports) && !hasCatalyst(catalyst, ports));

        input.setStackInSlot(0, new ItemStack(Items.DIAMOND, 2));
        report("two diamonds make the catalyst present", hasCatalyst(catalyst, ports));
        report("starting consumes the whole group", recipe.start(ports, RandomSource.create(1L)));
        report("both diamonds are gone", input.getStackInSlot(0).isEmpty());

        // The second entry only pays when the first cannot: one group, first payable entry in array order.
        MachineRequirement twoEntries = parse("""
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input",
                 "items": [{"item": "minecraft:diamond", "amount": 2},
                           {"item": "minecraft:emerald", "amount": 3}]}
                """);
        MachineRecipe twoEntryRecipe = recipe(twoEntries, RecipeModifiers.EMPTY);
        input.setStackInSlot(0, new ItemStack(Items.DIAMOND, 1));
        input.setStackInSlot(1, new ItemStack(Items.EMERALD, 3));
        report("an alternative entry pays when the first is short", hasCatalyst(twoEntries, ports));
        report("...and starting consumes the emerald, not the diamond",
                twoEntryRecipe.start(ports, RandomSource.create(2L))
                        && input.getStackInSlot(1).isEmpty()
                        && input.getStackInSlot(0).getCount() == 1);

        // NBT is a filter, not decoration: the same item without the tag cannot pay.
        MachineRequirement withNbt = parse("""
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input",
                 "items": [{"item": "minecraft:diamond", "amount": 2}], "nbt": {"foo": "1"}}
                """);
        MachineRecipe nbtRecipe = recipe(withNbt, RecipeModifiers.EMPTY);
        input.setStackInSlot(0, new ItemStack(Items.DIAMOND, 2));
        report("an untagged stack is not the NBT-filtered catalyst, and does not block the craft",
                nbtRecipe.canStart(ports) && !hasCatalyst(withNbt, ports));

        CompoundTag tag = new CompoundTag();
        tag.putInt("foo", 1);
        ItemStack tagged = new ItemStack(Items.DIAMOND, 2);
        tagged.setTag(tag);
        input.setStackInSlot(0, tagged);
        report("a stack with the matching NBT is the catalyst", hasCatalyst(withNbt, ports));

        // chance 0: presence still counts (the modifiers are folded in), but start pays nothing. The original
        // rolled chance in startCrafting only.
        MachineRequirement neverPaid = parse("""
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input",
                 "items": [{"item": "minecraft:diamond", "amount": 2}], "chance": 0}
                """);
        MachineRecipe chanceRecipe = recipe(neverPaid, RecipeModifiers.EMPTY);
        report("chance 0 still counts as present", hasCatalyst(neverPaid, ports));
        report("...and starting with chance 0 consumes nothing",
                chanceRecipe.start(ports, RandomSource.create(3L)) && input.getStackInSlot(0).getCount() == 2);
    }

    private static boolean hasCatalyst(MachineRequirement requirement, HatchCollection ports) {
        try {
            return (Boolean) requirement.getClass().getMethod("hasCatalyst", HatchCollection.class)
                    .invoke(requirement, ports);
        } catch (ReflectiveOperationException missing) {
            report("CatalystRequirement#hasCatalyst is missing: " + missing, false);
            return false;
        }
    }

    private static void wireRoundTrip() {
        MachineRequirement original = parse("""
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input",
                 "items": [{"item": "minecraft:diamond", "amount": 2}],
                 "chance": 0.25,
                 "nbt": {"foo": "1"},
                 "modifiers": [{"target": "duration", "io": "input", "operation": 1, "multiplier": 0.5}],
                 "tooltip": ["one"]}
                """);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        MachineRequirementParser.write(buffer, original);
        MachineRequirement back = MachineRequirementParser.read(buffer);

        check("the wire carries the catalyst kind", "catalyst", back.type().id().getPath());
        check("...and one entry", 1L, (long) callInt(back, "entryCount"));
        check("...with the entry amount", 2L, (long) callInt(back, "entryAmount", 0));
        check("...with its chance", "0.25", Float.toString(callFloat(back, "chance")));
        report("...with its NBT template", callObject(back, "nbt") instanceof NbtPredicate);
        check("...with its modifier", 1L, (long) ((List<?>) callObject(back, "modifiers")).size());
        check("...with its tooltip", 1L, (long) ((List<?>) callObject(back, "tooltips")).size());
        check("the buffer is fully consumed", 0L, (long) buffer.readableBytes());
    }

    private static void recipeLevelModifiers() {
        MachineRequirement catalyst = parse("""
                {"type": "modular_machinery_reborn:catalyst", "io-type": "input",
                 "items": [{"item": "minecraft:diamond"}],
                 "modifiers": [{"target": "duration", "io": "input", "operation": 1, "multiplier": 0.5}]}
                """);
        MachineRecipe recipe = recipe(catalyst, RecipeModifiers.EMPTY);
        try {
            Method accessor = MachineRecipe.class.getMethod("catalystModifiers", HatchCollection.class);
            ItemStackHandler input = new ItemStackHandler(1);
            HatchCollection empty = HatchCollection.builder().addItemHandler(input, true).build();
            RecipeModifiers absent = (RecipeModifiers) accessor.invoke(recipe, empty);
            // B11: the config default is off; this check is about modifier arithmetic, so opt in.
            check("a catalyst that is not in the ports contributes no modifier", "1.0",
                    Float.toString(absent.durationMultiplier(100, true)));
            input.setStackInSlot(0, new ItemStack(Items.DIAMOND, 1));
            RecipeModifiers modifiers = (RecipeModifiers) accessor.invoke(recipe, empty);
            check("a present catalyst duration modifier halves a 100-tick recipe", 50L, (long) recipe.duration(modifiers));
            check("the raw duration is unchanged without it", 100L, (long) recipe.duration(RecipeModifiers.EMPTY));
            check("the duration multiplier is the inverse ratio", "2.0",
                    Float.toString(modifiers.durationMultiplier(100, true)));
        } catch (ReflectiveOperationException missing) {
            report("MachineRecipe#catalystModifiers(HatchCollection) is missing: " + missing, false);
        }
    }

    private static void wiring() {
        try {
            String controller = source("block/MachineControllerBlockEntity.java");
            report("the controller folds catalyst modifiers in per candidate group",
                    controller.contains("recipe.prepareStart(scope, this.modifiers)"));
            report("...and uses that set when checking a start",
                    controller.contains("recipe.canStart(scope, prepared)"));
            report("...and when computing parallelism",
                    controller.contains("recipe.parallelism(scope, plan, machineCeiling)"));
            report("...and freezes the set for the running craft",
                    controller.contains("be.activeCraftModifiers = effective;")
                            && controller.contains("this.activeCraftModifiers = null;"));

            String factory = source("factory/FactoryEngine.java");
            report("the factory folds catalyst modifiers per (recipe, group)",
                    factory.contains("candidate.prepareStart(groupPorts, modifiers)"));
            report("...and screens candidate recipes with them",
                    factory.contains("candidate.canStart(groupPorts, plan)"));
            report("...and freezes them into the craft",
                    factory.contains("this.modifiers = plan.modifiers()") && factory.contains("craft.modifiers()"));

            String requirement = source("recipe/CatalystRequirement.java");
            report("the catalyst declares itself unaffected by parallelism",
                    requirement.contains("setParallelizeUnaffected(true);"));
            report("...and is an input, not a per-tick requirement",
                    requirement.contains("super(IOType.INPUT, false);"));

            String types = source("recipe/RequirementTypes.java");
            report("catalyst is registered as a built-in kind", types.contains("of(\"catalyst\""));

            String parser = source("recipe/CatalystParser.java");
            report("the reader accepts modifiers and tooltip", parser.contains("\"modifiers\"")
                    && parser.contains("\"tooltip\""));

            String recipe = source("recipe/MachineRecipe.java");
            report("the recipe exposes presence-aware catalyst modifiers",
                    recipe.contains("public RecipeModifiers catalystModifiers(HatchCollection ports)"));

            // JEI: a catalyst is an ingredient array plus its own effect lines. Without the explicit branch it
            // would be cast to ItemRequirement and the page would crash on open.
            String jei = source("client/jei/MachineRecipeCategory.java");
            report("the JEI page recognises a catalyst before casting an item requirement",
                    jei.contains("instanceof CatalystRequirement catalystRequirement"));
            report("...and lays out the catalyst's own stacks", jei.contains("catalystRequirement.entryStacksFlat()"));
            report("...and appends the catalyst effect lines to the slot tooltip",
                    jei.contains("addCatalystTooltip(tooltip, finalCatalyst.tooltips())"));

            String jeiText = source("client/jei/MachineRecipeText.java");
            report("the JEI text carries the original's catalyst key",
                    jeiText.contains("tooltip.modular_machinery_reborn.catalyst"));
            report("...and its effect key", jeiText.contains("tooltip.modular_machinery_reborn.catalyst.effect"));
            report("...and prints the effect list", jeiText.contains("static void addCatalystTooltip("));

            report("the catalyst can render itself as stacks",
                    requirement.contains("public List<ItemStack> entryStacksFlat()"));

            for (String lang : new String[]{"zh_cn.json", "en_us.json"}) {
                String text = Files.readString(Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/assets/"
                        + "modular_machinery_reborn/lang/" + lang));
                report(lang + " has the catalyst tooltip keys", text.contains("\"tooltip.modular_machinery_reborn.catalyst\"")
                        && text.contains("\"tooltip.modular_machinery_reborn.catalyst.effect\""));
            }
        } catch (Throwable broken) {
            report("the catalyst wiring cannot be read: " + broken, false);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static MachineRecipe recipe(MachineRequirement requirement, RecipeModifiers ignored) {
        return new MachineRecipe(RECIPE, "catalyst_machine", "catalyst_check", 100, List.of(requirement));
    }

    private static MachineRequirement parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return MachineRequirementParser.parse(object, RECIPE);
    }

    private static void expectReject(String label, String json, String needle) {
        try {
            parse(json);
            report(label + " — but it was accepted", false);
        } catch (RuntimeException rejected) {
            String message = String.valueOf(rejected.getMessage());
            report(label + " (" + message + ")", message.toLowerCase().contains(needle.toLowerCase()));
        }
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

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    private static void run(String label, Runnable body) {
        try {
            body.run();
        } catch (Throwable broken) {
            report(label + " crashed: " + broken, false);
        }
    }

    private static int callInt(Object target, String method, Object... args) {
        return (Integer) call(target, method, args);
    }

    private static float callFloat(Object target, String method, Object... args) {
        return (Float) call(target, method, args);
    }

    private static String callString(Object target, String method, Object... args) {
        return String.valueOf(call(target, method, args));
    }

    private static boolean callBoolean(Object target, String method, Object... args) {
        return (Boolean) call(target, method, args);
    }

    private static Object callObject(Object target, String method, Object... args) {
        return call(target, method, args);
    }

    private static Object call(Object target, String method, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] instanceof Integer ? int.class : args[i].getClass();
            }
            Method reflected = target.getClass().getMethod(method, types);
            return reflected.invoke(target, args);
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException(method + " is missing: " + broken, broken);
        }
    }

    private static String source(String relative) throws Exception {
        Path path = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                + relative);
        return Files.readString(path)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("(?m)//.*$", "");
    }
}
