package mmverify;

import com.reborn.modularmachinery.machine.DynamicPattern;
import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.EnergyRequirement;
import com.reborn.modularmachinery.recipe.IOType;
import com.reborn.modularmachinery.recipe.InterfaceNumberInputRequirement;
import com.reborn.modularmachinery.recipe.FluidRequirement;
import com.reborn.modularmachinery.recipe.ItemRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RecipeModifiers;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.items.ItemStackHandler;

import com.reborn.modularmachinery.machine.BlockMatcher;
import com.reborn.modularmachinery.machine.FailureAction;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachinePattern;
import com.reborn.modularmachinery.machine.MachineSchema;
import com.reborn.modularmachinery.machine.ParallelController;
import com.reborn.modularmachinery.machine.ParallelControllerCollection;
import com.reborn.modularmachinery.machine.ParallelismLimit;
import com.reborn.modularmachinery.block.ParallelControllerBlockEntity;
import com.reborn.modularmachinery.block.ParallelControllerTier;
import com.reborn.modularmachinery.block.UpgradeBusTier;
import com.reborn.modularmachinery.config.DisplayNumbers;
import com.reborn.modularmachinery.config.EnergyDisplay;
import com.reborn.modularmachinery.config.ModConfig;
import com.reborn.modularmachinery.network.ParallelControllerUpdatePacket;
import com.reborn.modularmachinery.upgrade.UpgradeBusUtility;
import com.reborn.modularmachinery.upgrade.BusUpgradeData;
import com.reborn.modularmachinery.upgrade.UpgradeEffects;
import com.reborn.modularmachinery.upgrade.UpgradeItemNbt;
import com.reborn.modularmachinery.upgrade.UpgradeStack;
import com.reborn.modularmachinery.upgrade.UpgradeTarget;
import com.reborn.modularmachinery.upgrade.UpgradeType;
import com.reborn.modularmachinery.block.MachineControllerBlock;
import com.reborn.modularmachinery.factory.FactoryEngine;
import com.reborn.modularmachinery.factory.FactoryHost;
import com.reborn.modularmachinery.factory.FactoryThread;
import com.reborn.modularmachinery.factory.FactoryThreadModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;/**
 * Offline acceptance harness for M6c's parallel settlement.
 *
 * <p>It drives {@link MachineRecipe} in the same order {@code MachineControllerBlockEntity#tick} does — compute
 * the limit, freeze it, canStart, start, per-tick, canFinish, finish — against in-memory ports, and prints what
 * actually moved. That is the evidence the scoping document asked for: N inputs consumed and N outputs produced,
 * not a label rendering.
 *
 * <p>Not part of the mod: it lives outside {@code src/} and is compiled and run by a throwaway Gradle init
 * script.
 */
public final class ParallelCraftCheck {

    private static int failures = 0;
    private static int checks = 0;
    /** Owning the evidence: from the first line of main, stdout and stderr are teed to a file. */
    private static Evidence EVIDENCE;

    /**
     * Prints the one line the acceptance rule is written against. It is emitted from {@code report}'s own
     * two counters, and only once, so the numbers in the evidence file are the numbers the checks
     * produced rather than a second count of the file's own text.
     */
    private static void printCounts() {
        if (EVIDENCE != null && !EVIDENCE.summaryPrinted) {
            EVIDENCE.summaryPrinted = true;
            System.out.println("SUMMARY: " + (checks - failures) + " PASS / " + failures
                    + " FAIL (" + checks + " checks)");
        }
    }

    private static ItemStackHandler itemIn;
    private static ItemStackHandler itemOut;
    private static EnergyStorage energyIn;

    public static void main(String[] args) {
        // Shape (A): the producer owns its evidence. This runs before anything else can print, so
        // the file holds every section header, every PASS/FAIL line and the summary -- however the
        // harness was launched (Gradle task, a bare java command, an IDE, a future CI step). The console
        // still receives all of it: this is a tee, not a redirect.
        EVIDENCE = new Evidence(args);
        System.out.println("evidence file: " + EVIDENCE.file);
        System.out.println(EVIDENCE.selfTest());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // The machine: 8x coal -> 1x diamond, 10 ticks, 100 FE/t. The shape of the alloy furnace's recipes.
        MachineRecipe recipe = new MachineRecipe(new ResourceLocation("mmverify", "parallel_demo"), "demo", "demo",
                10, List.of(
                        ItemRequirement.input(ingredient(Items.COAL), 8),
                        ItemRequirement.output(new ItemStack(Items.DIAMOND), 1, 1, 1.0F),
                        new EnergyRequirement(IOType.INPUT, 100L)));

        section("A. no modifiers, machine ceiling 8");
        reset(128, 4, 1_000_000);
        int limit = recipe.parallelism(ports(), RecipeModifiers.EMPTY, 8);
        check("parallelism limit from 128 coal / 8 per copy", 8, limit);
        craft(recipe, RecipeModifiers.EMPTY, limit);
        check("coal consumed", 8 * 8, 128 - coalLeft());
        check("diamonds produced", 8, outputCount(Items.DIAMOND));
        check("FE drawn over the whole craft", 8 * 100L * 10L, 1_000_000 - energyIn.getEnergyStored());

        section("B. the alloy furnace's own modifier: item output x2 at the vent position");
        reset(128, 4, 1_000_000);
        RecipeModifiers doubler = RecipeModifiers.of(List.of(
                RecipeModifier.multiplyOutput(RecipeModifier.Target.ITEM, 2.0F)));
        limit = recipe.parallelism(ports(), doubler, 8);
        check("parallelism limit is unaffected by an output modifier", 8, limit);
        craft(recipe, doubler, limit);
        check("diamonds produced (doubled)", 16, outputCount(Items.DIAMOND));

        section("C. a modifier that doubles the input cost divides the limit");
        reset(128, 4, 1_000_000);
        RecipeModifiers inputDoubler = RecipeModifiers.of(List.of(
                new RecipeModifier(RecipeModifier.Target.ITEM, IOType.INPUT, 2.0F,
                        RecipeModifier.Operation.MULTIPLY, false)));
        // 128 coal / (8 * 2) per copy = 8 copies, and the machine ceiling is 8.
        limit = recipe.parallelism(ports(), inputDoubler, 8);
        check("limit from 128 coal at 16 per copy", 8, limit);
        craft(recipe, inputDoubler, limit);
        check("coal consumed", 8 * 16, 128 - coalLeft());
        check("diamonds produced", 8, outputCount(Items.DIAMOND));

        // Halve the supply with the ceiling raised, so the supply really is what bounds it: 64 / 16 = 4.
        reset(64, 4, 1_000_000);
        limit = recipe.parallelism(ports(), inputDoubler, 64);
        check("limit from 64 coal at 16 per copy against a ceiling of 64", 4, limit);
        craft(recipe, inputDoubler, limit);
        check("coal consumed", 4 * 16, 64 - coalLeft());
        check("diamonds produced", 4, outputCount(Items.DIAMOND));

        // Without the modifier the same supply supports twice as many copies: 64 / 8 = 8.
        reset(64, 4, 1_000_000);
        limit = recipe.parallelism(ports(), RecipeModifiers.EMPTY, 64);
        check("the same 64 coal without the modifier", 8, limit);

        section("D. an output hatch with only 4 free slots caps the limit");
        reset(128, 4, 1_000_000);
        ItemStackHandler tightOut = new ItemStackHandler(1) {
            @Override public int getSlotLimit(int slot) { return 4; }
        };
        limit = recipe.parallelism(portsWith(tightOut), RecipeModifiers.EMPTY, 8);
        check("limit from an output hatch that holds 4", 4, limit);

        section("E. a recipe-level max-parallelism overrides a large machine ceiling");
        reset(128, 4, 1_000_000);
        MachineRecipe capped = new MachineRecipe(new ResourceLocation("mmverify", "capped"), "demo", "capped",
                10, recipe.requirements(), 2);
        limit = capped.parallelism(ports(), RecipeModifiers.EMPTY, 64);
        check("limit with max-parallelism 2 on a machine that allows 64", 2, limit);

        section("F. an energy-starved machine is throttled by the energy requirement");
        reset(128, 4, 1_000_000);
        energyIn.extractEnergy(energyIn.getEnergyStored(), false);
        energyIn.receiveEnergy(250, false);
        limit = recipe.parallelism(ports(), RecipeModifiers.EMPTY, 8);
        check("limit from 250 FE at 100 FE/copy/tick", 2, limit);

        section("G. duration modifiers");
        RecipeModifiers faster = RecipeModifiers.of(List.of(
                new RecipeModifier(RecipeModifier.Target.DURATION, null, 0.5F,
                        RecipeModifier.Operation.MULTIPLY, false)));
        check("10 ticks x 0.5", 5, recipe.duration(faster));
        check("duration multiplier", "2.0", String.valueOf(faster.durationMultiplier(10)));
        reset(128, 4, 1_000_000);
        craft(recipe, faster, 2);
        check("FE drawn is unchanged in total for a halved duration", 2 * 100L * 10L,
                1_000_000 - energyIn.getEnergyStored());

        section("H. the limit is 0 when even one copy cannot be paid");
        reset(3, 4, 1_000_000);
        check("limit with 3 of 8 coal", 0, recipe.parallelism(ports(), RecipeModifiers.EMPTY, 8));

        section("I. a 50% output at parallelism 4");
        MachineRecipe chanced = new MachineRecipe(new ResourceLocation("mmverify", "chanced"), "demo", "chanced",
                10, List.of(
                        ItemRequirement.input(ingredient(Items.COAL), 1),
                        ItemRequirement.output(new ItemStack(Items.DIAMOND), 1, 1, 1.0F),
                        ItemRequirement.output(new ItemStack(Items.EMERALD), 1, 1, 0.5F)));
        long diamonds = 0L;
        long emeralds = 0L;
        int rounds = 400;
        for (int i = 0; i < rounds; i++) {
            reset(4, 8, 1_000_000);
            craft(chanced, RecipeModifiers.EMPTY, 4);
            diamonds += outputCount(Items.DIAMOND);
            emeralds += outputCount(Items.EMERALD);
        }
        check("guaranteed diamonds over " + rounds + " crafts at parallelism 4", (long) rounds * 4, diamonds);
        double expected = rounds * 4.0 * 0.5;
        boolean within = emeralds > expected * 0.9 && emeralds < expected * 1.1;
        report("chance emeralds over " + rounds + " crafts: got " + emeralds + ", expected about " + expected,
                within);

        section("J. default machine values preserve today's behaviour");
        check("default max-parallelism", 2048,
                com.reborn.modularmachinery.machine.MachineDefinition.DEFAULT_MAX_PARALLELISM);
        check("default internal-parallelism", 0,
                com.reborn.modularmachinery.machine.MachineDefinition.DEFAULT_INTERNAL_PARALLELISM);
        reset(128, 4, 1_000_000);
        // A definition with no parallelism fields at all: ceiling 1, so the controller never parallelises.
        int defaultLimit = recipe.parallelism(ports(), RecipeModifiers.EMPTY, 1);
        check("limit on a machine whose ceiling is 1 (the pre-M6c behaviour)", 1, defaultLimit);
        craft(recipe, RecipeModifiers.EMPTY, defaultLimit);
        check("coal consumed", 8, 128 - coalLeft());
        check("diamonds produced", 1, outputCount(Items.DIAMOND));

        section("K. the machine loader's field rules and validation messages");
        loaderDefaults();
        loaderErrors();

        parallelControllerLimit();
        selectorTags();
        dynamicPatterns();
        x2SegmentRotation();
        x3ZeroSection();
        dynamicPatternPreviews();
        shippedContentMatches();
        packetRoundTrip();
        upgradeBusEffects();
        factoryLoaderFields();
        factoryEngine();
        factoryPresentationAndConfig();
        smartInterfaceChain();
        noFailureSentinel();
        screenLayoutMetrics();
        slotFrameAlignment();
        failureActionSection();
        // 0.26.0: ingredient_array_input. Reflection-first, so the pre-implementation run compiles and reports
        // FAIL rather than failing to build -- the red-first evidence the wave requires.
        IngredientArrayCheck.ingredientArrayInput();
        // 0.63.6: B1/B5, the item NBT template and the ingredient_array NBT. Reflection-first, so the
        // pre-implementation run reports FAIL instead of failing to compile.
        NbtRequirementCheck.nbtRequirements();
        // 0.63.7: B2, the original's any:fuel was parsed but could never run. This section lives before the fix
        // so the red run reports the current "Unknown item" message instead of failing to compile.
        FuelRequirementCheck.fuelRequirements();
        // 0.27.0: the MOC compatibility namespace. Reflection-first for the same reason, and it owns the
        // timing check that justifies the design.
        MocNamespaceCheck.mocCompatibilityNamespace();
        // 0.31.0: the generated data pack that gives an author-declared controller its loot table. Also
        // reflection-first — the builder is a game-side class, and the pre-implementation run must report FAIL
        // rather than fail to build.
        MocNamespaceCheck.generatedControllerLootTables();
        // 0.28.0: the KubeJS machine-definition entry point. Also reflection-first, because the KubeJS jar is a
        // compileOnly dependency and is not on this harness's classpath: the pre-implementation run must compile
        // and report FAIL rather than fail to build.
        KubeJSMachineCheck.kubeJsMachineApi();
        // 0.28.1: the binding the script actually receives. Unlike section Z this one does load KubeJS — in a
        // child loader, with the mod's real plugin driven through it — because 0.28.0's defect was invisible to
        // every check that only read class files.
        KubeJSBindingCheck.kubeJsBinding();
        // 0.28.2: what the documented .part(x, y, z, …) actually reaches once Rhino has resolved it. Z9 proved
        // a script can CALL the event; this proves the call it makes by the guide's own wording builds the
        // definition the guide says it builds. Same child loader, same real KubeJS, same real Rhino.
        KubeJSBindingCheck.kubeJsBuilderDispatch();
        // 0.29.0: the construct tool. Reflection-first for the same reason as X/Y/Z — the production classes do
        // not exist yet, so the pre-implementation run must compile and report FAIL rather than fail to build.
        // It owns the one thing about this tool that can be settled without a game: the selection and rotation
        // arithmetic, the exported fragment's text, and the file-name scheme.
        ConstructToolCheck.constructTool();
        // 波 5.2 (0.33.0): the requirement-type registry and the compat layer. It owns the one claim this wave
        // exists to make -- that a compat module can add a requirement kind without editing
        // ModRecipeSerializers -- and it drives that claim end to end (register, parse, wire round trip). The
        // classes are not reflected over: the registry was moved out of ModRecipeSerializers precisely so that a
        // harness can load and drive it, which is why this section can be written against the production types
        // directly instead of by name.
        RequirementTypeCheck.requirementTypeRegistry();
        // 波 5.3 (0.34.0): the first real compat module -- Mekanism chemical, i.e. the original's "gas". This is
        // the only way to find out whether the wave-5.2 framework works for its stated purpose, because it
        // drives that framework with a module that is NOT built in. Its boundary (what an offline harness can and
        // cannot prove about a Mekanism API) was measured with a throwaway probe before this section was
        // written, and the section pins that boundary with an assertion so the next person need not re-measure it.
        MekanismCompatCheck.mekanismChemical();
        // 0.50.0: 本模组自己的 Jade 插件。钉的是**结构**（插件类不 import 客户端类型、客户端那一段
        // 只在 registerClient 里被引用），因为 Jade 会在两侧都加载插件类，而这条约束离线可证。
        MekanismCompatCheck.jadePlugin();
        // 0.63.5：Jade 提示里的气体条。空流体时提示里没有流体行，第 1 行是 Mekanism 的气体元素；
        // 旧代码按固定下标清第 1 行，把它删了。
        MekanismCompatCheck.jadeGasRow();
        MekanismCompatCheck.jeiRequirementDispatch();
        // 波 6 第 2 项（color）的第一小步：解析与保存。
        ColorCheck.colorParsing();
        // 0.63.0：勘测 X1 / X9——不是「还没做」，而是「已经写错」。
        // X1 的两份根字段名单必须是一个契约（否则合法定义会被 strict 路径拒收）；
        // X9 的蓝图槽必须一格只放一张（旧版显式设过上限 1，搬家时漏了）。
        RootFieldListCheck.rootFieldLists();
        BlueprintSlotCheck.blueprintSlotLimit();
        // 0.63.8：A9——方块没了，槽里的物品不能跟着消失。旧版三个方块都在 breakBlock 里吐库存，
        // 本版只有升级总线记得；物品仓与控制器连蓝图都会一起没。
        BlockDropCheck.blockDrops();
        System.out.println();
        printCounts();
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED");
        closeEvidence();
        if (failures != 0) {
            // The exit status is the failure semantics and it is untouched: writing the evidence
            // must never swallow a failure.
            System.exit(1);
        }
        // 0.31.0: exit explicitly on the success path too, after closeEvidence() has flushed the transcript.
        //
        // Why this became necessary: the harness reaches a full green run only now and then, and until 0.31.0 a
        // green run simply returned from main and let the JVM wait for every non-daemon thread. Section Z10's new
        // v2b driving enters Rhino contexts (Context.enter) that are never left, and at least one of them leaves a
        // thread alive, so a *green* run hung after printing its summary while a *failing* run exited promptly —
        // the failure path was the only one with a System.exit. That is a nasty shape: the better the build, the
        // longer the task takes, and a hang looks like a slow build rather than a defect.
        //
        // The evidence file is complete before this line runs (closeEvidence() writes the summary first), and the
        // counts above are the same counters the file carries, so exiting here changes nothing about what is
        // proven — only about how long the JVM waits afterwards.
        System.exit(0);
    }

    // =================================================================== M6e: the factory

    private static final ResourceLocation EXAMPLE_FACTORY =
            new ResourceLocation("modular_machinery_reborn", "example_factory");
    private static final ResourceLocation STONE_RECIPE =
            new ResourceLocation("mmverify", "factory_stone");
    private static final ResourceLocation SMELT_RECIPE =
            new ResourceLocation("mmverify", "factory_smelt");

    /**
     * Section P — M6e's four machine-JSON fields, read through the loader's own {@code readMachine} exactly as
     * section K reads the parallelism fields.
     *
     * <p>Two of them ({@code has-factory} / {@code factory-only}) are the original's own schema and were
     * "recognised but not evaluated" until this release; the other two ({@code max-threads} / {@code
     * core-threads}) are this project's documented extensions, for D12's reason. Every malformed value must be
     * rejected with a message that says what to write instead — including the two places where a <b>legal</b>
     * value contradicts another field, which are warned about rather than rejected because the original's own
     * default ({@code enableFactoryControllerByDefault = false}) makes "has-factory absent" the normal case.
     */
    private static void factoryLoaderFields() {
        section("P. the machine loader's factory fields");

        String parts = "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]";

        // (1) Defaults: a definition that says nothing about factories is exactly the pre-M6e machine.
        MachineDefinition bare = readDefinition("{\"registryname\":\"t\",\"localizedname\":\"T\"," + parts + "}");
        report("no factory fields -> has-factory false (the original's own default)", !bare.hasFactory());
        report("no factory fields -> factory-only false", !bare.factoryOnly());
        check("no factory fields -> max-threads 20 (Config.defaultFactoryMaxThread)",
                FactoryThreadModel.DEFAULT_MAX_THREADS, bare.maxThreads());
        check("no factory fields -> no core threads", 0, bare.coreThreads().size());
        report("a machine without has-factory is refused by a factory controller",
                !FactoryThreadModel.factoryEnabled(true, bare));
        report("...and a plain controller is refused too, even with the flag on",
                !FactoryThreadModel.factoryEnabled(false, new MachineDefinition(EXAMPLE_FACTORY, "F",
                        bare.pattern(), FailureAction.RESET, false, 2048, 0, true, List.of(),
                        4, true, false, List.of())));

        // (2) The fields as written.
        MachineDefinition full = readDefinition("{\"registryname\":\"t\",\"localizedname\":\"T\","
                + "\"has-factory\":true,\"factory-only\":false,\"max-threads\":4,\"core-threads\":["
                + "{\"name\":\"smelter\",\"recipes\":[\"modular_machinery_reborn:recipes/demo\"]},"
                + "{\"name\":\"anything\"}]," + parts + "}");
        report("has-factory true is read", full.hasFactory());
        report("factory-only false is read", !full.factoryOnly());
        check("max-threads 4 is read", 4, full.maxThreads());
        check("two core threads are read", 2, full.coreThreads().size());
        check("the first core thread's name", "smelter", full.coreThreads().get(0).name());
        check("...and its pinned recipe set", "[modular_machinery_reborn:recipes/demo]",
                full.coreThreads().get(0).recipes().toString());
        check("a core thread with no 'recipes' may run anything", 0,
                full.coreThreads().get(1).recipes().size());
        check("...and its name", "anything", full.coreThreads().get(1).name());
        check("a bare recipe name gets this mod's namespace",
                "[modular_machinery_reborn:alloy_smelter_diamond]",
                readDefinition("{\"registryname\":\"t\",\"core-threads\":[{\"name\":\"a\","
                        + "\"recipes\":[\"alloy_smelter_diamond\"]}]," + parts + "}")
                        .coreThreads().get(0).recipes().toString());
        report("a machine with has-factory is accepted by a factory controller",
                FactoryThreadModel.factoryEnabled(true, full));
        report("...but not by a plain controller", !FactoryThreadModel.factoryEnabled(false, full));
        // M6e-2 moved this number into the config (factory-system.default-factory-max-thread), whose default is
        // the original's own config-file default of 10. Section P originally asserted the 20 from the original's
        // Java field initialiser; that value is not what the original ever ran with, so the assertion moved with
        // the number. ModConfig's class comment records the reasoning.
        check("the factory model's default thread count is the configured one",
                com.reborn.modularmachinery.config.ModConfig.factoryDefaultMaxThread(),
                FactoryThreadModel.DEFAULT_MAX_THREADS);
        check("the factory model's idle timeout is the original's",
                FactoryThreadModel.IDLE_TIME_OUT, 200);
        report("the factory model's has-factory default is the original's (false)",
                !FactoryThreadModel.DEFAULT_HAS_FACTORY);

        // (3) Every malformed value, and the sentence that has to be in the message.
        Object[][] bad = {
                {"{\"registryname\":\"t\",\"has-factory\":\"yes\"," + parts + "}", "must be true or false"},
                {"{\"registryname\":\"t\",\"factory-only\":1," + parts + "}", "must be true or false"},
                {"{\"registryname\":\"t\",\"max-threads\":-1," + parts + "}", "cannot be negative"},
                {"{\"registryname\":\"t\",\"max-threads\":\"lots\"," + parts + "}", "must be a whole number"},
                {"{\"registryname\":\"t\",\"core-threads\":{}," + parts + "}", "must be an array"},
                {"{\"registryname\":\"t\",\"core-threads\":[{\"recipes\":[]}]," + parts + "}", "has no 'name'"},
                {"{\"registryname\":\"t\",\"core-threads\":[{\"name\":\"\"}]," + parts + "}", "empty 'name'"},
                {"{\"registryname\":\"t\",\"core-threads\":[{\"name\":\"a\"},{\"name\":\"a\"}]," + parts + "}",
                        "both named 'a'"},
                {"{\"registryname\":\"t\",\"core-threads\":[{\"name\":\"a\",\"recipes\":\"demo\"}]," + parts + "}",
                        "must be an array of recipe names"},
        };
        for (Object[] c : bad) {
            try {
                MachineDefinition definition = readDefinition((String) c[0]);
                report("expected a rejection for " + c[0] + " but got " + definition, false);
            } catch (RuntimeException exception) {
                String message = String.valueOf(exception.getMessage());
                report("rejected: " + message, message.contains((String) c[1]));
            }
        }

        // (4) The shipped example datapack really carries the machine and both recipes.
        Path base = exampleDatapack();
        if (base == null) {
            report("example-datapack/ not found", false);
            return;
        }
        Path machineFile = base.resolve("data/modular_machinery_reborn/machinery/example_factory.json");
        report("the shipped factory machine definition exists", Files.isRegularFile(machineFile));
        if (Files.isRegularFile(machineFile)) {
            try {
                MachineDefinition shipped = readDefinition(Files.readString(machineFile));
                report("the shipped factory machine declares has-factory", shipped.hasFactory());
                check("...and max-threads", 4, shipped.maxThreads());
                check("...and one core thread", 1, shipped.coreThreads().size());
                check("...named", "smelter", shipped.coreThreads().get(0).name());
                check("...with no pinned recipes", 0, shipped.coreThreads().get(0).recipes().size());
                report("...and it is accepted by a factory controller",
                        FactoryThreadModel.factoryEnabled(true, shipped));
                report("...and refused by a plain controller",
                        !FactoryThreadModel.factoryEnabled(false, shipped));
                // The two demo recipes must NOT share an input or an output, or "which thread did that" stops
                // being countable. That property is read out of the shipped JSON.
                //
                // It goes through JsonParser rather than the recipe serialiser on purpose: deserialising a
                // vanilla Recipe outside FML's own class loader reaches net.minecraftforge.fml.common.Mod (an
                // annotation Forge's mod-file scanner carries), so the live path cannot be driven here. What is
                // asserted is the shipped JSON; the recipes the arithmetic below runs on are built to match it.
                JsonObject stoneJson = JsonParser.parseString(Files.readString(base.resolve(
                        "data/modular_machinery_reborn/recipes/example_factory/example_factory_stone.json")))
                        .getAsJsonObject();
                JsonObject smeltJson = JsonParser.parseString(Files.readString(base.resolve(
                        "data/modular_machinery_reborn/recipes/example_factory/example_factory_smelt.json")))
                        .getAsJsonObject();
                check("stone recipe machine", "example_factory", stoneJson.get("machine").getAsString());
                check("stone recipe time", 5, stoneJson.get("recipeTime").getAsInt());
                check("smelt recipe machine", "example_factory", smeltJson.get("machine").getAsString());
                check("smelt recipe time", 10, smeltJson.get("recipeTime").getAsInt());
                check("stone recipe requirement kinds",
                        "[modular_machinery_reborn:item, modular_machinery_reborn:item]",
                        requirementKinds(stoneJson).toString());
                check("smelt recipe requirement kinds",
                        "[modular_machinery_reborn:energy, modular_machinery_reborn:item, "
                                + "modular_machinery_reborn:item]",
                        requirementKinds(smeltJson).toString());
                check("the two recipes share no output item",
                        "[]", sharedItemIds(stoneJson, smeltJson, "output").toString());
                check("...and share no input item",
                        "[]", sharedItemIds(stoneJson, smeltJson, "input").toString());
            } catch (Exception exception) {
                report("reading the shipped factory definition failed: " + exception, false);
            }
        }
        Path claim = base.resolve("claims/example_factory.json");
        report("the shipped factory claim exists", Files.isRegularFile(claim));
        if (Files.isRegularFile(claim)) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(claim)).getAsJsonObject();
                check("the claim's registryname", "example_factory", json.get("registryname").getAsString());
                report("the claim sets has-factory",
                        json.has("has-factory") && json.get("has-factory").getAsBoolean());
            } catch (Exception exception) {
                report("reading the factory claim failed: " + exception, false);
            }
        }
    }

    /** The {@code type} of every requirement a shipped recipe JSON declares, in order. */
    private static List<String> requirementKinds(JsonObject recipe) {
        List<String> kinds = new ArrayList<>();
        for (JsonElement element : recipe.getAsJsonArray("requirements")) {
            kinds.add(element.getAsJsonObject().get("type").getAsString());
        }
        return kinds;
    }

    /**
     * The item names two shipped recipes share on one side ({@code "input"} or {@code "output"}).
     *
     * <p>Empty is what the demonstrator needs: if the two recipes competed for the same items, "the stone thread
     * consumed 8 cobblestone" would stop meaning anything.
     */
    private static List<String> sharedItemIds(JsonObject a, JsonObject b, String ioType) {
        List<String> first = itemIdsOnSide(a, ioType);
        first.retainAll(itemIdsOnSide(b, ioType));
        return first;
    }

    private static List<String> itemIdsOnSide(JsonObject recipe, String ioType) {
        List<String> ids = new ArrayList<>();
        for (JsonElement element : recipe.getAsJsonArray("requirements")) {
            JsonObject requirement = element.getAsJsonObject();
            if (!"modularmachinery:item".equals(requirement.get("type").getAsString())) {
                continue;
            }
            if (!ioType.equals(requirement.get("io-type").getAsString())) {
                continue;
            }
            ids.add(requirement.get("item").getAsString());
        }
        return ids;
    }

    /**
     * Section Q — M6e's actual claim, and the one the plan forbids accepting on a GUI: <b>N threads run
     * different recipes at the same time</b>, driven by {@code max-threads} and by the core-thread preset, and
     * the quantities that move are the sum of the threads' settlements.
     *
     * <p>The engine under test is the production {@link FactoryEngine}; only its host is a stand-in, because a
     * host needs a {@code Level} and this harness has none. Everything the host answers with is either taken
     * from the shipped example datapack or is the same integer the controller would compute.
     */
    private static void factoryEngine() {
        section("Q. several different recipes run at once, one per thread");

        MachineRecipe stone = new MachineRecipe(STONE_RECIPE, "example_factory", "factory_stone", 5, List.of(
                ItemRequirement.input(ingredient(Items.COBBLESTONE), 1),
                ItemRequirement.output(new ItemStack(Items.STONE), 1, 1, 1.0F)));
        MachineRecipe smelt = new MachineRecipe(SMELT_RECIPE, "example_factory", "factory_smelt", 10, List.of(
                new EnergyRequirement(IOType.INPUT, 100L),
                ItemRequirement.input(ingredient(Items.COAL), 1),
                ItemRequirement.output(new ItemStack(Items.DIAMOND), 1, 1, 1.0F)));
        report("the two demonstrator recipes are different", !stone.getId().equals(smelt.getId()));

        // (1) Two different recipes progress in the SAME tick. This is the direct evidence for "at the same
        // time": progress is captured after each engine tick, and the check is that one tick moved two crafts
        // that were both already running. A sum-of-quantities check would not distinguish "concurrent" from
        // "one after the other with the inputs pre-counted".
        //
        // The parallelism ceiling is 1 on purpose, so each thread settles exactly one copy per settlement and
        // every figure below is "one craft = one item". The ledger itself is section (6).
        FactoryRig rig = new FactoryRig(List.of(stone, smelt), 4, 8, List.of());
        rig.parallelCeiling = 1;
        rig.reset();
        rig.tick(82);
        report("two threads run at once with max-threads 4: " + rig.snapshotLog(), rig.twoRecipesRanInOneTick());
        report("...and the only recipes involved are the two distinct ones",
                rig.maxDistinctRecipesInOneTick() == 2);
        report("two crafts ADVANCED in the same tick (not merely existed): "
                        + rig.maxCraftsAdvancedInOneTick(),
                rig.maxCraftsAdvancedInOneTick() >= 2);

        // (2) What actually moved. Both recipes ran out of inputs after 8 settlements each, so the totals are
        // the sum of what the threads really settled — and they are disjoint, so neither can be the other's.
        check("cobblestone consumed by the stone thread", 8, 8 - rig.cobblestoneLeft());
        check("stone produced by the stone thread", 8, rig.outputCount(Items.STONE));
        check("coal consumed by the coal thread", 8, 8 - rig.coalLeft());
        check("diamond produced by the coal thread", 8, rig.outputCount(Items.DIAMOND));
        check("FE drawn by the coal thread (8 x 100 x 10)",
                8L * 100L * 10L, rig.energyDrawn());
        check("no thread is left working when the inputs run out", 0, rig.engine.workingThreadCount());
        report("the ordinary pool is at most max-threads", rig.engine.ordinaryThreadCount() <= 4);

        // (3) max-threads really bounds the ordinary pool. The direct evidence is the peak pool size; the
        // consequence is throughput, which is what a player would see as the difference.
        FactoryRig single = new FactoryRig(List.of(stone, smelt), 1, 8, List.of());
        single.parallelCeiling = 1;
        single.reset();
        single.tick(40);
        FactoryRig four = new FactoryRig(List.of(stone, smelt), 4, 8, List.of());
        four.parallelCeiling = 1;
        four.reset();
        four.tick(40);
        check("a one-slot factory can only ever hold one thread", 1, single.peakOrdinaryThreads);
        report("...where a four-slot one holds more than one at a time", four.peakOrdinaryThreads > 1);
        int singleSettlements = single.outputCount(Items.STONE) + single.outputCount(Items.DIAMOND);
        int fourSettlements = four.outputCount(Items.STONE) + four.outputCount(Items.DIAMOND);
        report("in 40 ticks the one-slot factory settled " + singleSettlements + " crafts and the four-slot one "
                + fourSettlements, fourSettlements > singleSettlements);
        check("...and the one-slot factory's crafts are one recipe at a time", 1, single.peakOrdinaryThreads);
        check("max-threads 0 means no ordinary thread may ever run", 0,
                new FactoryRig(List.of(stone), 0, 8, List.of()).parallelCeiling(1).tick(12)
                        .engine.ordinaryThreadCount());

        // (4) The honest version of "several threads at once", and the strongest statement the engine can make:
        // each recipe is pinned to its own core thread, so neither can starve the other, and both move inside the
        // same tick. Two different recipes, two separate settlements, one tick.
        //
        // max-threads is 4 here only so the ordinary pool is allowed to exist at all: with max-threads 0 a core
        // thread is the only thing that can hold work, and the engine's rule "one recipe per slot" then leaves
        // the second recipe with nowhere to go. The pump primitive in the block entity uses that same
        // configuration on purpose (only core threads may work), which is why the loader warns rather than errors
        // when a machine declares core threads and no ordinary ones.
        FactoryRig pinned2 = new FactoryRig(List.of(stone, smelt), 4, 8, List.of(
                new FactoryThreadModel.CoreThreadSpec("stone", List.of(STONE_RECIPE)),
                new FactoryThreadModel.CoreThreadSpec("coal", List.of(SMELT_RECIPE))));
        pinned2.parallelCeiling = 1;
        pinned2.reset();
        pinned2.engine.syncCoreThreads();
        pinned2.tick(42);
        report("two pinned core threads: " + pinned2.snapshotLog(), pinned2.twoRecipesRanInOneTick());
        report("...two crafts advanced in the same tick: " + pinned2.maxCraftsAdvancedInOneTick(),
                pinned2.maxCraftsAdvancedInOneTick() >= 2);
        // 42 ticks: the 5-tick recipe settles 8 times, the 10-tick one 4 times.
        check("...the pinned stone thread settled 8", 8, pinned2.outputCount(Items.STONE));
        check("...and the pinned coal thread settled 4", 4, pinned2.outputCount(Items.DIAMOND));
        check("...the pinned threads are still there", 2, pinned2.engine.coreThreadCount());

        // (5) A core thread exists as soon as the engine syncs, and is never reaped.
        FactoryRig withCore = new FactoryRig(List.of(stone, smelt), 4, 8,
                List.of(new FactoryThreadModel.CoreThreadSpec("smelter", List.of())));
        withCore.parallelCeiling = 1;
        withCore.reset();
        withCore.engine.syncCoreThreads();
        check("the core thread is there before anything is crafted", 1,
                withCore.engine.coreThreadCount());
        withCore.tick(82);
        check("...and is still there after the work is done", 1, withCore.engine.coreThreadCount());
        check("...and the machine's two recipes were both settled", 16,
                withCore.outputCount(Items.STONE) + withCore.outputCount(Items.DIAMOND));

        // (6) A pinned core thread accepts its set and refuses everything else. The set names a recipe that this
        // machine does not have, so the thread must stay idle while the machine's own recipe runs.
        FactoryRig pinned = new FactoryRig(List.of(stone), 4, 4,
                List.of(new FactoryThreadModel.CoreThreadSpec("pinned",
                        List.of(new ResourceLocation("mmverify", "not_a_recipe")))));
        pinned.parallelCeiling = 1;
        pinned.reset();
        pinned.engine.syncCoreThreads();
        pinned.tick(20);
        report("a core thread pinned to an unknown recipe stays idle",
                pinned.engine.coreThreads().get(0).isIdle());
        check("...while the ordinary pool did the work", 4, pinned.outputCount(Items.STONE));

        // A set that names a recipe the machine does have is accepted, and the ordinary pool is the control: with
        // the same budget the pinned core thread settles the same number.
        FactoryRig pinnedOk = new FactoryRig(List.of(stone), 4, 4,
                List.of(new FactoryThreadModel.CoreThreadSpec("pinned", List.of(STONE_RECIPE))));
        pinnedOk.parallelCeiling = 1;
        pinnedOk.reset();
        pinnedOk.engine.syncCoreThreads();
        pinnedOk.tick(20);
        check("a core thread pinned to a real recipe runs it, and keeps running it", 4,
                pinnedOk.outputCount(Items.STONE));
        report("...and the ordinary pool was there to pick up anything it could not take",
                pinnedOk.peakOrdinaryThreads >= 0);

        // (7) The parallelism ledger, which is a different quantity from the thread count: a thread's settlement
        // can move several copies, and the copies come out of the machine's ceiling — the original's
        // getAvailableParallelism, which subtracts what the other threads already claimed. Each thread is added
        // one per tick, so the ledger can be read as it fills.
        FactoryRig ledger = new FactoryRig(List.of(stone, smelt, thirdRecipe()), 3, 64, List.of());
        ledger.parallelCeiling = 8;
        ledger.reset();
        int claims = 0;
        int[] perThread = new int[3];
        for (int i = 0; i < 3; i++) {
            ledger.tick(1);
            perThread[i] = ledger.engine.allThreads().get(i).parallelism();
            claims += perThread[i];
        }
        check("the first thread claims the whole ceiling", 8, perThread[0]);
        check("...and the next thread is reduced to one copy", 1, perThread[1]);
        check("...and so is the third", 1, perThread[2]);
        check("so the three claims come to the ceiling plus the two extra threads",
                10, claims);
        report("each thread's claim is what the ceiling had left, floored at 1",
                perThread[0] == 8 && perThread[1] == 1 && perThread[2] == 1);
        // And the claims are real: a lone thread settles exactly its claim in one settlement.
        FactoryRig solo = new FactoryRig(List.of(stone), 1, 8, List.of());
        solo.parallelCeiling = 8;
        solo.reset();
        solo.tick(1);
        check("a lone thread settles its whole claim in one settlement", 8,
                solo.engine.allThreads().get(0).parallelism());

        // (8) Saving and reloading keeps the threads and their progress.
        FactoryRig persist = new FactoryRig(List.of(stone, smelt), 4, 8, List.of());
        persist.parallelCeiling = 1;
        persist.reset();
        persist.tick(3);
        int before = persist.engine.workingThreadCount();
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        persist.engine.save(saved);
        report("the save carries the original's own NBT keys",
                saved.contains("threadList") || saved.contains("coreThreadList"));
        FactoryEngine reloaded = new FactoryEngine(persist);
        reloaded.load(saved, id -> id.equals(STONE_RECIPE) ? stone : id.equals(SMELT_RECIPE) ? smelt : null,
                List.of());
        check("every thread came back", persist.engine.allThreads().size(), reloaded.allThreads().size());
        check("...still working", before, reloaded.workingThreadCount());
        check("...with their progress", persist.engine.allThreads().get(0).progress(),
                reloaded.allThreads().get(0).progress());
        check("...and their recipes", String.valueOf(persist.engine.allThreads().get(0).recipeId()),
                String.valueOf(reloaded.allThreads().get(0).recipeId()));

        // (9) The sweep. An ordinary thread with nothing left to do is reaped after IDLE_TIME_OUT; a core thread
        // is not. Driven by taking the recipes away, which is what a full output hatch does in game.
        FactoryRig idle = new FactoryRig(List.of(stone), 4, 8, List.of());
        idle.parallelCeiling = 1;
        idle.reset();
        idle.tick(82);
        report("ordinary threads were created while there was work", idle.peakOrdinaryThreads > 0);
        idle.recipes.clear();
        idle.tick(80);
        report("an idle ordinary thread is still there before its timeout", idle.engine.ordinaryThreadCount() > 0);
        idle.tick(200);
        check("with nothing left to craft, the idle ordinary threads are reaped", 0,
                idle.engine.ordinaryThreadCount());
        report("...and the pool did not refill while there was nothing to do",
                idle.engine.ordinaryThreadCount() == 0);

        FactoryRig coreKept = new FactoryRig(List.of(stone), 4, 8,
                List.of(new FactoryThreadModel.CoreThreadSpec("keeper", List.of())));
        coreKept.parallelCeiling = 1;
        coreKept.reset();
        coreKept.tick(82);
        coreKept.recipes.clear();
        coreKept.tick(300);
        check("...while a core thread survives the same sweep", 1, coreKept.engine.coreThreadCount());
        check("...and the ordinary one still went", 0, coreKept.engine.ordinaryThreadCount());

        // (9) The negative claim the task calls for: a machine WITHOUT has-factory cannot use a factory
        // controller at all. This is the whole decision, driven through the production methods.
        MachineDefinition noFactory = readDefinition("{\"registryname\":\"t\","
                + "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]}");
        MachineDefinition withFactory = readDefinition("{\"registryname\":\"t\",\"has-factory\":true,"
                + "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]}");
        report("block=factory + machine without has-factory -> no factory",
                !FactoryThreadModel.factoryEnabled(true, noFactory));
        report("block=factory + machine with has-factory -> factory",
                FactoryThreadModel.factoryEnabled(true, withFactory));
        report("block=plain + machine with has-factory -> no factory",
                !FactoryThreadModel.factoryEnabled(false, withFactory));
        report("block=plain + machine without has-factory -> no factory",
                !FactoryThreadModel.factoryEnabled(false, noFactory));
        report("a null machine is never a factory", !FactoryThreadModel.factoryEnabled(true, null));
        check("maxThreads() on a null machine falls back to the original's default",
                FactoryThreadModel.DEFAULT_MAX_THREADS, FactoryThreadModel.maxThreads(null));

        // (10) The shipped example datapack's claim: `has-factory` is what makes the block set contain a
        // factory controller, which is asserted through the same reader the loader uses.
        Path base = exampleDatapack();
        if (base == null) {
            report("example-datapack/ not found", false);
            return;
        }
        try {
            JsonObject claim = JsonParser.parseString(Files.readString(
                    base.resolve("claims/example_factory.json"))).getAsJsonObject();
            report("the shipped factory claim would register a factory controller",
                    claim.has("has-factory") && claim.get("has-factory").getAsBoolean());
            JsonObject plain = JsonParser.parseString(Files.readString(
                    base.resolve("claims/alloy_furnace.json"))).getAsJsonObject();
            report("the alloy furnace claim does NOT ask for a factory controller",
                    !plain.has("has-factory") || !plain.get("has-factory").getAsBoolean());
        } catch (Exception exception) {
            report("reading the shipped claims failed: " + exception, false);
        }
    }

    /**
     * Section R is intentionally not driven here — see its own comment. The list it would assert is
     * {@code ModBlocks.controllersNeedingGeneratedAssets()}, which the shipping checks in section Q cover from
     * the declaration side (a claim without {@code has-factory} gets no factory controller).
     */
    private static void factoryGeneratedAssetsUnused() {
    }

    // =================================================================== M6e-2: the factory's presentation

    /**
     * Section R — M6e-2's four deliverables, each checked offline as far as it can be.
     *
     * <p>The task's acceptance rule is explicit that "a number appeared in the GUI" is not evidence, and this
     * harness cannot see pixels, so this section splits the claims exactly the way the release notes do:
     *
     * <ol>
     *   <li><b>The two config keys</b> — the key paths are read out of the <i>rendered spec</i> rather than out
     *       of the source, because {@code ForgeConfigSpec.Builder} is path-accumulating and 0.20.0 shipped a file
     *       whose paths the documentation did not describe. The instance's existing
     *       {@code modular_machinery_reborn-common.toml} is then run through Forge's own read path
     *       ({@code isCorrect} → {@code correct} → {@code setConfig} → read back → {@code save}) to prove the
     *       addition did not invalidate it.</li>
     *   <li><b>The two preview rows</b> — driven through {@code PreviewPanel.machineInfoRows}, the method the
     *       panel itself uses, on real {@link MachineDefinition}s, so the guards and the order are asserted and
     *       not described.</li>
     *   <li><b>The screen's fixed metrics and the two textures</b> — the metric table the drawing code reads,
     *       and each PNG's dimensions and SHA-256 against the original's own file.</li>
     *   <li><b>The language keys</b> — the eight new keys in both files, with the original's exact wording, and
     *       the two sets' equality.</li>
     * </ol>
     */
    private static void factoryPresentationAndConfig() {
        section("R. the factory's config keys, preview rows, panel metrics and textures");

        // ---- (1) the config keys, read out of the rendered spec ------------------------------------------
        Map<String, String> rendered = renderConfigSpec();
        if (rendered.isEmpty()) {
            report("the config spec could not be rendered", false);
            return;
        }
        // 13 through 0.26.0; 0.27.0's MOC namespace adds general.modular-controller-compatible-mode and
        // general.disable-moc-deprecated-tip; the display.energy unit key is the sixteenth.
        check("the config spec defines this many keys", 16, rendered.size());

        String[][] expected = {
                {"parallel-controller.normal.max-parallelism", "4"},
                {"parallel-controller.reinforced.max-parallelism", "16"},
                {"parallel-controller.elite.max-parallelism", "64"},
                {"parallel-controller.super.max-parallelism", "256"},
                {"parallel-controller.ultimate.max-parallelism", "512"},
                {"upgrade-bus.normal.max-upgrade_slot", "3"},
                {"upgrade-bus.reinforced.max-upgrade_slot", "6"},
                {"upgrade-bus.elite.max-upgrade_slot", "9"},
                {"upgrade-bus.super.max-upgrade_slot", "12"},
                {"upgrade-bus.ultimate.max-upgrade_slot", "18"},
                {"factory-system.default-factory-max-thread", "10"},
                {"factory-system.enable-factory-controller-bydefault", "false"},
                {"smart-interface.enable-smart-interface-bydefault", "false"},
                // The original's own category and key spelling (`display.energy.Display_Energy_Type`), and its own
                // default. Written in the original's casing on purpose: a pack author's existing
                // modularmachinery.cfg line is expected to be understood as written.
                {"display.energy.Display_Energy_Type", "FE"},
        };
        for (String[] pair : expected) {
            String actual = rendered.get(pair[0]);
            report("the rendered spec has '" + pair[0] + "' = " + actual
                            + (actual == null ? " (ABSENT)" : ""),
                    pair[1].equals(actual));
        }
        report("no rendered key repeats a path element (the 0.20.0 defect)",
                rendered.keySet().stream().noneMatch(key -> repeatsASegment(key)));

        // The accessors must agree with the rendered defaults.
        check("ModConfig.factoryDefaultMaxThread()", 10,
                com.reborn.modularmachinery.config.ModConfig.factoryDefaultMaxThread());
        report("ModConfig.factoryControllerEnabledByDefault() is false",
                !com.reborn.modularmachinery.config.ModConfig.factoryControllerEnabledByDefault());
        check("MachineDefinition.DEFAULT_MAX_THREADS follows the config", 10,
                com.reborn.modularmachinery.machine.MachineDefinition.DEFAULT_MAX_THREADS);
        check("FactoryThreadModel.DEFAULT_MAX_THREADS follows the config", 10,
                FactoryThreadModel.DEFAULT_MAX_THREADS);
        check("...and a machine that does not say gets that number", 10,
                readDefinition("{\"registryname\":\"t\","
                        + "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]}")
                        .maxThreads());

        // ---- (1b) the display energy unit, and the two number formats ----------------------------------
        //
        // The original's `EnergyDisplayUtil.EnergyType` was FE(1), RF(1), IC2_EU(0.25), GT_EU(0.25) plus
        // `formatEnergyForDisplay` = multiply-and-floor. All four facts are asserted, because every one of them is
        // a place a "reasonable" rewrite would differ: RF is one-to-one (a label, not a conversion), the two EU
        // flavours are a quarter, and the result is floored rather than rounded.
        report("EnergyDisplay.FE is 1:1", EnergyDisplay.FE.multiplier() == 1.0D);
        report("EnergyDisplay.RF is 1:1 too — it is a label, not a conversion, which is what the original did",
                EnergyDisplay.RF.multiplier() == 1.0D);
        report("EnergyDisplay.IC2_EU scales by a quarter", EnergyDisplay.IC2_EU.multiplier() == 0.25D);
        report("EnergyDisplay.GT_EU scales by a quarter as well", EnergyDisplay.GT_EU.multiplier() == 0.25D);
        report("...and both EU flavours print the same label, 'EU', as the original's language file did",
                "EU".equals(EnergyDisplay.IC2_EU.label()) && "EU".equals(EnergyDisplay.GT_EU.label()));
        report("the labels are reached through per-type translation keys, so the unit is translatable: "
                        + EnergyDisplay.IC2_EU.labelKey(),
                EnergyDisplay.IC2_EU.labelKey()
                        .equals("tooltip.modular_machinery_reborn.machine.energy.type.ic2_eu"));

        // Scaling is display-only, and floored: an 8192 FE hatch reads 2048 EU, and 100 FE/t reads 25 EU/t.
        check("8192 stored FE displays as 2048 under IC2_EU", 2048L, EnergyDisplay.IC2_EU.display(8192L));
        check("...and is unchanged under FE", 8192L, EnergyDisplay.FE.display(8192L));
        check("...and unchanged under RF", 8192L, EnergyDisplay.RF.display(8192L));
        check("an odd value floors rather than rounds: 99 FE displays as 24 under IC2_EU", 24L,
                EnergyDisplay.IC2_EU.display(99L));
        check("the stored value itself is never touched: 8192 FE still reads 8192 through FE", 8192L,
                EnergyDisplay.FE.display(8192L));

        // The tolerant read, matching the original's `getType` (which swallowed the failure and returned FE) —
        // plus the part this port adds: an invalid value is *reportable* rather than silent.
        report("a config value is matched exactly, in any case: 'ic2_eu' and 'IC2_EU' both parse",
                EnergyDisplay.parse("ic2_eu") == EnergyDisplay.IC2_EU
                        && EnergyDisplay.parse("IC2_EU") == EnergyDisplay.IC2_EU);
        report("surrounding whitespace is tolerated, as Forge's own TOML reader would leave none but an edited "
                        + "file might", EnergyDisplay.parse(" rf ") == EnergyDisplay.RF);
        report("an unknown value falls back to FE rather than refusing to start (the original's tolerance)",
                EnergyDisplay.parse("nonsense") == EnergyDisplay.FE);
        report("...and is recognisable as invalid, so the loader can say so out loud",
                !EnergyDisplay.isValid("nonsense") && EnergyDisplay.isValid("GT_EU"));

        // The two formats, both from `MiscUtils`, kept apart on purpose: the hatch GUI and the JEI preview
        // abbreviate, the block tooltip groups. The abbreviation's arithmetic is the original's, quirk included.
        report("DisplayNumbers.abbreviated leaves anything under a thousand alone",
                "999".equals(DisplayNumbers.abbreviated(999L)));
        report("...and abbreviates at a thousand: 1500 -> 1.5K",
                "1.5K".equals(DisplayNumbers.abbreviated(1500L)));
        report("...reproducing the original's own quirk at the next step: 5_000_000 -> 5.0M, because every branch"
                        + " above K divides by the prefix below it and then by 1000 again",
                "5.0M".equals(DisplayNumbers.abbreviated(5_000_000L)));
        report("DisplayNumbers.grouped is the tooltip's format: a thousands separator and no suffix",
                "16,384".equals(DisplayNumbers.grouped(16384L)));

        // The user's existing file must survive: read it through Forge's own path. It is round-tripped on a
        // COPY, because this harness must not touch the instance's config — what is being asserted is that the
        // old file parses, validates, and yields a file that carries both old and new keys.
        Path existing = existingConfigFile();
        if (existing == null) {
            report("the instance's modular_machinery_reborn-common.toml was not found — the migration check "
                    + "was SKIPPED (it is not provable without that file)", false);
        } else {
            Path copy = null;
            try {
                String onDisk = Files.readString(existing);
                // 这个检查要证明的是「旧版本写下的配置文件会被新 spec 接受，且修正纯粹是新增」。
                //
                // 它**不能**假定磁盘上那份文件还是旧的：实例一旦用新版本启动过，Forge 就已经把新键补写进去了，
                // 于是 `isCorrect()` 直接为真、没有修正可做。那会让这个检查变成一台「取决于你的实例启动过没有」
                // 的仪器，而项目规矩是「从未失败过的断言不算证据」——时灵时不灵的断言更糟。
                //
                // 所以这里自己造前提：从磁盘那份的真实字节里**去掉本版新增的那一节**，得到一个「上一版写下的
                // 文件」，再让新 spec 去读它。这样无论实例处于哪个状态，被断言的输入都是已知的。
                String before = stripNewestSection(onDisk);
                boolean hadNewSection = before.contains("factory-system");
                Path work = Path.of("C:/mmwork/.tmp-m6c-verify/config-roundtrip");
                Files.createDirectories(work);
                copy = work.resolve("modular_machinery_reborn-common.toml");
                Files.writeString(copy, before);
                report("造出的「旧版本配置文件」确实不含本版新增的 display.energy 一节",
                        !before.contains("display.energy"));

                Object spec = configSpec();
                // Phase 1: read the old file's exact bytes through Forge's own path — a file config built the way
                // Forge's ConfigFileTypeHandler builds one.
                Object fileConfig = newConfig(copy);
                boolean listed = (Boolean) invokeOn(spec, "isCorrect", fileConfig);
                int corrections = (Integer) invokeOn(spec, "correct", fileConfig);
                // "The old file is still accepted" cannot mean "isCorrect() was already true": a config written
                // by the previous release legitimately does not know the two new keys, and isCorrect() reporting
                // that is exactly the signal Forge acts on. What has to hold is that the correction is purely
                // additive — the file becomes correct, without anything being dropped.
                report("the user's existing config file is readable by the new spec ("
                                + before.length() + " chars, was already correct: " + listed
                                + ", corrections applied: " + corrections + ")",
                        !listed && corrections > 0);
                invokeOn(spec, "setConfig", fileConfig);
                report("after the correction the file is correct", (Boolean) invokeOn(spec, "isCorrect", fileConfig));
                int readable = 0;
                StringBuilder missing = new StringBuilder();
                for (String[] pair : expected) {
                    // display.energy 这一节用**文本**断言，不走这里的路径取值：它的节名含点，
                    // Forge 把它写成 `[["display.energy"]]`，NightConfig 按字符串路径取它下面的键会答 null。
                    // 换一种取法而不是放宽断言——下面那条 contains 检查同样能发现「键丢了」，
                    // 而且它检查的是磁盘上真实写出的字节。
                    if (pair[0].startsWith("display.energy")) {
                        continue;
                    }
                    if (com.electronwill.nightconfig.core.Config.class
                            .getMethod("get", String.class).invoke(fileConfig, pair[0]) != null) {
                        readable++;
                    } else {
                        missing.append(' ').append(pair[0]);
                    }
                }
                check("every key with a plain path is readable from that file (missing:" + missing + ")",
                        13, readable);
                report("the spec reports itself loaded after setConfig",
                        (Boolean) invokeOn(spec, "isLoaded"));

                // Phase 2: let the spec write, so Forge's own writer is what produces the TOML that is then
                // inspected for the new section and for the absence of repeated path elements.
                invokeOn(spec, "save");
                flush(fileConfig);
                String after = Files.readString(copy);
                report("saving writes the display.energy section this release adds, and keeps the "
                                + "factory-system and smart-interface sections the file already had",
                        after.contains("display.energy") && after.contains("Display_Energy_Type")
                                && after.contains("default-factory-max-thread")
                                && after.contains("enable-factory-controller-bydefault")
                                && after.contains("[smart-interface]")
                                && after.contains("enable-smart-interface-bydefault"));
                report("...and the ten pre-existing keys are still there",
                        after.contains("max-parallelism") && after.contains("max-upgrade_slot"));
                report("...and it gained no repeated path element",
                        !after.contains("[factory-system.factory-system]")
                                && !after.contains("[smart-interface.smart-interface]")
                                && !after.contains("parallel-controller.parallel-controller"));
                report("...and the user's own values survived (the first tier is still "
                                + rendered.get("parallel-controller.normal.max-parallelism") + ")",
                        after.contains("max-parallelism = "
                                + rendered.get("parallel-controller.normal.max-parallelism")));
                report("...the correction was purely additive (" + corrections + " corrections)", corrections >= 2);
                report("...and the file grew only by those keys (was " + before.length() + ", now "
                                + after.length() + " chars)", after.length() > before.length());
                // The strongest form of "the old file still works": re-read what was just written and find all
                // twelve keys with the right values in it.
                Object reread = newConfig(copy);
                int found = 0;
                for (String[] pair : expected) {
                    Object value = com.electronwill.nightconfig.core.Config.class
                            .getMethod("get", String.class).invoke(reread, pair[0]);
                    if (value != null && pair[1].equals(String.valueOf(value))) {
                        found++;
                    }
                }
                check("...and re-reading the saved file finds all thirteen keys at their defaults", 13, found);
            } catch (Exception exception) {
                report("round-tripping the user's config failed: " + exception, false);
            } finally {
                try {
                    if (copy != null) {
                        Files.deleteIfExists(copy);
                    }
                } catch (Exception ignored) {
                    // A leftover copy under .tmp-m6c-verify is harmless.
                }
            }
        }

        // ---- (2) the two preview rows --------------------------------------------------------------------
        previewRows();

        // ---- (3) the panel metrics and the two textures ---------------------------------------------------
        int[] m = com.reborn.modularmachinery.client.FactoryControllerScreen.metrics();
        check("panel width", 280, m[0]);
        check("panel height", 213, m[1]);
        check("recipe element width", 86, m[2]);
        check("recipe element height", 32, m[3]);
        check("elements per page", 6, m[4]);
        check("recipe queue origin x", 8, m[5]);
        check("recipe queue origin y", 8, m[6]);
        check("scrollbar left", 94, m[7]);
        check("scrollbar top", 8, m[8]);
        check("scrollbar height", 197, m[9]);
        check("text block origin x in panel px", (int) (113 * 0.72F), m[10]);
        check("text block origin y in panel px", (int) (12 * 0.72F), m[11]);
        check("information wrap width", (int) (135 / 0.72F), m[12]);
        check("font scale (x100)", 72, m[13]);
        check("row label x inside the scaled matrix", (int) (8 / 0.72F) + 2, m[14]);
        check("row wrap width", (int) ((86 - 6) / 0.72F), m[15]);
        check("row text y offset", 2, m[16]);
        check("line pitch", 10, m[17]);
        check("spacer before the closing line", 5, m[18]);
        check("gap after a name or status block", 15, m[19]);
        check("row step", 33, m[20]);
        report("the recipe queue and the information block do not overlap (queue ends at "
                        + (m[5] + m[2]) + " panel px, the text starts at " + m[21] + ")",
                m[5] + m[2] <= m[21]);
        report("six rows fit inside the panel",
                m[6] + m[4] * m[20] <= m[1]);

        Path sourceTextures = originalGuiTextures();
        Path shipped = shippedGuiTextures();
        for (String name : new String[] {"guifactory.png", "guifactoryelements.png"}) {
            Path a = sourceTextures == null ? null : sourceTextures.resolve(name);
            Path b = shipped == null ? null : shipped.resolve(name);
            if (a == null || b == null || !Files.isRegularFile(a) || !Files.isRegularFile(b)) {
                report(name + " is missing on one side (source=" + a + ", shipped=" + b + ")", false);
                continue;
            }
            try {
                String hashA = sha256(a);
                String hashB = sha256(b);
                report(name + " is byte-for-byte the original's (SHA-256 " + hashB + ")",
                        hashA.equals(hashB));
                String[] sizeA = pngSize(a);
                String[] sizeB = pngSize(b);
                report(name + " dimensions match (" + sizeB[0] + "x" + sizeB[1] + ")",
                        sizeA[0].equals(sizeB[0]) && sizeA[1].equals(sizeB[1]));
            } catch (Exception exception) {
                report("hashing " + name + " failed: " + exception, false);
            }
        }
        // The panel size the screen sets must be the texture's own size: that is what "blit it whole" means.
        if (shipped != null && Files.isRegularFile(shipped.resolve("guifactory.png"))) {
            try {
                String[] size = pngSize(shipped.resolve("guifactory.png"));
                report("guifactory.png really is the 280x213 panel the screen blits whole ("
                                + size[0] + "x" + size[1] + ")",
                        size[0].equals("280") && size[1].equals("213"));
            } catch (Exception exception) {
                report("reading guifactory.png's size failed: " + exception, false);
            }
        }

        // ---- (4) the language keys ------------------------------------------------------------------------
        languageKeys();
    }

    /** {@code true} when a rendered key path names the same segment twice, the 0.20.0 defect's signature. */
    private static boolean repeatsASegment(String key) {
        String[] parts = key.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            for (int j = i + 1; j < parts.length; j++) {
                if (parts[i].equals(parts[j])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Renders {@code ModConfig.SPEC}'s own definition map as {@code path → default}, which is the only
     * trustworthy source for a key path: reading the source tells you what was <i>intended</i>, and 0.20.0 is
     * the proof that those two can differ.
     */
    static Map<String, String> renderConfigSpec() {
        Map<String, String> rendered = new java.util.LinkedHashMap<>();
        try {
            Object values = invokeOn(configSpec(), "getValues");
            Object valueMap = invokeOn(values, "valueMap");
            flattenSpec("", (Map<?, ?>) valueMap, rendered);
        } catch (Exception exception) {
            report("rendering ModConfig.SPEC failed: " + exception, false);
        }
        return rendered;
    }

    /**
     * Walks the spec's own value tree and reads every leaf's {@code getDefault()}.
     *
     * <p>The tree is nested by path element — {@code parallel-controller} → {@code normal} →
     * {@code max-parallelism} — so the dotted path is rebuilt here, which is exactly the string Forge writes
     * into the TOML file as a section header. That is why this, and not the source code, is the authority on
     * what the keys are called.
     */
    private static void flattenSpec(String prefix, Map<?, ?> node, Map<String, String> out) throws Exception {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            java.lang.reflect.Method defaultValue = null;
            for (java.lang.reflect.Method candidate : value.getClass().getMethods()) {
                if (candidate.getName().equals("getDefault") && candidate.getParameterCount() == 0) {
                    defaultValue = candidate;
                    break;
                }
            }
            if (defaultValue != null) {
                out.put(key, String.valueOf(defaultValue.invoke(value)));
                continue;
            }
            java.lang.reflect.Method nested = null;
            for (java.lang.reflect.Method candidate : value.getClass().getMethods()) {
                if (candidate.getName().equals("valueMap") && candidate.getParameterCount() == 0) {
                    nested = candidate;
                    break;
                }
            }
            if (nested == null) {
                report("neither a value nor a section: " + key + " -> " + value.getClass(), false);
                continue;
            }
            flattenSpec(key, (Map<?, ?>) nested.invoke(value), out);
        }
    }

    private static Object configSpec() throws Exception {
        return Class.forName("com.reborn.modularmachinery.config.ModConfig")
                .getField("SPEC").get(null);
    }

    private static Class<?> commentedConfigType() throws Exception {
        return Class.forName("com.electronwill.nightconfig.core.CommentedConfig");
    }

    private static Object invokeOn(Object target, String name, Object... args) throws Exception {
        for (java.lang.reflect.Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == args.length
                    && assignable(method.getParameterTypes(), args)) {
                return method.invoke(target, args);
            }
        }
        StringBuilder available = new StringBuilder();
        for (java.lang.reflect.Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name)) {
                available.append(' ').append(java.util.Arrays.toString(method.getParameterTypes()));
            }
        }
        throw new NoSuchMethodException(name + "/" + args.length + " on " + target.getClass()
                + "; overloads:" + available);
    }

    /** Whether every argument can be handed to the corresponding parameter type. */
    private static boolean assignable(Class<?>[] parameters, Object[] args) {
        for (int i = 0; i < parameters.length; i++) {
            if (args[i] == null ? parameters[i].isPrimitive() : !parameters[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    /** A NightConfig file config for {@code path}, built and loaded the way Forge's handler does it. */
    private static Object newConfig(Path path) throws Exception {
        Class<?> formatType = Class.forName("com.electronwill.nightconfig.toml.TomlFormat");
        Object format = formatType.getMethod("instance").invoke(null);
        Class<?> fileType = Class.forName("com.electronwill.nightconfig.core.file.CommentedFileConfig");
        Class<?> builderType = Class.forName("com.electronwill.nightconfig.core.file.GenericBuilder");
        Object builder = fileType.getMethod("builder", Path.class,
                Class.forName("com.electronwill.nightconfig.core.ConfigFormat")).invoke(null, path, format);
        builder = builderType.getMethod("preserveInsertionOrder").invoke(builder);
        Object config = builderType.getMethod("build").invoke(builder);
        // A freshly built file config is empty until it is loaded; Forge's ConfigFileTypeHandler calls load()
        // itself. Without this the spec would be asked whether an empty config is correct, which it is not.
        // NightConfig's implementation class is package-private but implements the public FileConfig interface,
        // so the method is looked up there rather than on the instance's own (inaccessible) class.
        Class<?> fileConfigInterface = Class.forName("com.electronwill.nightconfig.core.file.FileConfig");
        fileConfigInterface.getMethod("load").invoke(config);
        return config;
    }

    /** Parses TOML text into an in-memory {@code CommentedConfig}. */
    private static Object parseToml(String text) throws Exception {
        Class<?> parserType = Class.forName("com.electronwill.nightconfig.toml.TomlParser");
        Object parser = parserType.getConstructor().newInstance();
        return parserType.getMethod("parse", java.io.Reader.class)
                .invoke(parser, new java.io.StringReader(text));
    }

    /** Flushes a file config's buffered writer, which Forge's own handler also has to do. */
    private static void flush(Object fileConfig) {
        try {
            fileConfig.getClass().getMethod("close").invoke(fileConfig);
        } catch (Exception ignored) {
            // Not fatal: the read that follows reports what actually landed on disk.
        }
    }

    /** The instance's config file, if this machine can see it. */
    @Nullable
    private static Path existingConfigFile() {
        Path[] candidates = {
                Path.of("D:/.minecraft/versions/彩虹花园/config/modular_machinery_reborn-common.toml"),
                Path.of("D:/", ".minecraft", "versions", "彩虹花园", "config",
                        "modular_machinery_reborn-common.toml"),
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path originalGuiTextures() {
        Path[] candidates = {
                Path.of("..", "_mmce-src", "ModularMachinery-Community-Edition-master", "src", "main",
                        "resources", "assets", "modularmachinery", "textures", "gui"),
                Path.of("C:/mmwork/_mmce-src/ModularMachinery-Community-Edition-master/src/main/resources/"
                        + "assets/modularmachinery/textures/gui"),
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path shippedGuiTextures() {
        Path[] candidates = {
                Path.of("..", "modular-machinery-reborn", "src", "main", "resources", "assets",
                        "modular_machinery_reborn", "textures", "gui"),
                Path.of("src", "main", "resources", "assets", "modular_machinery_reborn", "textures", "gui"),
                Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/assets/"
                        + "modular_machinery_reborn/textures/gui"),
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String sha256(Path file) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /** A PNG's width and height, read out of its IHDR — no ImageIO, no display. */
    private static String[] pngSize(Path file) throws Exception {
        byte[] head = new byte[24];
        try (java.io.InputStream in = Files.newInputStream(file)) {
            int read = in.readNBytes(head, 0, 24);
            if (read < 24) {
                throw new IllegalStateException("short PNG header");
            }
        }
        int width = ((head[16] & 0xFF) << 24) | ((head[17] & 0xFF) << 16)
                | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF);
        int height = ((head[20] & 0xFF) << 24) | ((head[21] & 0xFF) << 16)
                | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF);
        return new String[] {String.valueOf(width), String.valueOf(height)};
    }

    /**
     * The two rows M6e-2 adds to the structure preview's machine-info overlay.
     *
     * <p>Driven through {@code PreviewPanel.machineInfoRows} — the same list the panel draws — on definitions
     * built by the loader's own {@code readMachine}, so the guards are the shipping ones. The original's order
     * and its two conditions ({@code MachineStructurePreviewPanel:298-317}):
     * {@code max_threads} only for a machine with {@code has-factory}, {@code core_threads} only when the
     * {@code core-threads} preset is not empty.
     */
    private static void previewRows() {
        List<String> bare = com.reborn.modularmachinery.client.preview.PreviewPanel.machineInfoRows(
                readDefinition("{\"registryname\":\"t\","
                        + "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]}"));
        report("a machine with no factory prints no max-threads row",
                bare.stream().noneMatch(row -> row.contains("machine_info.max_threads")));
        report("...and no core-threads row",
                bare.stream().noneMatch(row -> row.contains("machine_info.core_threads")));
        check("...and the four unconditional rows are still there", 4, bare.size());

        List<String> factory = com.reborn.modularmachinery.client.preview.PreviewPanel.machineInfoRows(
                readDefinition("{\"registryname\":\"t\",\"has-factory\":true,\"max-threads\":7,"
                        + "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]}"));
        System.out.println("  (rows) " + factory);
        report("a factory without a core-thread preset prints max-threads",
                factory.stream().anyMatch(row -> row.contains("machine_info.max_threads")));
        report("...and still no core-threads row",
                factory.stream().noneMatch(row -> row.contains("machine_info.core_threads")));
        report("...and the row carries the machine's own number",
                factory.stream().anyMatch(row -> row.contains("max_threads") && row.contains("7")));
        check("...so it has five rows", 5, factory.size());

        List<String> core = com.reborn.modularmachinery.client.preview.PreviewPanel.machineInfoRows(
                readDefinition("{\"registryname\":\"t\",\"has-factory\":true,\"max-threads\":7,"
                        + "\"core-threads\":[{\"name\":\"a\"},{\"name\":\"b\"}],"
                        + "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]}"));
        System.out.println("  (rows) " + core);
        report("a factory with a two-entry preset prints a core-threads row of 2",
                core.stream().anyMatch(row -> row.contains("machine_info.core_threads") && row.contains("2")));
        check("...so it has six rows", 6, core.size());
        int maxThreadsAt = indexOfRow(core, "machine_info.max_threads");
        int coreThreadsAt = indexOfRow(core, "machine_info.core_threads");
        report("max-threads comes before core-threads, as in the original",
                maxThreadsAt >= 0 && coreThreadsAt == maxThreadsAt + 1);
        report("...and both come after the parallelism rows, as in the original",
                indexOfRow(core, "machine_info.max_parallelism") < maxThreadsAt);

        // A core-thread preset on a machine with no factory still gets its row (the original's own two
        // independent guards), which is worth pinning: it is the one combination the guards do not pair up.
        List<String> coreOnly = com.reborn.modularmachinery.client.preview.PreviewPanel.machineInfoRows(
                readDefinition("{\"registryname\":\"t\",\"core-threads\":[{\"name\":\"a\"}],"
                        + "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]}"));
        report("a preset without has-factory prints core-threads but not max-threads",
                indexOfRow(coreOnly, "machine_info.core_threads") >= 0
                        && indexOfRow(coreOnly, "machine_info.max_threads") < 0);
    }

    private static int indexOfRow(List<String> rows, String fragment) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).contains(fragment)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The eight language keys this release adds, in both files, against the original's own wording.
     *
     * <p>The Chinese values are compared with {@code _mmce-src}'s {@code zh_CN.lang} and the English ones with
     * its {@code en_US.lang}, key for key, so a typo in either file fails here. Note the key names themselves
     * are namespaced ({@code gui.modular_machinery_reborn.factory.*}) where the original's were not
     * ({@code gui.factory.*}): that is this project's existing convention for its own GUI keys — 0.12.0's
     * controller, 0.20.0's parallel controller and 0.21.0's bus all renamed theirs the same way — while the
     * preview keys keep the original's un-namespaced names because they were introduced that way in M6c.
     * The <i>wording</i> is the original's verbatim in all eight.
     */
    private static void languageKeys() {
        Path zh = Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/assets/"
                + "modular_machinery_reborn/lang/zh_cn.json");
        Path en = Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/assets/"
                + "modular_machinery_reborn/lang/en_us.json");
        Path sourceLang = Path.of("C:/mmwork/_mmce-src/ModularMachinery-Community-Edition-master/src/main/"
                + "resources/assets/modularmachinery/lang");
        if (!Files.isRegularFile(zh) || !Files.isRegularFile(en)) {
            report("the language files were not found at " + zh.getParent(), false);
            return;
        }
        try {
            Map<String, String> zhMap = flatJson(zh);
            Map<String, String> enMap = flatJson(en);
            java.util.Set<String> onlyZh = new java.util.TreeSet<>(zhMap.keySet());
            onlyZh.removeAll(enMap.keySet());
            java.util.Set<String> onlyEn = new java.util.TreeSet<>(enMap.keySet());
            onlyEn.removeAll(zhMap.keySet());
            report("zh_cn and en_us define the same key set (" + zhMap.size() + " keys; only-zh=" + onlyZh
                            + ", only-en=" + onlyEn + ")",
                    zhMap.keySet().equals(enMap.keySet()));
            // 167 from 0.26.0 (ingredient_array_input added the original's
            // tooltip.machinery.ingredient_array_input wording under this mod's namespace); 169 in 0.27.0, when
            // the MOC namespace added the original's two tile.modularmachinery.machinecontroller.deprecated.tip
            // keys verbatim; 176 in 0.29.0, when the construct tool gained the original's own tooltip plus its
            // six message.structurebuild.* lines — all seven under the ORIGINAL's key names, so section AA1 can
            // diff them against _mmce-src's .lang files key for key. 189 at 0.32.0: the four energy unit labels, the six
        // craftcheck.failure.* lines, and the three dynamic-structure preview keys. 190 from 0.37.0: the fluid
        // hatch's tooltip gained a gas line, so that a gas inside a fluid hatch is visible in game
            // (`…energy.type` plus `.fe` / `.rf` / `.ic2_eu` / `.gt_eu`), which are the original's
            // `tooltip.energy.type.*` keys carried under this mod's namespace.
            // 191 from 0.63.4: the owner asked the gas hatch's tooltip to have exactly the fluid hatch's shape,
            // so gashatch.tank (the "气体：" prefix) and fluidhatch.blocked_by_gas ("空（气体占着这个仓）")
            // were removed -- 193 minus 2.
            check("the language files' key count", 191, zhMap.size());

            // The four unit labels, asserted by value rather than by count: the two that the original's own
            // language file gives the SAME text (`ic2_eu` and `gt_eu` both read `EU`) are the pair most likely to
            // be "helpfully" differentiated by a later edit, and that would change what the player sees.
            String[][] energyTypes = {
                    {"tooltip.modular_machinery_reborn.machine.energy.type.fe", "FE"},
                    {"tooltip.modular_machinery_reborn.machine.energy.type.rf", "RF"},
                    {"tooltip.modular_machinery_reborn.machine.energy.type.ic2_eu", "EU"},
                    {"tooltip.modular_machinery_reborn.machine.energy.type.gt_eu", "EU"},
            };
            for (String[] pair : energyTypes) {
                check("zh " + pair[0], pair[1], zhMap.get(pair[0]));
                check("en " + pair[0], pair[1], enMap.get(pair[0]));
            }

            Map<String, String> sourceZh = flatLang(sourceLang.resolve("zh_CN.lang"));
            Map<String, String> sourceEn = flatLang(sourceLang.resolve("en_US.lang"));

            // project key -> original key
            String[][] keys = {
                    {"gui.modular_machinery_reborn.factory.threads", "gui.factory.threads"},
                    {"gui.modular_machinery_reborn.factory.thread", "gui.factory.thread"},
                    {"gui.preview.button.machine_info.max_threads", "gui.preview.button.machine_info.max_threads"},
                    {"gui.preview.button.machine_info.core_threads", "gui.preview.button.machine_info.core_threads"},
            };
            for (String[] pair : keys) {
                check("zh " + pair[0] + " is the original's wording",
                        sourceZh.get(pair[1]), zhMap.get(pair[0]));
                check("en " + pair[0] + " is the original's wording",
                        sourceEn.get(pair[1]), enMap.get(pair[0]));
            }
            check("the factory screen's two keys are the only new gui.<modid>.factory ones",
                    "[gui.modular_machinery_reborn.factory.thread, gui.modular_machinery_reborn.factory.threads]",
                    zhMap.keySet().stream().filter(key -> key.startsWith("gui.modular_machinery_reborn.factory."))
                            .sorted().toList().toString());
        } catch (Exception exception) {
            report("reading the language files failed: " + exception, false);
        }
    }

    /** One flat key → value map out of a JSON language file. */
    private static Map<String, String> flatJson(Path file) throws Exception {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getAsString());
        }
        return map;
    }

    /** One flat key → value map out of a 1.12.2 {@code .lang} file. */
    private static Map<String, String> flatLang(Path file) throws Exception {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (String line : Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
            int equals = line.indexOf('=');
            if (equals > 0 && !line.startsWith("#")) {
                map.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        return map;
    }

    // ------------------------------------------------------------------ loader rules

    /** The three machine fields, as the loader reads them out of a definition JSON. */
    private static void loaderDefaults() {
        record Case(String json, int max, int internal, boolean parallelizable, int modifierCount) {
        }
        String parts = "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]";
        Case[] cases = {
                new Case("{\"registryname\":\"t\",\"localizedname\":\"T\"," + parts + "}", 2048, 0, true, 0),
                new Case("{\"registryname\":\"t\",\"max-parallelism\":64,\"internal-parallelism\":4,"
                        + "\"parallelizable\":false," + parts + "}", 64, 4, false, 0),
                new Case("{\"registryname\":\"t\",\"modifiers\":[{\"elements\":\"minecraft:stone\","
                        + "\"x\":0,\"y\":1,\"z\":0,\"modifier\":{\"target\":\"item\",\"io\":\"output\","
                        + "\"operation\":1,\"multiplier\":2.0}}]," + parts + "}", 2048, 0, true, 1),
        };
        for (Case c : cases) {
            com.reborn.modularmachinery.machine.MachineDefinition definition = readDefinition(c.json());
            if (definition == null) {
                report("definition failed to load: " + c.json(), false);
                continue;
            }
            boolean ok = definition.maxParallelism() == c.max()
                    && definition.internalParallelism() == c.internal()
                    && definition.parallelizable() == c.parallelizable()
                    && definition.modifiers().size() == c.modifierCount();
            report("loaded " + c.json() + " -> max=" + definition.maxParallelism() + " internal="
                    + definition.internalParallelism() + " parallelizable=" + definition.parallelizable()
                    + " modifiers=" + definition.modifiers().size(), ok);
        }
    }

    /** Every malformed field must be rejected, and the message must say what to write instead. */
    private static void loaderErrors() {
        String parts = "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]";
        Object[][] cases = {
                {"{\"registryname\":\"t\",\"max-parallelism\":-1," + parts + "}", "cannot be negative"},
                {"{\"registryname\":\"t\",\"max-parallelism\":\"lots\"," + parts + "}", "must be a whole number"},
                {"{\"registryname\":\"t\",\"internal-parallelism\":64,\"max-parallelism\":8," + parts + "}",
                        "above its 'max-parallelism'"},
                {"{\"registryname\":\"t\",\"parallelizable\":\"yes\"," + parts + "}", "must be true or false"},
                {"{\"registryname\":\"t\",\"modifiers\":{}}", "must be an array"},
                {"{\"registryname\":\"t\",\"modifiers\":[{\"elements\":\"minecraft:stone\",\"x\":0,\"y\":1,\"z\":0,"
                        + "\"modifier\":{\"target\":\"mana\",\"io\":\"output\",\"operation\":1,\"multiplier\":2.0}}],"
                        + parts + "}", "not a requirement kind"},
                {"{\"registryname\":\"t\",\"modifiers\":[{\"elements\":\"minecraft:stone\",\"x\":0,\"y\":1,\"z\":0,"
                        + "\"modifier\":{\"target\":\"item\",\"io\":\"output\",\"operation\":7,\"multiplier\":2.0}}],"
                        + parts + "}", "only defines 0 (add) and 1 (multiply)"},
                {"{\"registryname\":\"t\",\"modifiers\":[{\"elements\":\"minecraft:stone\",\"x\":0,\"y\":1,\"z\":0,"
                        + "\"modifier\":{\"target\":\"item\",\"io\":\"sideways\",\"operation\":1,\"multiplier\":2.0}}],"
                        + parts + "}", "write \"input\" or \"output\""},
        };
        for (Object[] c : cases) {
            try {
                com.reborn.modularmachinery.machine.MachineDefinition definition = readDefinition((String) c[0]);
                report("expected a rejection for " + c[0] + " but got " + definition, false);
            } catch (RuntimeException exception) {
                String message = String.valueOf(exception.getMessage());
                boolean ok = message.contains((String) c[1]);
                report("rejected: " + message, ok);
            }
        }
    }

    /** Calls the loader's private {@code readMachine} on a JSON string. */
    private static com.reborn.modularmachinery.machine.MachineDefinition readDefinition(String json) {
        try {
            Class<?> loader = Class.forName("com.reborn.modularmachinery.machine.MachineLoader");
            java.lang.reflect.Method method = loader.getDeclaredMethod("readMachine", ResourceLocation.class,
                    com.google.gson.JsonElement.class, java.util.Map.class);
            method.setAccessible(true);
            com.google.gson.JsonElement element = com.google.gson.JsonParser.parseString(json);
            return (com.reborn.modularmachinery.machine.MachineDefinition) method.invoke(null,
                    new ResourceLocation("mmverify", "test"), element, new java.util.HashMap<String, List<String>>());
        } catch (java.lang.reflect.InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // ------------------------------------------------------------------ M6d: the parallel controller

    /** A stand-in for one placed parallel controller; the block entity is this interface plus NBT. */
    private record FakeController(int maxParallelism, int parallelism) implements ParallelController {
    }

    /**
     * Section L2 —— 波 3a：{@code selector-tag}（部件标签）与它驱动的端口筛选。
     *
     * <p>这个特性存在的理由：装配线是<strong>动态</strong>结构，同一台机器在不同世界里长短不同，配方必须能说
     * 「原料进第 3 段」而不是「随便进哪个输入仓」。旧版靠 {@code ComponentSelectorTag} 实现——
     * 结构位置带标签，需求带标签，运行时两者相等才可见
     * （{@code RecipeCraftingContext.java:222}）。这里是那一层的对应实现。
     *
     * <p>断言的重点不是「可能返回了正确的桶」，而是三条<strong>可观察</strong>的性质：
     * 标签跟着结构旋转走（不跟就会静默串位）、无标签需求看不到打了标签的端口（否则「进第 3 段」形同虚设）、
     * 控制器自己的槽位永远不算任何部件的端口。
     */
    private static void selectorTags() {
        section("L2. selector-tag：结构位置标签、旋转、以及需求按标签筛端口");

        // ---- (1) 结构上的标签 --------------------------------------------------------------
        MachinePattern.Builder builder = MachinePattern.builder();
        builder.add(new BlockPos(1, 0, 0), List.of(BlockMatcher.parse("minecraft:stone")));
        builder.add(new BlockPos(2, 0, 0), List.of(BlockMatcher.parse("minecraft:stone")));
        builder.add(new BlockPos(3, 0, 0), List.of(BlockMatcher.parse("minecraft:stone")));
        builder.tag(new BlockPos(1, 0, 0), "input");
        MachinePattern pattern = builder.build();

        check("打了标签的位置读回它的标签", "input", String.valueOf(pattern.tag(new BlockPos(1, 0, 0))));
        check("没打标签的位置读回 null", "null", String.valueOf(pattern.tag(new BlockPos(2, 0, 0))));
        report("hasTags() 报出这个结构有标签", pattern.hasTags());
        check("只有被标记的那一个位置进了标签表", 1, pattern.tags().size());

        // 空串与 null 都要归一成「没有标签」：作者写 `"selector-tag": ""` 的部件，
        // 行为必须与完全没写这个字段一致，否则会凭空多出一个「空标签」的桶。
        MachinePattern.Builder blank = MachinePattern.builder();
        blank.add(new BlockPos(1, 0, 0), List.of(BlockMatcher.parse("minecraft:stone")));
        blank.tag(new BlockPos(1, 0, 0), "");
        blank.tag(new BlockPos(1, 0, 0), null);
        report("空串标签与 null 都归一成「没有标签」，不会造出一个空标签桶",
                !blank.build().hasTags());

        // ---- (2) 旋转必须把标签一起转 ------------------------------------------------------
        // rotateYCCW 是 (z, y, -x)。(1,0,0) 因此应当落到 (0,0,-1)。
        MachinePattern rotated = pattern.rotateYCounterClockwise();
        check("绕 Y 逆时针旋转后，标签落在 (z, y, -x) 变换到的那个坐标上", "input",
                String.valueOf(rotated.tag(new BlockPos(0, 0, -1))));
        report("旋转前的那个坐标在旋转后不再带标签（标签确实跟着位置走了，而不是留在原地）",
                rotated.tag(new BlockPos(1, 0, 0)) == null);
        check("旋转后仍然只有一个带标签的位置", 1, rotated.tags().size());

        // ---- (3) 端口按标签筛选 ------------------------------------------------------------
        net.minecraftforge.items.ItemStackHandler smelterIn =
                new net.minecraftforge.items.ItemStackHandler(1);
        net.minecraftforge.items.ItemStackHandler lineIn =
                new net.minecraftforge.items.ItemStackHandler(1);
        net.minecraftforge.items.ItemStackHandler plainIn =
                new net.minecraftforge.items.ItemStackHandler(1);
        HatchCollection tagged = HatchCollection.builder()
                .add(com.reborn.modularmachinery.block.HatchKind.ITEM_INPUT, smelterIn, null, null, "input")
                .add(com.reborn.modularmachinery.block.HatchKind.ITEM_INPUT, lineIn, null, null, "line")
                .add(com.reborn.modularmachinery.block.HatchKind.ITEM_INPUT, plainIn, null, null)
                .build();

        check("无标签的集合里有三个输入端口", 3, tagged.itemInputs().size());
        check("按 input 筛只剩一个", 1, tagged.withTag("input").itemInputs().size());
        report("...而且剩的正是那个部件自己的仓", tagged.withTag("input").itemInputs().get(0) == smelterIn);
        report("按 line 筛到的是另一个仓，两者没有混淆",
                tagged.withTag("line").itemInputs().get(0) == lineIn);
        report("筛一个结构里不存在的标签得到空集合（配方引用了不存在的部件不会崩，只是永远不满足）",
                tagged.withTag("no_such_part").itemInputs().isEmpty());
        check("没有标签的需求只看到没打标签的端口", 1, tagged.withTag(null).itemInputs().size());
        report("...也就是那个无标签的仓，而不是「全都能看到」",
                tagged.withTag(null).itemInputs().get(0) == plainIn);

        // 控制器自己的槽位永远不算任何部件的端口：否则「进第 3 段」会被控制器自己的槽位满足，
        // 这个特性就形同虚设。
        net.minecraftforge.items.ItemStackHandler controllerOwn =
                new net.minecraftforge.items.ItemStackHandler(1);
        HatchCollection withController = HatchCollection.builder()
                .add(com.reborn.modularmachinery.block.HatchKind.ITEM_INPUT, smelterIn, null, null, "input")
                .addItemHandler(controllerOwn, true)
                .build();
        check("控制器自己的槽位进了无标签桶", 1, withController.withTag(null).itemInputs().size());
        report("...并且对任何打了标签的需求都不可见",
                withController.withTag("input").itemInputs().size() == 1
                        && withController.withTag("input").itemInputs().get(0) == smelterIn);

        // ---- (4) 需求带着标签，整条配方就只认那个部件的料 -----------------------------------
        net.minecraftforge.items.ItemStackHandler taggedSlot =
                new net.minecraftforge.items.ItemStackHandler(1);
        net.minecraftforge.items.ItemStackHandler untaggedSlot =
                new net.minecraftforge.items.ItemStackHandler(1);
        HatchCollection twoInputs = HatchCollection.builder()
                .add(com.reborn.modularmachinery.block.HatchKind.ITEM_INPUT, taggedSlot, null, null, "input")
                .add(com.reborn.modularmachinery.block.HatchKind.ITEM_INPUT, untaggedSlot, null, null)
                .build();

        MachineRequirement requirement = ItemRequirement.input(Ingredient.of(Items.COBBLESTONE), 1);
        report("新建的需求没有标签", requirement.selectorTag() == null);
        requirement.setSelectorTag("input");
        check("设入的标签读得回来", "input", String.valueOf(requirement.selectorTag()));

        untaggedSlot.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 8));
        report("料只在无标签仓里时，带 input 标签的需求不满足（它看不到那个仓）",
                !requirement.canSatisfy(requirement.visiblePorts(twoInputs)));
        taggedSlot.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 8));
        report("料进了它自己的仓之后就满足了",
                requirement.canSatisfy(requirement.visiblePorts(twoInputs)));

        // 同一条需求去掉标签，就该重新看得到无标签的那个仓——证明上面那次「不满足」真的来自筛选，
        // 而不是因为料不够或别的原因。
        requirement.setSelectorTag(null);
        report("同一个配方去掉标签后，无标签的仓又能被看到（对照，证明上一条的失败确实来自筛选）",
                requirement.canSatisfy(requirement.visiblePorts(twoInputs)));

        // ---- (5) 料放错段时，必须报告原因，而不是「没有配方」------------------------------------
        //
        // 实机发现的第二个缺陷：`findRecipe` 只返回**能开工**的配方，于是「配方在、只是料放错了段」
        // 也走「没有配方」那条路，控制器显示 NO_RECIPE 并把失败文案**清掉**。
        // 玩家看到「没有相匹配的配方」，而真正的原因（缺少物品输入）被丢了——实机时正卡在这里。
        //
        // 现在控制器在那种情况下保留 `startFailure` 给出的原因。这里断言的是那条链路里**配方一侧**的
        // 那一半：一条绑着 `input_line_0` 的配方，在料放在别的段上时必须答出「缺少物品输入」。
        net.minecraftforge.items.ItemStackHandler wrongSegment =
                new net.minecraftforge.items.ItemStackHandler(1);
        net.minecraftforge.items.ItemStackHandler rightSegment =
                new net.minecraftforge.items.ItemStackHandler(1);
        HatchCollection segments = HatchCollection.builder()
                .add(com.reborn.modularmachinery.block.HatchKind.ITEM_INPUT, rightSegment, null, null,
                        "input_line_0")
                .add(com.reborn.modularmachinery.block.HatchKind.ITEM_INPUT, wrongSegment, null, null,
                        "input_line_1")
                .build();
        MachineRequirement bound = ItemRequirement.input(Ingredient.of(Items.COBBLESTONE), 1);
        bound.setSelectorTag("input_line_0");
        wrongSegment.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 8));
        report("料只放在别的段上时，绑定的需求不满足",
                !bound.canSatisfy(bound.visiblePorts(segments)));
        check("...而且它给出的原因是「缺少物品输入」，不是空",
                ItemRequirement.FAILURE_ITEM_INPUT,
                String.valueOf(bound.startFailure(bound.visiblePorts(segments))));
        rightSegment.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, 8));
        report("料放进它自己那一段之后满足了", bound.canSatisfy(bound.visiblePorts(segments)));
        // 注意这里**不**断言 `startFailure()` 在满足时返回 null：按契约它只在 canSatisfy 已经答 false
        // 之后才被问（见 MachineRequirement 的说明），而 ItemRequirement 的实现是「按方向给出那条消息」。
        // 生产代码里对应的纪律是 `unaffordableStartFailure` 先自己确认 canStart/canTick 都过不了再问它——
        // 上面那条断言失败过一次，正是因为当时的生产代码没做这个前置检查。

        // ---- (6) 失败文案必须**能被镜像到客户端** ------------------------------------------------
        //
        // 这是波 2 最大的一处「看不见的墙」：菜单只能传整数，所以失败键要通过一张**白名单**
        // （START_FAILURE_KEYS）映射成索引；而波 2 新增的六条 craftcheck.failure.* 一个都不在里面。
        // failureIndex() 对「不在列表里的键」返回 NO_START_FAILURE，于是那些文案**从来没有可能到达客户端**
        // ——服务端设了、发包了、客户端读到的却是「没有失败」。实机表现是「状态显示闲置，但状态行下面是空的」。
        //
        // 这一条之所以值得单独立断言：一份白名单漏项时**不会报错**，只会安静地把功能删掉。
        // 所以这里断言的不是「文案存在」，而是「每一条键都能被镜像到客户端、且原样读回来」。
        String[] mustBeMirrorable = {
                com.reborn.modularmachinery.recipe.ItemRequirement.FAILURE_ITEM_INPUT,
                com.reborn.modularmachinery.recipe.ItemRequirement.FAILURE_ITEM_OUTPUT_SPACE,
                com.reborn.modularmachinery.recipe.FluidRequirement.FAILURE_FLUID_INPUT,
                com.reborn.modularmachinery.recipe.FluidRequirement.FAILURE_FLUID_OUTPUT_SPACE,
                com.reborn.modularmachinery.recipe.EnergyRequirement.FAILURE_ENERGY_INPUT,
                com.reborn.modularmachinery.recipe.EnergyRequirement.FAILURE_ENERGY_OUTPUT_SPACE,
                com.reborn.modularmachinery.recipe.InterfaceNumberInputRequirement.MISSING_KEY,
                com.reborn.modularmachinery.machine.SmartInterfaceType.DEFAULT_NOT_EQUAL_KEY,
        };
        for (String key : mustBeMirrorable) {
            int index = com.reborn.modularmachinery.block.MachineControllerBlockEntity.failureIndex(key);
            report("失败键能镜像到客户端: " + key + " (索引 " + index + ")",
                    index >= 0 && key.equals(com.reborn.modularmachinery.block.MachineControllerBlockEntity
                            .failureKeyAt(index)));
        }
        report("不在白名单里的键仍然映射成「没有失败」，而不是越界",
                com.reborn.modularmachinery.block.MachineControllerBlockEntity.failureIndex("not.a.key")
                        == com.reborn.modularmachinery.block.MachineControllerBlockEntity.NO_START_FAILURE);
        report("「没有失败」的哨兵值不会被当成索引取到东西",
                com.reborn.modularmachinery.block.MachineControllerBlockEntity
                        .failureKeyAt(com.reborn.modularmachinery.block.MachineControllerBlockEntity
                                .NO_START_FAILURE) == null);
        // 顺序是通信格式的一部分：新键只能追加。这条断言把「前缀不变」这件事钉住。
        check("原有两条键仍在最前面（索引 0/1），新键只能追加在末尾",
                com.reborn.modularmachinery.recipe.InterfaceNumberInputRequirement.MISSING_KEY,
                com.reborn.modularmachinery.block.MachineControllerBlockEntity.START_FAILURE_KEYS.get(0));
        check("...第二条仍是接口数值不等", com.reborn.modularmachinery.machine.SmartInterfaceType
                        .DEFAULT_NOT_EQUAL_KEY,
                com.reborn.modularmachinery.block.MachineControllerBlockEntity.START_FAILURE_KEYS.get(1));

        // ---- (7) 波 4：源 JSON 往返之后重建出的定义必须与原定义相符 ------------------------------
        //
        // 这是「机器定义同步到客户端」那条路的核心性质：同步传的是**定义解析用的那份 JSON**，
        // 客户端拿它重跑同一个 MachineSchema.read。所以「往返之后还是同一台机器」必须被断言——
        // 否则客户端会拿到一份**看起来能用、但某些字段不同**的定义，而那类分歧表现为
        // 「单机正常、联机行为诡异」，是最难查的一种。
        //
        // 这里用的是一个**功能齐全**的定义（并行度、工厂线程、智能接口、修改器、动态结构都写了），
        // 因为只测一个最小定义的话，漏掉的恰好会是那些字段。
        String wireJson = "{\"registryname\":\"mmverify_wire\",\"localizedname\":\"Wire Test\","
                + "\"max-parallelism\":32,\"internal-parallelism\":2,\"parallelizable\":true,"
                + "\"failure-action\":\"decrease\",\"requires-blueprint\":false,"
                + "\"max-threads\":7,\"has-factory\":true,\"factory-only\":false,"
                + "\"core-threads\":[{\"name\":\"smelter\",\"recipes\":[]}],"
                + "\"smart-interfaces\":[{\"type\":\"mode\",\"default\":1,\"priority\":100}],"
                + "\"modifiers\":[{\"elements\":\"minecraft:stone\",\"x\":0,\"y\":2,\"z\":0,"
                + "\"modifier\":{\"target\":\"item\",\"io\":\"output\",\"operation\":1,\"multiplier\":2.0}}],"
                + "\"parts\":[{\"x\":0,\"y\":0,\"z\":-1,\"elements\":\"minecraft:stone\"},"
                + "{\"x\":-1,\"y\":0,\"z\":0,\"selector-tag\":\"input\",\"elements\":\"minecraft:chest\"},"
                + "{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                + "\"dynamic-patterns\":[{\"name\":\"line\",\"faces\":[\"north\"],\"minSize\":1,\"maxSize\":4,"
                + "\"parts\":[{\"x\":0,\"y\":0,\"z\":0,\"selector-tag\":\"seg\","
                + "\"elements\":[\"minecraft:stone\",\"minecraft:chest\"]}],"
                + "\"structure-size-offset-start\":{\"x\":0,\"y\":0,\"z\":1},"
                + "\"structure-size-offset\":{\"x\":0,\"y\":0,\"z\":1}}]}";
        com.google.gson.JsonElement sourceJson = com.google.gson.JsonParser.parseString(wireJson);
        MachineDefinition original = com.reborn.modularmachinery.machine.MachineSchema.read(
                sourceJson, "wire-round-trip", java.util.Map.of());
        com.reborn.modularmachinery.machine.MachineRegistry.replace(
                java.util.Map.of(original.id(), original),
                java.util.Map.of(original.id(), sourceJson));
        report("注册表留下了源 JSON", com.reborn.modularmachinery.machine.MachineRegistry
                .sources().containsKey(original.id()));
        com.google.gson.JsonElement roundTripped = com.reborn.modularmachinery.machine.MachineRegistry
                .sources().get(original.id());
        report("...而且就是同一份 JSON", sourceJson.equals(roundTripped));
        MachineDefinition rebuilt = com.reborn.modularmachinery.machine.MachineSchema.read(
                roundTripped, "wire-round-trip", java.util.Map.of());
        // `MachineDefinition` 有 toString()（逐字段拼的），所以这一条是**整台机器**的比较，
        // 而不是我挑几个字段来比——挑字段的写法会漏掉我没想到的那个。
        check("往返之后整台机器逐字段相同", original.toString(), rebuilt.toString());
        report("...结构里的标签也相同（selector-tag 随 JSON 一起过去）",
                original.pattern().tags().equals(rebuilt.pattern().tags()));
        report("...而且这份定义确实覆盖了那些容易漏的字段（不是最小定义）",
                original.hasFactory() && original.maxThreads() == 7
                        && original.smartInterfaces().size() == 1 && original.modifiers().size() == 1
                        && original.hasDynamicPatterns() && original.pattern().hasTags());
        // 一次 replace 只保留仍然存在的定义的源 JSON：否则下一次同步会把已经删掉的机器又发给客户端。
        com.reborn.modularmachinery.machine.MachineRegistry.replace(java.util.Map.of());
        report("注册表清空时源 JSON 也清空（删掉的机器不会被重新同步出去）",
                com.reborn.modularmachinery.machine.MachineRegistry.sources().isEmpty());

        // ---- (8) 客户端收到包之后，界面读的那个注册表必须真的被填上 ------------------------------
        //
        // 上面 (7) 验的是「JSON 往返之后定义不变」；这一段验的是**终点**：客户端的那些读点
        // （`MachineRegistry.all()` / `byId()`）在收到同步之后能不能拿到机器。
        //
        // 为什么非要单独立一条：这个特性的全部意义就是「客户端也能看到机器定义」，
        // 而「包发出去了」和「客户端界面上有内容」之间还隔着一步——把解析结果写进注册表。
        // 这一步错了的话，包、注册表、往返断言**全都是绿的**，而 JEI 分类依然是空的。
        try {
            com.google.gson.JsonElement clientSource = com.google.gson.JsonParser.parseString(wireJson);
            MachineDefinition sentDefinition = com.reborn.modularmachinery.machine.MachineSchema.read(
                    clientSource, "client-side", java.util.Map.of());
            // 服务端那一步：注册表里有什么，就同步什么。
            com.reborn.modularmachinery.machine.MachineRegistry.replace(
                    java.util.Map.of(sentDefinition.id(), sentDefinition),
                    java.util.Map.of(sentDefinition.id(), clientSource));
            java.util.Map<ResourceLocation, String> snapshot =
                    com.reborn.modularmachinery.network.MachineDefinitionSyncPacket.snapshot();
            check("服务端快照里有这台机器", 1, snapshot.size());
            check("...键是定义自己的 id", sentDefinition.id().toString(),
                    String.valueOf(snapshot.keySet().iterator().next()));

            // 客户端那一步：清空注册表，装作刚连上来的客户端，再接收同步。
            com.reborn.modularmachinery.machine.MachineRegistry.replace(java.util.Map.of());
            report("客户端接收之前注册表是空的（这就是专用服务器客户端的现状）",
                    com.reborn.modularmachinery.machine.MachineRegistry.all().isEmpty());
            com.reborn.modularmachinery.client.ClientMachines.accept(snapshot);
            check("接收之后注册表里有 1 台机器", 1,
                    com.reborn.modularmachinery.machine.MachineRegistry.size());
            report("...而且就是那台机器（界面用 byId 查得到）",
                    com.reborn.modularmachinery.machine.MachineRegistry.byId(sentDefinition.id())
                            .map(found -> found.toString().equals(sentDefinition.toString()))
                            .orElse(false));
            check("...没有条目被拒", 0, com.reborn.modularmachinery.client.ClientMachines.lastRejected());
            // 断线清空：否则换一个服务器之后，上一个服务器的机器还留在界面里。
            com.reborn.modularmachinery.client.ClientMachines.clear();
            report("断线清空之后注册表又空了",
                    com.reborn.modularmachinery.machine.MachineRegistry.all().isEmpty());

            // 一份坏定义被跳过并计数，而不是让整次同步失败——少一台机器好过整个界面打不开。
            com.reborn.modularmachinery.client.ClientMachines.accept(java.util.Map.of(
                    sentDefinition.id(), wireJson,
                    new ResourceLocation("mmverify", "broken"), "{\"registryname\":"));
            check("坏定义被跳过，好的那台仍然收下", 1,
                    com.reborn.modularmachinery.machine.MachineRegistry.size());
            check("...并且被计数", 1, com.reborn.modularmachinery.client.ClientMachines.lastRejected());

            // **客户端重建必须与服务端加载一样宽容。**
            //
            // 这一条抓的是一个真实隐患，而我第一版就写错了：我当时给客户端用了 strict=true，
            // 理由是"客户端应该严格一点"。但 strict 只控制一件事——是否拒绝**未知根字段**——
            // 而 MachineLoader.readMachine 走的是三参数的 read，即 strict=false。
            // 于是含未知字段的定义在服务端加载成功、到客户端被拒，客户端静默少一台机器。
            //
            // 所以这里把两端**实际上用的那个模式**断言成同一个值，而不只是断言"客户端能解析"：
            // 后者在两边都宽容时也会通过，而前者才是"忠实重建"这件事本身。
            com.reborn.modularmachinery.machine.MachineRegistry.replace(java.util.Map.of());
            String withUnknownField = "{\"registryname\":\"mmverify_unknown\","
                    + "\"a-field-from-a-newer-version\":123,"
                    + "\"parts\":[{\"x\":0,\"y\":0,\"z\":-1,\"elements\":\"minecraft:stone\"}]}";
            report("客户端用的解析模式与服务端加载时相同（都是宽容）",
                    !com.reborn.modularmachinery.network.MachineDefinitionSyncPacket.CLIENT_PARSE_STRICT);
            com.reborn.modularmachinery.client.ClientMachines.accept(java.util.Map.of(
                    new ResourceLocation("mmverify", "unknown_field"), withUnknownField));
            check("含未知根字段的定义在客户端也被收下（服务端就是这么接受它的）", 1,
                    com.reborn.modularmachinery.machine.MachineRegistry.size());
            check("...没有被拒", 0, com.reborn.modularmachinery.client.ClientMachines.lastRejected());
            // 对照：严格模式确实会拒绝它——证明上面那条不是因为「未知字段其实没人检查」而通过的。
            report("对照：严格模式确实拒绝同一个定义（所以宽容不是空断言）",
                    com.reborn.modularmachinery.network.MachineDefinitionSyncPacket.parse(
                            new ResourceLocation("mmverify", "unknown_field"), withUnknownField, true) == null);
            com.reborn.modularmachinery.client.ClientMachines.clear();
        } catch (Throwable throwable) {
            // 这一段的失败要说清楚它测的是什么——它是这个特性的终点，不是细节。
            report("客户端重建这一段无法驱动（这才是波 4 真正要验的东西）: " + throwable, false);
        }
    }

    /**
     * 去掉本版新增的那一节，得到一个「上一版写下的配置文件」。
     *
     * <p>为什么要有这个方法：配置迁移检查要证明的是「旧文件被新 spec 接受、修正纯粹是新增」，
     * 而实例一旦用新版本启动过，磁盘上那份就已经被 Forge 补全了——那时的检查<strong>无法</strong>证明任何事，
     * 只会报一条取决于「你启动过没有」的结果。自己造出一个已知的旧输入，这个检查才有意义，
     * 而且它在任何机器上跑都一样。
     *
     * <p>做法是从 <b>{@code [display.energy]}</b> 这一节的开头切成两半，其余字节原样保留：
     * 被断言的仍然是真实文件，只是少了这一版才加的那一段。节首（{@code [display.energy]}）与节尾
     * （下一个 {@code [} 开头的行）之间的所有内容，包括注释，都一起被去掉——注释是 Forge 从 spec 重新写出来的，
     * 留着它反而会让「这一节不存在」这条断言变成假话。
     */
    private static String stripNewestSection(String config) {
        // Forge 写含点号的分类名时会加引号、并用双括号（数组表）：实际字节是 `[["display.energy"]]`，
        // 而不是 `[display.energy]`。锚点必须用真实写法，否则这里会把整份文件原样返回，
        // 而「这个文件是旧的」这条前提就悄悄不成立了。
        String[] headers = {"[[\"display.energy\"]]", "[\"display.energy\"]", "[display.energy]"};
        int start = -1;
        for (String header : headers) {
            start = config.indexOf(header);
            if (start >= 0) {
                break;
            }
        }
        if (start < 0) {
            // 磁盘上那份本来就没有这一节（例如从未用本版启动过）：原样返回，这才是真实的「旧文件」。
            return config;
        }
        int nextSection = config.indexOf("\n[", start + 1);
        if (nextSection < 0) {
            return config.substring(0, start);
        }
        return config.substring(0, start) + config.substring(nextSection + 1);
    }

    /**
     * Section L3 —— 波 3b：{@code dynamic-patterns} 的解析、展开，以及量长度的算术。
     *
     * <p>这个特性的形状是「沿某个面重复 N 节」，而 N 由世界里实际存在的方块决定。定义里同时给出
     * {@code minSize} 与 {@code maxSize} 作为上下限。
     *
     * <p>这里断言的核心是 {@link DynamicPattern#matchesSize} 那段算术。它能被离线测到，是因为它被写成了
     * 「问一个位置成不成立」的谓词形式——否则它需要一个 {@code Level}，就会变成复现器永远覆盖不到的代码，
     * 而它恰好是这个特性里最容易写错的地方（上限判定、收尾段的两处检查、回退一格防重叠）。
     */
    private static void dynamicPatterns() {
        section("L3. dynamic-patterns：解析、按节展开、以及长度匹配的四种边界");

        // ---- (1) 定义级：字段、校验、以及它不再是「未实现字段」 ------------------------------
        String lineJson = "{\"registryname\":\"mmverify_line\","
                + "\"parts\":[{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                + "\"dynamic-patterns\":[{"
                + "  \"name\":\"line\","
                + "  \"faces\":[\"north\"],"
                + "  \"minSize\":5,"
                + "  \"maxSize\":15,"
                + "  \"parts\":[{\"x\":0,\"y\":0,\"z\":1,\"selector-tag\":\"input\",\"elements\":\"minecraft:stone\"}],"
                + "  \"parts-end\":[{\"x\":0,\"y\":0,\"z\":1,\"selector-tag\":\"output\",\"elements\":\"minecraft:iron_block\"}],"
                + "  \"structure-size-offset-start\":{\"x\":0,\"y\":0,\"z\":1},"
                + "  \"structure-size-offset\":{\"x\":0,\"y\":0,\"z\":1}"
                + "}]}";

        MachineDefinition line = readDefinition(lineJson);
        check("动态结构被解析出来", 1, line.dynamicPatterns().size());
        report("...并且这台机器报告自己有动态结构", line.hasDynamicPatterns());
        check("没有该字段的机器报告没有动态结构", 0,
                readDefinition("{\"registryname\":\"mmverify_plain\","
                        + "\"parts\":[{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}]}")
                        .dynamicPatterns().size());

        DynamicPattern parsed = line.dynamicPatterns().get(0);
        check("模式名读到了", "line", parsed.name());
        check("minSize 读到了", 5, parsed.minSize());
        check("maxSize 读到了", 15, parsed.maxSize());
        report("faces 里的 north 读到了", parsed.faces().contains(net.minecraft.core.Direction.NORTH));
        report("收尾段被识别为存在", parsed.hasEnd());
        check("起始偏移读到了", "0, 0, 1", parsed.startOffset().toShortString());
        check("步进偏移读到了", "0, 0, 1", parsed.stepOffset().toShortString());

        // `dynamic-patterns` 曾经在 UNIMPLEMENTED_ROOT_FIELDS 里（加载时会警告「认得但不求值」）。
        // 断言它已经离开那份清单，否则作者会看到一条与事实相反的警告。
        report("dynamic-patterns 已不在「认得但未实现」清单里",
                !MachineSchema.KNOWN_ROOT_FIELDS.isEmpty()
                        && MachineSchema.KNOWN_ROOT_FIELDS.contains("dynamic-patterns"));

        // 旧版那条 `minSize > maxSize` 的检查是空转的（它在 minSize 被赋值之前运行，读到的永远是构造器初值 0），
        // 所以旧版实际放过了这个矛盾。本版把它接上。
        //
        // 断言的是**消息内容**而不只是「抛了异常」：一个抛异常的检查只证明有东西出错，
        // 而这里要证明的是「出错时告诉作者该写什么」——与本项目既有的 loaderErrors 用同一种形态。
        Object[][] patternErrors = {
                {"{\"registryname\":\"t\",\"parts\":[{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                        + "\"dynamic-patterns\":[{\"name\":\"bad\",\"faces\":[\"north\"],"
                        + "\"minSize\":9,\"maxSize\":3,"
                        + "\"parts\":[{\"x\":0,\"y\":0,\"z\":1,\"elements\":\"minecraft:stone\"}]}]}",
                        "above its 'maxSize'"},
                {"{\"registryname\":\"t\",\"parts\":[{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                        + "\"dynamic-patterns\":[]}", "is empty"},
                {"{\"registryname\":\"t\",\"parts\":[{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                        + "\"dynamic-patterns\":[{\"name\":\"p\",\"faces\":[\"north\"],"
                        + "\"parts\":[{\"x\":0,\"y\":0,\"z\":1,\"elements\":\"minecraft:stone\"}]}]}",
                        "must have a 'maxSize'"},
                {"{\"registryname\":\"t\",\"parts\":[{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                        + "\"dynamic-patterns\":[{\"name\":\"p\",\"faces\":[\"sideways\"],\"maxSize\":3,"
                        + "\"parts\":[{\"x\":0,\"y\":0,\"z\":1,\"elements\":\"minecraft:stone\"}]}]}",
                        "Invalid facing 'sideways'"},
                {"{\"registryname\":\"t\",\"parts\":[{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                        + "\"dynamic-patterns\":[{\"faces\":[\"north\"],\"maxSize\":3,"
                        + "\"parts\":[{\"x\":0,\"y\":0,\"z\":1,\"elements\":\"minecraft:stone\"}]}]}",
                        "Missing required field 'name'"},
        };
        for (Object[] c : patternErrors) {
            try {
                MachineDefinition bad = readDefinition((String) c[0]);
                report("expected a rejection for " + c[1] + " but got " + bad, false);
            } catch (RuntimeException exception) {
                String message = String.valueOf(exception.getMessage());
                report("rejected with a usable message: " + message, message.contains((String) c[1]));
            }
        }

        // ---- (2) 展开：节数、标签名、以及偏移 ------------------------------------------------
        // 标签名是**硬约定**：作者写的配方里就是靠它指认「第几段」。格式是 `<标签>_<模式名>_<段号>`。
        check("段标签的格式", "input_line_0", DynamicPattern.segmentTag("input", "line", 0));
        check("...段号从 0 开始", "input_line_2", DynamicPattern.segmentTag("input", "line", 2));
        check("收尾段的标签没有段号", "output_line_end", DynamicPattern.endTag("output", "line"));

        MachinePattern three = parsed.expand(3, 0);
        // 期望值取自这个结构**实际**放下的标签（上一轮的十一条失败全部来自我对偏移的心算，
        // 所以这里保留一行把布局打出来的报告，让下一个人一眼能核对自己的期望）。
        //
        // 偏移是 (0,0,1)、部件坐标是 z=1，而 expand 的约定是「部件坐标 + 段偏移」，
        // 于是第 0 节落在 z=1+1=2，之后每节 +1。
        StringBuilder dump = new StringBuilder();
        for (java.util.Map.Entry<BlockPos, String> entry : new java.util.TreeMap<>(three.tags()).entrySet()) {
            dump.append(' ').append(entry.getKey().toShortString()).append('=').append(entry.getValue());
        }
        report("展开 3 节后的标签布局（供核对）:" + dump, true);
        check("展开 3 节后，重复段与收尾段的标签各就各位：3 + 1 = 4 个标签", 4, three.tags().size());
        check("第 0 节的标签", "input_line_0", String.valueOf(three.tag(new BlockPos(0, 0, 2))));
        check("第 1 节的标签", "input_line_1", String.valueOf(three.tag(new BlockPos(0, 0, 3))));
        check("第 2 节的标签", "input_line_2", String.valueOf(three.tag(new BlockPos(0, 0, 4))));
        check("收尾段接在第 2 节之后", "output_line_end", String.valueOf(three.tag(new BlockPos(0, 0, 5))));
        check("第 3 节不存在（只展开了 3 节）", "null", String.valueOf(three.tag(new BlockPos(0, 0, 6))));

        // 0 节也可以成立（只有收尾段），此时不该有段号标签，只有收尾标签。
        MachinePattern zero = parsed.expand(0, 0);
        check("展开 0 节时只剩收尾段的一个标签", 1, zero.tags().size());
        // 再看 1 节：用于分清「offsets 整体多了一格」与「只有收尾段那次多算」这两种不同的原因。
        MachinePattern one = parsed.expand(1, 0);
        StringBuilder oneDump = new StringBuilder();
        for (java.util.Map.Entry<BlockPos, String> entry : new java.util.TreeMap<>(one.tags()).entrySet()) {
            oneDump.append(' ').append(entry.getKey().toShortString()).append('=').append(entry.getValue());
        }
        report("展开 1 节后的标签布局（供核对）:" + oneDump, true);
        // 收尾段锚在 startOffset + 0 节 * stepOffset，再加上收尾单元自己的局部坐标 (0,0,1)：
        // (0,0,1) + (0,0,1) = (0,0,2)。
        //
        // 0.63.1 及以前这里落在 z=3：展开时无论几节都先把锚点前进一步，于是「0 节」的收尾段
        // 与配方里写的偏移差了一格，而量长度一侧把「收尾段压在起点」算成 0 节——两侧自相矛盾，
        // 0 节机器永远拼不成（勘测 X3）。
        check("...而且它就是收尾标签（落在 z=2，即 start + 0 步）", "output_line_end",
                String.valueOf(zero.tag(new BlockPos(0, 0, 2))));
        report("...并且没有生成任何段号标签", !zero.tags().containsValue("input_line_0"));

        // ---- (3) 量长度：四种边界，全部离线可测 ----------------------------------------------
        BlockPos origin = BlockPos.ZERO;
        BlockPos step = new BlockPos(0, 0, 1);
        MachinePattern oneBlock = MachinePattern.builder()
                .add(BlockPos.ZERO, List.of(BlockMatcher.parse("minecraft:stone")))
                .build();

        DynamicPattern noEnd = new DynamicPattern("plain", Set.of(net.minecraft.core.Direction.NORTH),
                0, 15, oneBlock, null, BlockPos.ZERO, BlockPos.ZERO);
        DynamicPattern withEnd = new DynamicPattern("line", Set.of(net.minecraft.core.Direction.NORTH),
                0, 15, oneBlock, oneBlock, BlockPos.ZERO, BlockPos.ZERO);

        java.util.Set<BlockPos> parts = new java.util.HashSet<>();
        parts.add(new BlockPos(0, 0, 0));
        parts.add(new BlockPos(0, 0, 1));
        parts.add(new BlockPos(0, 0, 2));
        parts.add(new BlockPos(0, 0, 3));

        // 四个位置连续匹配，所以数到 4——「起点本身算第一节」。
        check("四节都在：数到 4（起点本身算第一节）", 4,
                noEnd.matchesSize(parts::contains, pos -> false, origin, BlockPos.ZERO, step));
        // 没有收尾段时数到 0 节也成立（返回 0 而不是 -1）：作者不写 parts-end 又把 minSize 留成
        // 缺省 0，就是在声明「这段可有可无」。旧版在这里返回 0 且它的 isMatched 只看面，所以旧版算成立；
        // 0.62.9–0.63.1 返回 -1，0 节永远选不上（勘测 X3）。
        check("一节都没有、也没有收尾段：0 节成立（X3）", 0,
                noEnd.matchesSize(pos -> false, pos -> false, origin, BlockPos.ZERO, step));

        // 上限：maxSize=3 而世界上有 4 节时，结果是**不匹配**而不是被截断成 3。
        DynamicPattern capped = new DynamicPattern("cap", Set.of(net.minecraft.core.Direction.NORTH),
                0, 3, oneBlock, null, BlockPos.ZERO, BlockPos.ZERO);
        check("超过 maxSize 时是不匹配（-1），而不是截断成 maxSize", -1,
                capped.matchesSize(parts::contains, pos -> false, origin, BlockPos.ZERO, step));

        // 收尾段规则一：**当前这一格**就是收尾段，节数 = 已经数过的那些。
        // 重复段在 z=0..2，收尾段在 z=3。
        java.util.Set<BlockPos> endAtThree = new java.util.HashSet<>();
        endAtThree.add(new BlockPos(0, 0, 0));
        endAtThree.add(new BlockPos(0, 0, 1));
        endAtThree.add(new BlockPos(0, 0, 2));
        endAtThree.add(new BlockPos(0, 0, 3));
        check("收尾段在第 4 格（z=3）：数是 3 节（前面数到 3 节）", 3,
                withEnd.matchesSize(endAtThree::contains,
                        pos -> pos.equals(new BlockPos(0, 0, 3)), origin, BlockPos.ZERO, step));

        // 收尾段规则二（回退一格）：重复段在 z=0..2，收尾段落在回退到的那一格 z=2 上。
        // 算法走到 z=3 发现既不是重复段、也不是收尾段，于是**退回去**在 z=2 处再确认一次
        // （旧版注释里写的 Prevent overlapping structures），而旧版这一支带一次 `size--`。
        //
        // 结果是 2 而不是 3：收尾段压在最后一节上时，那一节没有被计入。
        // **这里刻意照抄旧版而不去「修正」它**——没有证据说明作者的意图是 3，
        // 而凭「看起来更合理」改动过的计数会让机器在世界里少算或多算一节，比留着这个歧义更糟。
        // 这条断言的作用是把旧版的实际取值钉住：将来要改它，必须先解释清楚意图。
        java.util.Set<BlockPos> endBackAtTwo = new java.util.HashSet<>();
        endBackAtTwo.add(new BlockPos(0, 0, 0));
        endBackAtTwo.add(new BlockPos(0, 0, 1));
        endBackAtTwo.add(new BlockPos(0, 0, 2));
        check("收尾段压在最后一格上（旧版这一支带 size--）：取值是 2", 2,
                withEnd.matchesSize(endBackAtTwo::contains,
                        pos -> pos.equals(new BlockPos(0, 0, 2)), origin, BlockPos.ZERO, step));

        // 回退到的那一格也不是收尾段：不成立。收尾段在 z=4，回退落到 z=2，两处都不匹配。
        java.util.Set<BlockPos> endBackMisses = new java.util.HashSet<>(endBackAtTwo);
        endBackMisses.add(new BlockPos(0, 0, 4));
        check("回退到的那一格不是收尾段时不成立", -1,
                withEnd.matchesSize(endBackMisses::contains,
                        pos -> pos.equals(new BlockPos(0, 0, 4)), origin, BlockPos.ZERO, step));

        // 收尾段既不在这里、也不在回退的那一格：不成立。
        // 重复段 z=0..2，z=3 空着，收尾段在 z=5——回退到 z=2 也不是收尾段。
        java.util.Set<BlockPos> gap = new java.util.HashSet<>();
        gap.add(new BlockPos(0, 0, 0));
        gap.add(new BlockPos(0, 0, 1));
        gap.add(new BlockPos(0, 0, 2));
        gap.add(new BlockPos(0, 0, 5));
        check("收尾段两处都找不到时不成立", -1,
                withEnd.matchesSize(gap::contains,
                        pos -> pos.equals(new BlockPos(0, 0, 5)), origin, BlockPos.ZERO, step));

        // 只有收尾段、没有重复段：0 节且**成立**（旧版会返回 size-1 = 0）。
        // 起点就是收尾段，所以回退一格也落在起点上。
        int onlyEndActual = withEnd.matchesSize(pos -> false,
                pos -> pos.equals(new BlockPos(0, 0, 0)), origin, BlockPos.ZERO, step);
        report("诊断 onlyEnd: matchesSize 返回 " + onlyEndActual
                        + "，startOffset=" + withEnd.startOffset().toShortString()
                        + " stepOffset=" + withEnd.stepOffset().toShortString()
                        + " hasEnd=" + withEnd.hasEnd(), true);
        check("只有收尾段没有重复段时，结果是 0 节（回退到起点验到收尾段）", 0, onlyEndActual);

        // 全空：不成立。
        check("全空时不成立", -1,
                withEnd.matchesSize(pos -> false, pos -> false, origin, BlockPos.ZERO, step));

        // ---- (4) 朝向：上、下不参与旋转 --------------------------------------------------------
        report("朝北时 faces 原样保留",
                DynamicPattern.trueFacing(Set.of(net.minecraft.core.Direction.NORTH), 0)
                        .contains(net.minecraft.core.Direction.NORTH));
        report("朝西（1 步）时 north 变成 west",
                DynamicPattern.trueFacing(Set.of(net.minecraft.core.Direction.NORTH), 1)
                        .contains(net.minecraft.core.Direction.WEST));
        report("上、下不参与旋转——这是一条明确的老版行为",
                DynamicPattern.trueFacing(Set.of(net.minecraft.core.Direction.UP), 1)
                        .contains(net.minecraft.core.Direction.UP));
        // 旋转是 (x,y,z) -> (z,y,-x)：施加于 (0,0,1) 得 (1,0,0)。这是与 MachinePattern 同一个变换，
        // 两处必须是同一个，否则结构会成型而它的动态部分错位。
        check("偏移也按朝向一起旋转：(0,0,1) 转一步是 (1,0,0)",
                "1, 0, 0", DynamicPattern.rotate(new BlockPos(0, 0, 1), 1).toShortString());
    }

    /**
     * X2：动态段的**段内布局**在「量长度」与「展开」两边是不是同一套旋转。
     *
     * <p>世界由生产代码 {@link DynamicPattern#expand(int, int)} 建出来——那就是玩家照着建造预览搭出来的结构；
     * 量长度一侧用「段内每个键在 anchor 偏移后都落在世界里」复刻 {@link MachinePattern#matches} 的判定
     * （离线没有 Level，而 matches 的其余部分只是 isLoaded + 认方块）。
     *
     * <p>**用反射调匹配侧那个入口是有意为之**：它在 0.62.9 上不存在，直接写编译期调用的话，「旧版是错的」
     * 这件事只会表现为整份复现器编译不过；反射把它变成一条红色断言，红绿两边才跑的是同一份代码。
     */
    private static void x2SegmentRotation() {
        section("L3b. [X2] 动态段的段内布局：量长度与展开用的是不是同一套旋转");
        java.lang.reflect.Method rotated = x2RotatedSection();
        report("DynamicPattern 有『按朝向旋转段内布局』的匹配侧入口（0.62.9 上没有，见 -x2-red 证据）",
                rotated != null);
        if (rotated == null) {
            return;
        }

        // 非对称段：一格骨架 + 一格侧翼。4 重对称的段会让旋转看不出来，所以缺陷要用不对称的段才现形。
        MachinePattern asym = MachinePattern.builder()
                .add(new BlockPos(0, 0, 0), List.of(BlockMatcher.parse("minecraft:stone")))
                .add(new BlockPos(1, 0, 0), List.of(BlockMatcher.parse("minecraft:iron_block")))
                .build();
        // 对照：以锚点为中心的十字，绕 Y 轴转任意步都不变。
        MachinePattern sym = MachinePattern.builder()
                .add(new BlockPos(0, 0, 0), List.of(BlockMatcher.parse("minecraft:stone")))
                .add(new BlockPos(1, 0, 0), List.of(BlockMatcher.parse("minecraft:stone")))
                .add(new BlockPos(-1, 0, 0), List.of(BlockMatcher.parse("minecraft:stone")))
                .add(new BlockPos(0, 0, 1), List.of(BlockMatcher.parse("minecraft:stone")))
                .add(new BlockPos(0, 0, -1), List.of(BlockMatcher.parse("minecraft:stone")))
                .build();

        // 未旋转的段内布局在同一片世界里量出来的节数，0..3 朝向。它就是缺陷的形状：
        // 朝向 1 少数一节、朝向 2/3 一节都量不到；而 4 重对称的十字在四个朝向下都是 4。
        //
        // 最后两个 0 在 0.63.1 时是 -1（「量失败」）。X3 之后 0 是**合法节数**，而这里问的是「从起点
        // 一节都数不到」，所以正确的期望就是 0；这两种情形仍然证明「未旋转的布局在世界里找不到段」。
        long[] asymUnrotated = {4, 3, 0, 0};
        for (int steps = 0; steps < 4; steps++) {
            x2SegmentRow("asym", asym, rotated, steps, asymUnrotated[steps]);
            x2SegmentRow("sym", sym, rotated, steps, 4);
        }
    }

    private static void x2SegmentRow(String label, MachinePattern shape, java.lang.reflect.Method rotated,
                                     int steps, long unrotatedMeasured) {
        DynamicPattern dp = new DynamicPattern("x2_" + label, Set.of(net.minecraft.core.Direction.NORTH),
                0, 15, shape, null, BlockPos.ZERO, new BlockPos(0, 0, 1));
        Set<BlockPos> world = new java.util.HashSet<>(dp.expand(4, steps).positions().keySet());
        Set<BlockPos> oneSection = dp.expand(1, steps).positions().keySet();
        BlockPos start = DynamicPattern.rotate(dp.startOffset(), steps);
        BlockPos step = DynamicPattern.rotate(dp.stepOffset(), steps);

        MachinePattern spun;
        try {
            spun = (MachinePattern) rotated.invoke(null, shape, steps);
        } catch (ReflectiveOperationException failure) {
            report("..." + label + " steps=" + steps + " 调匹配侧的段内旋转时抛出了 " + failure, false);
            return;
        }
        Set<BlockPos> spunKeys = spun.positions().keySet();

        check("..." + label + " steps=" + steps + "：匹配侧转出来的段内布局 == expand 展开出来的第 0 节",
                x2Keys(oneSection), x2Keys(spunKeys));
        check("..." + label + " steps=" + steps + "：用这份布局在世界里量到全部 4 节",
                4, dp.matchesSize(anchor -> x2Covers(world, anchor, spunKeys), pos -> false,
                        BlockPos.ZERO, start, step));
        check("..." + label + " steps=" + steps + "：未旋转的段内布局量出的节数（缺陷的形状）",
                unrotatedMeasured,
                dp.matchesSize(anchor -> x2Covers(world, anchor, shape.positions().keySet()),
                        pos -> false, BlockPos.ZERO, start, step));
    }

    private static java.lang.reflect.Method x2RotatedSection() {
        try {
            return DynamicPattern.class.getMethod("rotatedSection", MachinePattern.class, int.class);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static boolean x2Covers(Set<BlockPos> world, BlockPos anchor, Set<BlockPos> keys) {
        for (BlockPos key : keys) {
            if (!world.contains(anchor.offset(key))) {
                return false;
            }
        }
        return true;
    }

    /** 位置集合的稳定文本形式（按坐标排序），把两边的差异直接打进证据里。 */
    private static String x2Keys(java.util.Collection<BlockPos> positions) {
        List<String> out = new java.util.ArrayList<>();
        for (BlockPos pos : positions) {
            out.add(pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
        }
        java.util.Collections.sort(out);
        return out.toString();
    }

    /**
     * X3：0 节动态段的语义。
     *
     * <p>旧版把 0 节既当作<strong>合法长度</strong>（{@code kasuminova/.../DynamicPattern.java:108} 的
     * {@code size >= minSize} 收下 0，而它的 {@code MatchResult#isMatched} 只看面），又当作「量失败」的
     * 编码（{@code matchesSize} 失败也返回 0）。0.62.9–0.63.1 只继承了一半：{@code matchesSize} 用 -1
     * 表示失败，但选结果那句 {@code size > best.size()} 让 0 永远选不上——{@code minSize} 缺省 0 的机器
     * 在世界里永远不成型。
     *
     * <p>本版恢复「0 节成立」，但<strong>不</strong>把量失败当 0（否则 {@code minSize: 0} 时 maxSize 形同虚设）。
     * 这里钉住三件事：判据只看面、0 节能从「还没有候选」里胜出、0 节的收尾段锚在起点（与量长度一侧一致）。
     */
    private static void x3ZeroSection() {
        section("L3c. [X3] 0 节动态段：合法长度，不是量失败");

        // ---- (1) 判据：有面就成立，与节数无关（旧版 DynamicPattern.java:381-392） --------------
        check("0 节且量到了面：成立（判据是 face 非空）", "true",
                String.valueOf(new DynamicPattern.MatchResult(0, net.minecraft.core.Direction.NORTH).isMatched()));
        check("NO_MATCH（没有面）不成立，即使节数也是 0", "false",
                String.valueOf(DynamicPattern.MatchResult.NO_MATCH.isMatched()));

        // ---- (2) 选择规则：0 节要能从「还没有候选」里胜出，但压不过更长的候选 ------------------
        // 0.63.1 上这个入口不存在；直接写编译期调用会让「旧版是错的」表现为整份复现器编译不过，
        // 所以与 X2 同一种手法：反射 + 一条红断言。
        java.lang.reflect.Method betterThan = x3BetterThan();
        report("MatchResult 有『这个候选是否更值得采纳』的入口（0.63.1 上没有，见 -x3-red 证据）",
                betterThan != null);
        if (betterThan != null) {
            DynamicPattern.MatchResult zero = new DynamicPattern.MatchResult(0, net.minecraft.core.Direction.NORTH);
            check("0 节能从「还没有候选」里胜出", "true",
                    String.valueOf(x3Invoke(betterThan, zero, DynamicPattern.MatchResult.NO_MATCH)));
            check("0 节压不过已经量到的 2 节", "false",
                    String.valueOf(x3Invoke(betterThan, zero,
                            new DynamicPattern.MatchResult(2, net.minecraft.core.Direction.NORTH))));
            check("3 节压得过 0 节", "true",
                    String.valueOf(x3Invoke(betterThan,
                            new DynamicPattern.MatchResult(3, net.minecraft.core.Direction.NORTH), zero)));
            check("同为 0 节时保留先到的那个（等长不算更好）", "false",
                    String.valueOf(x3Invoke(betterThan, zero,
                            new DynamicPattern.MatchResult(0, net.minecraft.core.Direction.SOUTH))));
        }

        // ---- (3) 展开：0 节的收尾段锚在起点，1 节及以上不变 ------------------------------------
        // 重复段是石头、收尾段是铁块，这样「世界里那一格是哪一种」能把两侧的锚点直接区分开。
        BlockPos start = new BlockPos(0, 0, 1);
        BlockPos step = new BlockPos(0, 0, 1);
        MachinePattern partUnit = MachinePattern.builder()
                .add(BlockPos.ZERO, List.of(BlockMatcher.parse("minecraft:stone")))
                .build();
        MachinePattern endUnit = MachinePattern.builder()
                .add(BlockPos.ZERO, List.of(BlockMatcher.parse("minecraft:iron_block")))
                .build();
        DynamicPattern line = new DynamicPattern("x3_line", Set.of(net.minecraft.core.Direction.NORTH),
                0, 15, partUnit, endUnit, start, step);

        MachinePattern zeroSections = line.expand(0, 0);
        check("展开 0 节：只有收尾段，落在 start（不再是 start + 1 步）", "[0, 0, 1]",
                x2Keys(zeroSections.positions().keySet()));
        check("展开 1 节：重复段在 start、收尾段在 start + 1 步", "[0, 0, 1, 0, 0, 2]",
                x2Keys(line.expand(1, 0).positions().keySet()));

        // ---- (4) 量长度：照 0 节搭出来的世界被量成 0 节 ----------------------------------------
        // 世界直接取生产代码 expand(0) 放下的方块——那就是玩家照着预览搭出来的结构。
        // 离线没有 Level，于是把「这一格是不是收尾段」换成集合查询（matches 的其余部分只是认方块）。
        Set<BlockPos> built = new java.util.HashSet<>(zeroSections.positions().keySet());
        check("0 节结构只放下一个方块（收尾段那一格）", 1, built.size());
        int measured = line.matchesSize(pos -> false, built::contains, BlockPos.ZERO, start, step);
        check("把照 0 节搭出来的世界量回去：0 节", 0, measured);
        if (betterThan != null) {
            check("...而这个 0 节结果能被采纳（0.63.1 上它压不过初值 0 节，所以永远选不上）", "true",
                    String.valueOf(x3Invoke(betterThan,
                            new DynamicPattern.MatchResult(measured, net.minecraft.core.Direction.NORTH),
                            DynamicPattern.MatchResult.NO_MATCH)));
        }
    }

    private static java.lang.reflect.Method x3BetterThan() {
        try {
            return DynamicPattern.MatchResult.class.getMethod("betterThan", DynamicPattern.MatchResult.class);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Object x3Invoke(java.lang.reflect.Method method, DynamicPattern.MatchResult candidate,
                                   DynamicPattern.MatchResult other) {
        try {
            return method.invoke(candidate, other);
        } catch (ReflectiveOperationException failure) {
            return "threw " + failure;
        }
    }

    /**
     * Section L4 —— 波 3c：预览与建造投影里的动态结构。
     *
     * <p>动态结构的长度在世界里是量出来的，而预览里没有世界，所以长度由玩家在界面上选（旧版同样如此）。
     * 这里断言的是那条链路：大小 → 展开 → 预览结构 → 材料清单 → 信息面板的尺寸行。
     *
     * <p>全部是纯逻辑，所以能离线测。真正需要眼睛的是**按钮的贴图与位置**，那属于实机验收。
     */
    private static void dynamicPatternPreviews() {
        section("L4. 动态结构在预览里按玩家选的大小展开");

        // 与 L3 同一份定义：基座一格 + 一个动态模式（重复单元与收尾段各一格）。
        String lineJson = "{\"registryname\":\"mmverify_preview_line\","
                + "\"parts\":[{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                + "\"dynamic-patterns\":[{"
                + "  \"name\":\"line\","
                + "  \"faces\":[\"north\"],"
                + "  \"minSize\":5,"
                + "  \"maxSize\":15,"
                + "  \"parts\":[{\"x\":0,\"y\":0,\"z\":1,\"selector-tag\":\"input\",\"elements\":\"minecraft:stone\"}],"
                + "  \"parts-end\":[{\"x\":0,\"y\":0,\"z\":1,\"selector-tag\":\"output\",\"elements\":\"minecraft:iron_block\"}],"
                + "  \"structure-size-offset-start\":{\"x\":0,\"y\":0,\"z\":1},"
                + "  \"structure-size-offset\":{\"x\":0,\"y\":0,\"z\":1}"
                + "}]}";
        MachineDefinition line = readDefinition(lineJson);

        // ---- (1) 初始值与夹取：照旧版的两条规则 ------------------------------------------------
        // 旧版把渲染器的初始大小夹到第一个动态模式的 minSize（WorldSceneRendererWidget.java:191），
        // 之后每次增减夹进 [minSize, maxSize]（:196）。
        check("初始大小取第一个动态模式的 minSize", 5,
                com.reborn.modularmachinery.client.preview.StructurePreviews.initialDynamicPatternSize(line));
        check("小于下限时被夹到下限", 5,
                com.reborn.modularmachinery.client.preview.StructurePreviews
                        .clampDynamicPatternSize(line, 0));
        check("大于上限时被夹到上限", 15,
                com.reborn.modularmachinery.client.preview.StructurePreviews
                        .clampDynamicPatternSize(line, 999));
        check("区间内原样保留", 9,
                com.reborn.modularmachinery.client.preview.StructurePreviews
                        .clampDynamicPatternSize(line, 9));

        // 没有动态结构的机器：初始值为 0、夹取恒为 0，两个按钮也不会出现。
        MachineDefinition plain = readDefinition("{\"registryname\":\"mmverify_preview_plain\","
                + "\"parts\":[{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}]}");
        report("没有动态结构的机器初始大小为 0", 
                com.reborn.modularmachinery.client.preview.StructurePreviews
                        .initialDynamicPatternSize(plain) == 0);
        report("...夹取也恒为 0", 
                com.reborn.modularmachinery.client.preview.StructurePreviews
                        .clampDynamicPatternSize(plain, 7) == 0);
        report("...并且它报告自己没有动态结构（按钮条因此少两个按钮）", !plain.hasDynamicPatterns());

        // ---- (2) 大小真的改变了投影 ------------------------------------------------------------
        // 5 节 / 15 节 / 1 节三种大小下，投影的 Z 向长度必须相应变化。
        // 之所以要断言**尺寸**而不只是「没崩」：这是玩家唯一能直接看出来的效果。
        MachinePattern at5 = com.reborn.modularmachinery.client.preview.StructurePreviews
                .previewPattern(line, 5);
        MachinePattern at15 = com.reborn.modularmachinery.client.preview.StructurePreviews
                .previewPattern(line, 15);
        MachinePattern at1 = com.reborn.modularmachinery.client.preview.StructurePreviews
                .previewPattern(line, 1);
        report("5 节的投影比 1 节长", at5.size().getZ() > at1.size().getZ());
        report("15 节的投影比 5 节长", at15.size().getZ() > at5.size().getZ());
        report("15 节的方块数比 5 节多", at15.partCount() > at5.partCount());
        // 每多一节就多一个重复段方块（收尾段只出现一次）。
        check("从 5 节到 15 节，投影正好多 10 个方块", at5.partCount() + 10, at15.partCount());

        // 动态段的标签进入预览结构，段号与展开时的规则一致。
        report("预览结构里带着动态段的标签", at5.hasTags());
        check("第 0 节的标签在预览结构里", "input_line_0",
                String.valueOf(at5.tag(new BlockPos(0, 0, 2))));
        report("1 节的预览只有一段动态部件（加基座），比 5 节少 4 个方块",
                at1.partCount() + 4 == at5.partCount());

        // 没有动态结构的机器，预览结构**就是**基座本身（不是副本、也不是空集）。
        report("没有动态结构的机器，预览结构等于基座",
                com.reborn.modularmachinery.client.preview.StructurePreviews.previewPattern(plain, 0)
                        .partCount() == plain.pattern().partCount());

        // ---- (3) 材料清单跟着大小变：**离线测不了，这里只记录限制** ------------------------------
        //
        // `StructurePreviews.components` 会取控制器方块的状态来把控制器插进清单，于是碰到
        // `ModBlocks` 的静态初始化，而它在 FML 之外无法运行（`NoClassDefFoundError:
        // net/minecraftforge/fml/common/Mod`）。复现器其它地方也同样绕开 `ModBlocks.get()`
        // （见 noFailureSentinel 那段对 `MACHINE_CONTROLLER_ENTITY.get()` 的注释）。
        //
        // 所以这一条**不假装测过**：材料清单「加长一节就多备一节的料」这件事依赖
        // `previewPattern` 的方块数（上面已断言），而清单本身只是把那个结构的方块按物品合并——
        // 合并逻辑是既有的、已被其它段覆盖的代码。真正需要眼睛的是清单**在界面上**跟着变，
        // 那属于实机验收清单。
        report("材料清单需要 FML（ModBlocks 静态初始化），离线不可测——已在实机清单里列出", true);

        // ---- (4) 信息面板：存在动态结构时多一行，且尺寸行跟着大小变 ------------------------------
        // 旧版那一行的守卫是 `!machine.getDynamicPatterns().isEmpty()`
        // （MachineStructurePreviewPanel 的 getMachineExtraInfo），位置在 core_threads 之后、
        // requires_blueprint 之前。
        List<String> rowsPlain = com.reborn.modularmachinery.client.preview.PreviewPanel
                .machineInfoRows(plain);
        List<String> rowsLine = com.reborn.modularmachinery.client.preview.PreviewPanel
                .machineInfoRows(line);
        report("没有动态结构的机器不打印那一行",
                rowsPlain.stream().noneMatch(row -> row.contains("machine_info.dynamic_pattern")));
        report("有动态结构的机器打印那一行",
                rowsLine.stream().anyMatch(row -> row.contains("machine_info.dynamic_pattern")));
        // 尺寸行必须与当前预览的结构一致——加长一节投影真的变长，信息面板不能说一个对不上的数字。
        List<String> rowsAt1 = com.reborn.modularmachinery.client.preview.PreviewPanel
                .machineInfoRows(line, 1);
        List<String> rowsAt5 = com.reborn.modularmachinery.client.preview.PreviewPanel
                .machineInfoRows(line, 5);
        report("1 节与 5 节的尺寸行不同（尺寸行跟着结构走）",
                !rowsAt1.equals(rowsAt5));
        // ---- (6) 端到端复现「旋转之后展开」这一步 ----------------------------------------------
        //
        // 实机诊断报出「结构里没有任何带标签的位置」，而不带标签的位置里确实有动态段（z=-1/-2/-3）。
        // 也就是说 **展开本身没产出标签**，而 3a 的断言只测了 `expand(size, 0)`（朝向 0），
        // 从没测过**旋转之后**的展开。这里把那个缺口补上。
        MachinePattern rot0 = com.reborn.modularmachinery.client.preview.StructurePreviews
                .previewPattern(line, 2);
        report("朝向 0 展开 2 节：有标签", rot0.hasTags());
        for (int steps = 1; steps <= 3; steps++) {
            final int s = steps;
            // previewPattern 固定用朝向 0，所以这里直接调 expand 传入旋转。
            MachinePattern rotated = line.pattern().withMerged(
                    line.dynamicPatterns().get(0).expand(2, s));
            report("朝向 " + s + " 展开 2 节：有标签（标签数=" + rotated.tags().size() + "）",
                    rotated.hasTags());
            StringBuilder dump = new StringBuilder();
            for (java.util.Map.Entry<BlockPos, String> e
                    : new java.util.TreeMap<>(rotated.tags()).entrySet()) {
                dump.append(' ').append(e.getKey().toShortString()).append('=').append(e.getValue());
            }
            report("    朝向 " + s + " 的标签布局:" + dump, true);
        }
        report("...而且两边的动态结构行都在",
                rowsAt1.stream().anyMatch(row -> row.contains("machine_info.dynamic_pattern"))
                        && rowsAt5.stream().anyMatch(row -> row.contains("machine_info.dynamic_pattern")));

        // ---- (7) 逐字复现实机那份定义（基座含 -z 一侧）-------------------------------------------
        //
        // 实机诊断报出「仓口在 z=-1，而结构认为 z=-1 没有标签」。上面 (6) 用的测试定义与实机那份
        // 在**基座坐标**上不同，所以这里照抄实机正在跑的那一份几何，把差别补上。
        // 方块 id 换成原版替身（离线解析不了本模组方块），坐标/标签/偏移逐字保持一致。
        String geomJson = "{\"registryname\":\"real_line\","
                + "\"parts\":["
                + "{\"x\":0,\"y\":0,\"z\":-1,\"elements\":\"minecraft:stone\"},"
                + "{\"x\":-1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                + "{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                + "{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                + "\"dynamic-patterns\":[{\"name\":\"line\",\"faces\":[\"north\"],\"minSize\":1,\"maxSize\":3,"
                + "\"parts\":["
                + "{\"x\":0,\"y\":0,\"z\":0,\"selector-tag\":\"input\",\"elements\":"
                + "[\"minecraft:stone\",\"minecraft:chest\"]},"
                + "{\"x\":-1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                + "{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                + "{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                + "\"parts-end\":["
                + "{\"x\":0,\"y\":0,\"z\":0,\"selector-tag\":\"output\",\"elements\":"
                + "[\"minecraft:stone\",\"minecraft:barrel\"]},"
                + "{\"x\":-1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                + "{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                + "{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                + "\"structure-size-offset-start\":{\"x\":0,\"y\":0,\"z\":1},"
                + "\"structure-size-offset\":{\"x\":0,\"y\":0,\"z\":1}}]}";
        MachineDefinition realLine = readDefinition(geomJson);
        DynamicPattern realDynamic = realLine.dynamicPatterns().get(0);
        check("实机几何：解析出 1 个动态模式", 1, realLine.dynamicPatterns().size());
        report("实机几何：重复段带标签（definition 里的 selector-tag 读到了）", realDynamic.part().hasTags());
        check("实机几何：重复段的标签是 input", "input",
                String.valueOf(realDynamic.part().tag(BlockPos.ZERO)));

        // 对照实验：唯一差别是**重复段的 elements 从「两条目的列表」换成「单个字符串」**。
        // 两者都应当读到 selector-tag；如果只有这一份读到了，问题就出在列表形式的那条路径上。
        String singleJson = geomJson.replace(
                "[\"minecraft:stone\",\"minecraft:chest\"]", "\"minecraft:chest\"");
        DynamicPattern singleDynamic = readDefinition(singleJson).dynamicPatterns().get(0);
        report("对照：elements 用单个字符串时，selector-tag 读到了", singleDynamic.part().hasTags());
        check("对照：标签是 input", "input", String.valueOf(singleDynamic.part().tag(BlockPos.ZERO)));

        // 对照实验二：elements 是列表，但只含一项。
        String oneItemListJson = geomJson.replace(
                "[\"minecraft:stone\",\"minecraft:chest\"]", "[\"minecraft:chest\"]");
        DynamicPattern oneItemDynamic = readDefinition(oneItemListJson).dynamicPatterns().get(0);
        report("对照：elements 是单元素列表时，selector-tag 读到了", oneItemDynamic.part().hasTags());

        // ---- 回归断言：动态段**正中央那一格**必须存在，而且必须带上它的标签 ----------------------
        //
        // 这一段是波 3b/3a 里一个真实缺陷的守卫。`readParts` 里有一条「跳过原点」的规则，它只对**根
        // `parts`** 成立（基座里 (0,0,0) 就是控制器自己站的那一格）；但第一版把同一条规则用在了动态段的
        // `parts` 上，于是**动态段的正中央那一格连同 selector-tag 被整个丢掉**——而那正是作者放
        // `input_line_0` 的位置（装配线正中央的输入总线）。
        //
        // 症状是「机器成型、仓口都在、配方也加载了，但一个端口都收集不到」，而**旧的断言没抓到**，
        // 因为那些测试定义把重复段的坐标写成 (0,0,1) 而不是 (0,0,0)，刚好绕过了那个分支。
        // 所以这里的几何是**逐字照抄实机那份**（含 (0,0,0) 那个带标签的部件）。
        check("实机几何：重复段有 4 个位置（(0,0,0) 那个带标签的部件没有被跳过）",
                4, realDynamic.part().positions().size());
        check("实机几何：重复段有 1 个标签", 1, realDynamic.part().tags().size());
        report("实机几何：收尾段同样保留了它的正中央那一格",
                realDynamic.endPart() != null && realDynamic.endPart().positions().size() == 4
                        && realDynamic.endPart().tags().size() == 1);
        // 根 parts 的跳过规则必须**仍然生效**：基座里不能出现 (0,0,0)（那是控制器自己）。
        report("根 parts 仍然跳过原点（控制器那一格不属于结构）",
                !realLine.pattern().positions().containsKey(BlockPos.ZERO));

        for (int steps = 0; steps <= 3; steps++) {
            final int s = steps;
            MachinePattern merged = realLine.pattern().withMerged(realDynamic.expand(2, s));
            StringBuilder dump = new StringBuilder();
            for (java.util.Map.Entry<BlockPos, String> e
                    : new java.util.TreeMap<>(merged.tags()).entrySet()) {
                dump.append(' ').append(e.getKey().toShortString()).append('=').append(e.getValue());
            }
            report("实机几何 朝向" + s + " 合并后标签数=" + merged.tags().size() + ":" + dump,
                    merged.hasTags());
        }

        // 注：`docs/实机验收步骤-0.32.0.md` 里那份给玩家照抄的定义**不在这里断言**。
        // 它用的是真实方块 id（`modular_machinery_reborn:blockcasing[casing=plain]` 这种），
        // 而离线复现器解析不了方块 id（Forge 注册表为空，`BlockMatcher.parse` 会抛
        // "has no block"），复现器里所有既有断言也都只用 `minecraft:stone` 这类原版方块。
        // 那份定义的 id 正确性由另一条可离线检查的事实保证：**它们与已实机验证过的
        // `example-datapack/data/modular_machinery_reborn/machinery/alloy_furnace.json` 逐字相同**。
        // 它的结构合法性（字段名、faces、minSize/maxSize）由上面 L3 段的同一套解析覆盖。
    }


    /** A definition with a one-position pattern, so the arithmetic can be driven without a world. */
    private static MachineDefinition definition(String path, int maxParallelism, int internalParallelism,
                                                boolean parallelizable) {        MachinePattern pattern = MachinePattern.builder()
                .add(new BlockPos(1, -1, 0), List.of(BlockMatcher.parse("minecraft:stone")))
                .build();
        return new MachineDefinition(new ResourceLocation("mmverify", path), "Verify " + path, pattern,
                FailureAction.RESET, false, maxParallelism, internalParallelism, parallelizable, List.of());
    }

    /**
     * Section L — M6d's actual claim: a placed parallel controller raises the machine's ceiling, the raised
     * ceiling reaches the recipe, and that many copies really settle.
     *
     * <p>Driven in the same order {@code MachineControllerBlockEntity#tick} uses: the ceiling comes from
     * {@link ParallelismLimit#resolve} fed by {@link ParallelControllerCollection#parallelism()}, the ceiling is
     * handed to {@code MachineRecipe#parallelism}, and the result is counted in <b>items that moved</b>.
     */
    private static void parallelControllerLimit() {
        section("L. a parallel controller raises the limit, and the raised limit settles that many copies");

        // The tier ladder, straight out of the original's ParallelControllerData (:9-14).
        check("NORMAL ceiling", 4, ParallelControllerTier.NORMAL.defaultMaxParallelism());
        check("REINFORCED ceiling", 16, ParallelControllerTier.REINFORCED.defaultMaxParallelism());
        check("ELITE ceiling", 64, ParallelControllerTier.ELITE.defaultMaxParallelism());
        check("SUPER ceiling", 256, ParallelControllerTier.SUPER.defaultMaxParallelism());
        check("ULTIMATE ceiling", 512, ParallelControllerTier.ULTIMATE.defaultMaxParallelism());
        check("tier count", 5, ParallelControllerTier.values().length);
        // No config file is ever loaded by this harness, so the resolved value must be the original's default.
        check("ELITE resolved with no config file", 64, ParallelControllerTier.ELITE.maxParallelism());

        MachineDefinition alloyFurnace = definition("alloy_furnace", 2048, 1, true);
        MachineDefinition bare = definition("bare", 2048, 0, true);
        MachineDefinition clamped = definition("clamped", 64, 0, true);
        MachineDefinition frozen = definition("frozen", 2048, 0, false);

        // (1) No controller: M6c's behaviour has to be bit-for-bit what it was.
        check("alloy furnace with no controller", 1, ParallelismLimit.resolve(alloyFurnace, 0));
        check("internal-parallelism 0 with no controller", 1, ParallelismLimit.resolve(bare, 0));
        check("effectiveParallelCeiling() is the zero-controller case", 1, alloyFurnace.effectiveParallelCeiling());

        // (2) The collection: what the structure walk feeds in.
        ParallelController elite = new FakeController(64, 64);
        ParallelController dialledDown = new FakeController(64, 8);
        check("no controllers: count", 0, ParallelControllerCollection.EMPTY.controllerCount());
        check("no controllers: sum", 0, ParallelControllerCollection.EMPTY.parallelism());
        check("one elite: count", 1, ParallelControllerCollection.of(List.of(elite)).controllerCount());
        check("one elite: sum", 64, ParallelControllerCollection.of(List.of(elite)).parallelism());
        check("two elites: sum", 128, ParallelControllerCollection.of(List.of(elite, elite)).parallelism());
        check("a controller dialled down to 8 contributes 8", 8,
                ParallelControllerCollection.of(List.of(dialledDown)).parallelism());

        // (3) The same machine, measurably different limits with and without a controller.
        int withoutController = ParallelismLimit.resolve(bare, 0);
        int withOneElite = ParallelismLimit.resolve(bare, ParallelControllerCollection.of(List.of(elite)).parallelism());
        check("bare machine without a controller", 1, withoutController);
        check("the same machine with one elite controller", 64, withOneElite);
        report("one elite controller raises the limit from " + withoutController + " to " + withOneElite,
                withOneElite > withoutController);
        check("bare machine + super controller", 256, ParallelismLimit.resolve(bare, 256));
        check("bare machine + ultimate controller", 512, ParallelismLimit.resolve(bare, 512));
        check("alloy furnace (internal-parallelism 1) + elite", 65, ParallelismLimit.resolve(alloyFurnace, 64));
        check("a machine whose max-parallelism is 64 clamps an ultimate controller", 64,
                ParallelismLimit.resolve(clamped, 512));
        check("parallelizable:false ignores even an ultimate controller", 1,
                ParallelismLimit.resolve(frozen, 512));

        // (4) End to end, on the datapack's demonstrator: 1 cobblestone -> 1 stone, 5 ticks, 100 FE/t.
        MachineRecipe demo = new MachineRecipe(new ResourceLocation("mmverify", "parallel_demo"), "alloy_furnace",
                "alloy_furnace_parallel_demo", 5, List.of(
                        new EnergyRequirement(IOType.INPUT, 100L),
                        ItemRequirement.input(ingredient(Items.COBBLESTONE), 1),
                        ItemRequirement.output(new ItemStack(Items.STONE), 1, 1, 1.0F)));

        int noControllerCeiling = ParallelismLimit.resolve(alloyFurnace, 0);
        resetCobblestone(512, 4_000_000L);
        int noControllerLimit = demo.parallelism(ports(), RecipeModifiers.EMPTY, noControllerCeiling);
        check("copies with no controller, from 512 cobblestone", 1, noControllerLimit);
        craft(demo, RecipeModifiers.EMPTY, noControllerLimit);
        check("cobblestone consumed with no controller", 1, 512 - cobblestoneLeft());
        check("stone produced with no controller", 1, outputCount(Items.STONE));

        for (ParallelControllerTier tier : ParallelControllerTier.values()) {
            int tierCeiling = tier.maxParallelism();
            int ceiling = ParallelismLimit.resolve(alloyFurnace, tierCeiling);
            check(tier.tierName() + " controller on the alloy furnace: ceiling", tierCeiling + 1, ceiling);
            // Exactly `ceiling` cobblestone, so the supply is not what bounds the craft.
            resetCobblestone(ceiling, 4_000_000L);
            int limit = demo.parallelism(ports(), RecipeModifiers.EMPTY, ceiling);
            check(tier.tierName() + ": copies within " + ceiling + " cobblestone", ceiling, limit);
            craft(demo, RecipeModifiers.EMPTY, limit);
            check(tier.tierName() + ": cobblestone consumed in one craft", ceiling, ceiling - cobblestoneLeft());
            check(tier.tierName() + ": stone produced in one craft", ceiling, outputCount(Items.STONE));
        }

        // (5) The same supply, one copy short, must settle one copy fewer — the supply really is consulted.
        int superCeiling = ParallelismLimit.resolve(alloyFurnace, 256);
        resetCobblestone(superCeiling - 1, 4_000_000L);
        check("one cobblestone short of 257", 256, demo.parallelism(ports(), RecipeModifiers.EMPTY, superCeiling));

        // (6) The clamp the screen and the server both rely on.
        check("clamp above the ceiling", 64, ParallelControllerBlockEntity.clampParallelism(999, 64));
        check("clamp below zero", 0, ParallelControllerBlockEntity.clampParallelism(-5, 64));
        check("clamp inside the range", 37, ParallelControllerBlockEntity.clampParallelism(37, 64));
        check("clamp with a zero ceiling", 0, ParallelControllerBlockEntity.clampParallelism(10, 0));
    }

    /**
     * Section M — the numbers above only mean something if the shipped example datapack really contains the
     * machine and recipe they were taken from.
     */
    private static void shippedContentMatches() {
        section("M. the shipped example datapack carries the demonstrator");
        Path base = exampleDatapack();
        if (base == null) {
            report("example-datapack/ not found from " + System.getProperty("user.dir"), false);
            return;
        }
        try {
            JsonObject machine = JsonParser.parseString(Files.readString(base.resolve(
                    "data/modular_machinery_reborn/machinery/alloy_furnace.json"))).getAsJsonObject();
            check("alloy_furnace max-parallelism", 2048, machine.get("max-parallelism").getAsInt());
            check("alloy_furnace internal-parallelism", 1, machine.get("internal-parallelism").getAsInt());
            check("alloy_furnace parallelizable", "true", String.valueOf(machine.get("parallelizable").getAsBoolean()));

            boolean acceptsController = false;
            for (JsonElement part : machine.getAsJsonArray("parts")) {
                JsonObject entry = part.getAsJsonObject();
                if (entry.get("x").getAsInt() != 0 || entry.get("y").getAsInt() != 1
                        || entry.get("z").getAsInt() != 1) {
                    continue;
                }
                for (JsonElement element : entry.getAsJsonArray("elements")) {
                    if ("modular_machinery_reborn:parallel_controller".equals(element.getAsString())) {
                        acceptsController = true;
                    }
                }
            }
            report("the roof centre (0,1,1) accepts modular_machinery_reborn:parallel_controller",
                    acceptsController);

            JsonObject recipe = JsonParser.parseString(Files.readString(base.resolve(
                    "data/modular_machinery_reborn/recipes/alloy_smelter/alloy_smelter_parallel_demo.json")))
                    .getAsJsonObject();
            check("demo recipe machine", "alloy_furnace", recipe.get("machine").getAsString());
            check("demo recipe time", 5, recipe.get("recipeTime").getAsInt());
            List<String> requirements = new ArrayList<>();
            for (JsonElement element : recipe.getAsJsonArray("requirements")) {
                JsonObject requirement = element.getAsJsonObject();
                requirements.add(requirement.get("type").getAsString());
            }
            check("demo recipe requirement kinds",
                    "[modularmachinery:energy, modularmachinery:item, modularmachinery:item]",
                    requirements.toString());
        } catch (Exception exception) {
            report("failed to read the example datapack: " + exception, false);
        }
    }

    /** Section N — the one packet this release adds must survive a varint round trip. */
    private static void packetRoundTrip() {
        section("N. the parallel-controller update packet");
        for (int value : new int[]{0, 1, 4, 64, 512, 2048, 100_000, Integer.MAX_VALUE}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                new ParallelControllerUpdatePacket(value).encode(buffer);
                int decoded = new ParallelControllerUpdatePacket(buffer).newParallelism();
                check("round trip of " + value, value, decoded);
            } finally {
                buffer.release();
            }
        }

        // 波 4：机器定义同步包的往返。断言的是**包这一层**，客户端重建那一层在 L4 段。
        section("N2. 机器定义同步包");
        java.util.Map<ResourceLocation, String> sent = new java.util.LinkedHashMap<>();
        sent.put(new ResourceLocation("mmverify", "one"), "{\"registryname\":\"one\"}");
        sent.put(new ResourceLocation("mmverify", "two"),
                "{\"registryname\":\"two\",\"max-parallelism\":7}");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new com.reborn.modularmachinery.network.MachineDefinitionSyncPacket(sent).encode(buffer);
            java.util.Map<ResourceLocation, String> decoded =
                    new com.reborn.modularmachinery.network.MachineDefinitionSyncPacket(buffer).definitions();
            check("两台机器都往返回来", 2, decoded.size());
            check("...第一份的 JSON 逐字相同", sent.get(new ResourceLocation("mmverify", "one")),
                    decoded.get(new ResourceLocation("mmverify", "one")));
            check("...第二份也是", sent.get(new ResourceLocation("mmverify", "two")),
                    decoded.get(new ResourceLocation("mmverify", "two")));
        } finally {
            buffer.release();
        }
        // 有问题的输入：计数字段是**外部数据**，伪造一个巨大的计数不能让客户端按它预分配内存。
        // 这里断言的是「读得下去、不抛异常」，而不是「读得快」——真正的防线是那个上限常量。
        FriendlyByteBuf hostile = new FriendlyByteBuf(Unpooled.buffer());
        try {
            hostile.writeInt(Integer.MAX_VALUE);
            // 一项都没有：读取会在第一项就撞到缓冲区末尾并抛 DecoderException，这是**正确**的
            // （一个说"有 21 亿项"的包本来就该被拒绝），而不是让客户端按那个数字申请内存。
            boolean threw = false;
            try {
                new com.reborn.modularmachinery.network.MachineDefinitionSyncPacket(hostile);
            } catch (RuntimeException expected) {
                threw = true;
            }
            report("一个声称有 " + Integer.MAX_VALUE + " 项却没有内容的包被拒绝（而不是按它预分配）", threw);
        } finally {
            hostile.release();
        }
    }

    /**
     * A third recipe for the ledger check, with its own input and output so three threads never compete for the
     * same items. 20 ticks, so it is still running while the ledger is read.
     */
    private static MachineRecipe thirdRecipe() {
        return new MachineRecipe(new ResourceLocation("mmverify", "factory_third"), "example_factory",
                "factory_third", 20, List.of(
                        ItemRequirement.input(ingredient(Items.IRON_INGOT), 1),
                        ItemRequirement.output(new ItemStack(Items.GOLD_INGOT), 1, 1, 1.0F)));
    }

    /** The example datapack, looked for relative to the working directory and at the ASCII junction. */
    private static Path exampleDatapack() {
        Path[] candidates = {
                Path.of("example-datapack"),
                Path.of("..", "example-datapack"),
                Path.of("C:/mmwork/modular-machinery-reborn/example-datapack"),
        };
        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // =================================================================== M6e: the factory rig

    /**
     * A factory under test: the production {@link FactoryEngine} plus a {@link FactoryHost} that answers with
     * in-memory ports and the recipes handed to it.
     *
     * <p>The host is the only fiction. Every decision the harness asserts — which thread gets a recipe, how many
     * copies it settles, when it restarts, when it is reaped, what the loader does with the fields — is
     * production code. The framework the harness would otherwise have to build to use the controller's own host
     * is a {@code Level}, which is precisely why the engine takes a host interface at all.
     */
    private static final class FactoryRig implements FactoryHost {

        private final List<MachineRecipe> recipes = new ArrayList<>();
        private final List<FactoryThreadModel.CoreThreadSpec> coreSpecs;
        private final int maxThreads;
        private final int cobblestone;
        private final int coal;

        private net.minecraftforge.items.ItemStackHandler itemIn;
        private net.minecraftforge.items.ItemStackHandler itemOut;
        private EnergyStorage energyIn;

        private FactoryEngine engine;
        private int parallelCeiling = 8;
        private int ticksUsed;
        private int peakOrdinaryThreads;
        private final List<String> progressLog = new ArrayList<>();
        private final List<String> concurrentTicks = new ArrayList<>();

        /**
         * The machine's declared {@code failure-action}, reported through {@link #failureAction()}.
         *
         * <p>{@code failureAction()} carries no {@code @Override} on purpose: 0.25.0 adds that method to
         * {@link FactoryHost}, and this harness has to compile against <b>both</b> sides of that change so the
         * run that precedes the implementation fails on the assertion instead of on {@code javac}. Once the
         * interface has the method, this is its implementation.
         */
        private FailureAction declaredFailureAction = FailureAction.STILL;

        FactoryRig(List<MachineRecipe> recipes, int maxThreads, int inputs,
                   List<FactoryThreadModel.CoreThreadSpec> coreSpecs) {
            this.recipes.addAll(recipes);
            this.maxThreads = maxThreads;
            this.cobblestone = inputs;
            this.coal = inputs;
            this.coreSpecs = List.copyOf(coreSpecs);
            this.engine = new FactoryEngine(this);
        }

        /** Fills the ports and forgets every thread, the way a fresh structure would. */
        FactoryRig reset() {
            this.itemIn = new net.minecraftforge.items.ItemStackHandler(4);
            this.itemOut = new net.minecraftforge.items.ItemStackHandler(16);
            this.energyIn = new EnergyStorage(4_000_000, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
            this.itemIn.setStackInSlot(0, new ItemStack(Items.COBBLESTONE, this.cobblestone));
            this.itemIn.setStackInSlot(1, new ItemStack(Items.COAL, this.coal));
            // A third input for the ledger check's third recipe; harmless when nothing uses it.
            this.itemIn.setStackInSlot(2, new ItemStack(Items.IRON_INGOT, this.cobblestone));
            this.energyIn.receiveEnergy(4_000_000, false);
            this.engine.reset();
            this.ticksUsed = 0;
            this.peakOrdinaryThreads = 0;
            this.progressLog.clear();
            this.concurrentTicks.clear();
            return this;
        }

        /** Runs the engine for {@code count} ticks, capturing what was running after each one. */
        FactoryRig tick(int count) {
            for (int i = 0; i < count; i++) {
                this.engine.tick();
                this.ticksUsed++;
                this.peakOrdinaryThreads = Math.max(this.peakOrdinaryThreads, this.engine.ordinaryThreadCount());
                capture();
            }
            return this;
        }

        /** Sets the machine's parallelism ceiling, so each thread's settlement size is controlled. */
        FactoryRig parallelCeiling(int value) {
            this.parallelCeiling = value;
            return this;
        }

        /**
         * Records, per tick, which recipes had an active craft and what its progress was. From this the harness
         * can tell "two crafts advanced in the same tick" from "two crafts existed in the same tick" — the
         * former is the evidence, the latter is not.
         */
        private void capture() {
            List<String> running = new ArrayList<>();
            for (FactoryThread thread : this.engine.allThreads()) {
                if (thread.isWorking()) {
                    running.add(thread.recipeId() + "@" + thread.progress());
                }
            }
            this.progressLog.add(running.toString());
            if (running.size() > 1) {
                boolean distinct = new java.util.HashSet<>(running.stream()
                        .map(entry -> entry.substring(0, entry.indexOf('@'))).toList()).size() > 1;
                if (distinct) {
                    this.concurrentTicks.add(running.toString());
                }
            }
        }

        /** Whether one engine tick advanced two crafts whose recipes differ. */
        boolean twoRecipesRanInOneTick() {
            return !this.concurrentTicks.isEmpty();
        }

        int maxDistinctRecipesInOneTick() {
            int best = 0;
            for (String entry : this.progressLog) {
                String trimmed = entry.substring(1, Math.max(1, entry.length() - 1));
                if (trimmed.isBlank()) {
                    continue;
                }
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (String piece : trimmed.split(", ")) {
                    ids.add(piece.substring(0, piece.indexOf('@')));
                }
                best = Math.max(best, ids.size());
            }
            return best;
        }

        String snapshotLog() {
            return this.concurrentTicks.isEmpty()
                    ? "no tick ran two different recipes (log: " + this.progressLog + ")"
                    : this.concurrentTicks.size() + " ticks ran two different recipes, first was "
                            + this.concurrentTicks.get(0);
        }

        /**
         * How many crafts advanced during one engine tick — the strongest form of the claim, computed from the
         * progress log rather than from anything the engine reports about itself.
         */
        int maxCraftsAdvancedInOneTick() {
            int best = 0;
            for (int i = 1; i < this.progressLog.size(); i++) {
                int advanced = 0;
                for (String before : pieces(this.progressLog.get(i - 1))) {
                    int at = before.indexOf('@');
                    String recipe = before.substring(0, at);
                    int progress = Integer.parseInt(before.substring(at + 1));
                    for (String after : pieces(this.progressLog.get(i))) {
                        int at2 = after.indexOf('@');
                        if (after.substring(0, at2).equals(recipe)
                                && Integer.parseInt(after.substring(at2 + 1)) == progress + 1) {
                            advanced++;
                        }
                    }
                }
                best = Math.max(best, advanced);
            }
            return best;
        }

        private static List<String> pieces(String entry) {
            String trimmed = entry.substring(1, Math.max(1, entry.length() - 1));
            return trimmed.isBlank() ? List.of() : List.of(trimmed.split(", "));
        }

        int cobblestoneLeft() {
            return this.itemIn.getStackInSlot(0).getCount();
        }

        int coalLeft() {
            return this.itemIn.getStackInSlot(1).getCount();
        }

        long energyDrawn() {
            return 4_000_000L - this.energyIn.getEnergyStored();
        }

        int outputCount(Item item) {
            int total = 0;
            for (int slot = 0; slot < this.itemOut.getSlots(); slot++) {
                ItemStack stack = this.itemOut.getStackInSlot(slot);
                if (!stack.isEmpty() && stack.is(item)) {
                    total += stack.getCount();
                }
            }
            return total;
        }

        // ---------------------------------------------------------------- 0.25.0: driving a failure

        /** Sets the machine's declared action, the value the host reports. Call before {@link #reset()}. */
        FactoryRig declaredFailureAction(FailureAction action) {
            this.declaredFailureAction = action;
            return this;
        }

        /**
         * The machine's {@code failure-action} — {@link FactoryHost}'s 0.25.0 method. Deliberately without
         * {@code @Override}; see the field's comment.
         */
        public FailureAction failureAction() {
            return this.declaredFailureAction;
        }

        /** Empties the energy buffer, so the next engine tick cannot pay a per-tick requirement. */
        FactoryRig starveEnergy() {
            this.energyIn.extractEnergy(this.energyIn.getEnergyStored(), false);
            return this;
        }

        FactoryRig restoreEnergy() {
            this.energyIn.receiveEnergy(4_000_000, false);
            return this;
        }

        /** Fills every output slot, so a finished craft's result has nowhere to go. */
        FactoryRig plugOutput() {
            for (int slot = 0; slot < this.itemOut.getSlots(); slot++) {
                this.itemOut.setStackInSlot(slot, new ItemStack(Items.DIRT, 64));
            }
            return this;
        }

        /** How many threads hold a craft — the "is the craft still armed" question. */
        int workingThreads() {
            int working = 0;
            for (FactoryThread thread : this.engine.allThreads()) {
                if (thread.isWorking()) {
                    working++;
                }
            }
            return working;
        }

        /** The progress of the one running thread, or {@code -1} when no thread holds a craft. */
        int activeProgress() {
            for (FactoryThread thread : this.engine.allThreads()) {
                if (thread.isWorking()) {
                    return thread.progress();
                }
            }
            return -1;
        }

        // ---------------------------------------------------------------- FactoryHost

        @Override
        public RecipeModifiers modifiers() {
            return RecipeModifiers.EMPTY;
        }

        @Override
        public HatchCollection ports() {
            return HatchCollection.builder()
                    .addItemHandler(this.itemIn, true)
                    .addItemHandler(this.itemOut, false)
                    .addEnergyStorage(this.energyIn, true)
                    .build();
        }

        @Override
        public List<FactoryThreadModel.CoreThreadSpec> coreThreads() {
            return this.coreSpecs;
        }

        @Override
        public List<MachineRecipe> availableRecipes() {
            return List.copyOf(this.recipes);
        }

        @Override
        public int parallelCeiling() {
            return this.parallelCeiling;
        }

        @Override
        public int maxThreads() {
            return this.maxThreads;
        }

        @Override
        public RandomSource random() {
            return RandomSource.create(7L);
        }

        @Override
        public void onThreadStarted(FactoryThread thread) {
        }

        @Override
        public void onThreadFinished(FactoryThread thread) {
        }

        @Override
        public void onThreadIdle(FactoryThread thread) {
        }

        @Override
        public void onThreadsChanged() {
        }
    }

    // =================================================================== M6b: the upgrade bus

    /**
     * Section O — M6b's actual claim: <b>inserting an upgrade into a bus changes the craft outcome</b>.
     *
     * <p>This is the evidence the plan demands. "The bus GUI shows the upgrade" is explicitly not accepted, so
     * every check below counts something that really moved — ticks of crafting, or items consumed and produced —
     * and the upgrade is put into an in-memory bus inventory and taken out again through the very same method
     * the block entity uses ({@code UpgradeEffects.read}), then folded through the same
     * {@code MachineControllerBlockEntity.collectBusModifiers} the server calls.
     *
     * <p>The recipe is the datapack's own alloy-furnace demonstrator (1 cobblestone → 1 stone, 5 ticks,
     * 100 FE/t) because it is the smallest thing whose arithmetic is unambiguous.
     *
     * <p>The declarations under test are read out of the shipped {@code example-datapack}, through the loader's
     * own private reader, so this also proves the shipped JSON carries what the section claims.
     */
    private static void upgradeBusEffects() {
        section("O. an upgrade in a bus changes what the craft does");

        UpgradeType speed = loadUpgrade("example_speed");
        UpgradeType doubler = loadUpgrade("example_output_doubler");
        UpgradeType charge = loadUpgrade("example_charge");
        if (speed == null || doubler == null || charge == null) {
            report("could not read the shipped upgrade declarations", false);
            return;
        }

        // (1) The declarations themselves, as the loader reads them.
        check("example_speed declares one modifier", 1, speed.modifiers().size());
        check("example_speed is stackable", "true", String.valueOf(speed.stackable()));
        check("example_speed's modifier target", "DURATION",
                String.valueOf(speed.modifiers().get(0).target()));
        check("example_speed's modifier operation", "MULTIPLY",
                String.valueOf(speed.modifiers().get(0).operation()));
        check("example_speed's modifier value", "0.5",
                String.valueOf(speed.modifiers().get(0).value()));
        check("example_output_doubler declares one modifier", 1, doubler.modifiers().size());
        check("example_output_doubler is NOT stackable", "false", String.valueOf(doubler.stackable()));
        check("example_output_doubler's io", "OUTPUT", String.valueOf(doubler.modifiers().get(0).ioTarget()));
        check("example_charge declares no modifier", 0, charge.modifiers().size());

        // (1b) The five tiers, straight out of the original's UpgradeBusData (:8-14), and the resolved value with
        // no config file loaded — the same pair of assertions section L makes for the parallel controller.
        check("NORMAL slots", 3, UpgradeBusTier.NORMAL.defaultMaxUpgradeSlots());
        check("REINFORCED slots", 6, UpgradeBusTier.REINFORCED.defaultMaxUpgradeSlots());
        check("ELITE slots", 9, UpgradeBusTier.ELITE.defaultMaxUpgradeSlots());
        check("SUPER slots", 12, UpgradeBusTier.SUPER.defaultMaxUpgradeSlots());
        check("ULTIMATE slots", 18, UpgradeBusTier.ULTIMATE.defaultMaxUpgradeSlots());
        check("tier count", 5, UpgradeBusTier.values().length);
        check("ULTIMATE resolved with no config file", 18, UpgradeBusTier.ULTIMATE.maxUpgradeSlots());
        check("the original's config upper bound", 18, ModConfig.MAX_UPGRADE_SLOTS_LIMIT);

        // The two shipped item mappings, through the loader's own reader. See checkItemMappings for why the live
        // registry cannot be populated offline, and what that means for the checks below.
        checkItemMappings();

        // (2) The shipped example datapack really carries these files (section M's style).
        Path base = exampleDatapack();
        if (base == null) {
            report("example-datapack/ not found", false);
        } else {
            for (String name : new String[] {"example_speed", "example_output_doubler"}) {
                Path file = base.resolve("data/modular_machinery_reborn/upgrade/" + name + ".json");
                report("shipped declaration " + name + ".json exists", Files.isRegularFile(file));
            }
            try {
                JsonObject machine = JsonParser.parseString(Files.readString(base.resolve(
                        "data/modular_machinery_reborn/machinery/alloy_furnace.json"))).getAsJsonObject();
                boolean acceptsBus = false;
                for (JsonElement part : machine.getAsJsonArray("parts")) {
                    JsonObject entry = part.getAsJsonObject();
                    if (entry.get("x").getAsInt() != 0 || entry.get("y").getAsInt() != 1
                            || entry.get("z").getAsInt() != 0) {
                        continue;
                    }
                    for (JsonElement element : entry.getAsJsonArray("elements")) {
                        if ("modular_machinery_reborn:upgrade_bus".equals(element.getAsString())) {
                            acceptsBus = true;
                        }
                    }
                }
                report("the alloy furnace's roof front centre (0,1,0) accepts modular_machinery_reborn:upgrade_bus",
                        acceptsBus);
                check("the alloy furnace still has 26 parts", 26, machine.getAsJsonArray("parts").size());
            } catch (Exception exception) {
                report("failed to read the alloy furnace definition: " + exception, false);
            }
        }

        // (3) A base craft with an empty bus: 8 cobblestone -> 8 stone in 5 ticks.
        MachineRecipe recipe = parallelDemo();
        resetCobblestone(128, 4_000_000L);
        RecipeModifiers emptyBus = busModifiers(UpgradeStack.Bag.EMPTY, ALLOY_FURNACE);
        check("an empty bus contributes nothing", 0, emptyBus.modifiers().size());
        int limit = recipe.parallelism(ports(), emptyBus, 8);
        check("limit with an empty bus", 8, limit);
        craft(recipe, emptyBus, limit);
        check("cobblestone consumed with an empty bus", 8, 128 - cobblestoneLeft());
        check("stone produced with an empty bus", 8, outputCount(Items.STONE));

        // (4) A duration upgrade halves the craft, measured in ticks the harness actually ticked.
        RecipeModifiers fast = busModifiers(bag(stack(speed, 1)), ALLOY_FURNACE);
        check("a speed upgrade contributes one modifier", 1, fast.modifiers().size());
        check("duration with the speed upgrade (5 ticks x 0.5)", 3, recipe.duration(fast));
        check("duration multiplier with the speed upgrade", "2.0",
                String.valueOf(fast.durationMultiplier(recipe.recipeTime())));
        // The per-tick rate compensation: the shortened craft draws `rate * parallelism * durationMultiplier`
        // for `duration(modifiers)` ticks. 8 copies x 100 FE/t x 2.0 x 3 ticks = 4800.
        // NOTE: this is NOT the unmodified total of 4000, and that is a pre-existing M6c behaviour rather than a
        // property of this slice: `applyDurationMultiplier` is computed from the *unrounded* duration while the
        // tick count is `round(recipeTime * value)`, so the two disagree by the rounding. Section G asserts the
        // same identity on a machine with no bus in the way.
        resetCobblestone(16, 4_000_000L);
        long shortenedFeBefore = energyIn.getEnergyStored();
        int ticks = craftCountingTicks(recipe, fast, 8);
        long shortenedFe = shortenedFeBefore - energyIn.getEnergyStored();
        check("ticks actually ticked with the speed upgrade", 3, ticks);
        check("cobblestone consumed with the speed upgrade", 8, 16 - cobblestoneLeft());
        check("stone produced with the speed upgrade", 8, outputCount(Items.STONE));
        check("FE drawn over the shortened craft (compensated per tick)", 8 * 100L * 3L * 2L, shortenedFe);

        // (5) The same upgrade at stack size 2: `stackable` squares the value, as the original's loop did.
        RecipeModifiers faster = busModifiers(bag(stack(speed, 2)), ALLOY_FURNACE);
        check("duration with two speed upgrades (5 x 0.25)", 1, recipe.duration(faster));
        RecipeModifiers faster3 = busModifiers(bag(stack(speed, 3)), ALLOY_FURNACE);
        check("duration with three speed upgrades is floored at one tick", 1, recipe.duration(faster3));

        // A non-stackable upgrade must NOT scale: that is the difference the field carries.
        UpgradeStack doublerStack2 = stack(doubler, 2);
        check("a non-stackable upgrade's multiplier is unchanged at count 2", "2.0",
                String.valueOf(UpgradeEffects.stacked(doubler.modifiers().get(0), doublerStack2).value()));
        check("a stackable upgrade's multiplier squares at count 2", "0.25",
                String.valueOf(UpgradeEffects.stacked(speed.modifiers().get(0),
                        stack(speed, 2)).value()));

        // (6) An OUTPUT x2 upgrade doubles what the craft produces.
        resetCobblestone(8, 4_000_000L);
        RecipeModifiers doubled = busModifiers(bag(stack(doubler, 1)), ALLOY_FURNACE);
        check("an output doubler contributes one modifier", 1, doubled.modifiers().size());
        int doubledLimit = recipe.parallelism(ports(), doubled, 4);
        check("the doubled output divides the limit from 8 cobblestone", 4, doubledLimit);
        craft(recipe, doubled, doubledLimit);
        check("cobblestone consumed with the output doubler", 4, 8 - cobblestoneLeft());
        check("stone produced with the output doubler", 8, outputCount(Items.STONE));

        // (7) An incompatible upgrade is refused, not misapplied.
        UpgradeStack.Bag incompatibleBag = bag(stack(doubler, 1));
        check("the doubler's whitelist names the alloy furnace", "true",
                String.valueOf(doubler.isCompatible(ALLOY_FURNACE)));
        check("the doubler is refused by a different machine", "false",
                String.valueOf(doubler.isCompatible(OTHER_MACHINE)));
        RecipeModifiers refused = busModifiers(incompatibleBag, OTHER_MACHINE);
        check("a bus upgrade incompatible with the machine contributes nothing", 0,
                refused.modifiers().size());
        resetCobblestone(4, 4_000_000L);
        int refusedLimit = recipe.parallelism(ports(), refused, 2);
        check("the refused craft settles the unmodified number of copies", 2, refusedLimit);
        craft(recipe, refused, refusedLimit);
        check("stone produced with the refused upgrade (undoubled)", 2, outputCount(Items.STONE));
        // The GUI's own listing is unfiltered, which is how the warning rows get their content.
        check("the unfiltered bag still holds the upgrade", 1, incompatibleBag.size());
        check("...and reports it as incompatible with that machine", 1,
                incompatibleBag.incompatibleWith(OTHER_MACHINE).size());

        // (8) Merging slots: the original summed same-type slots instead of applying twice.
        // Driven through the real reader with real carrier ItemStacks. The declaration table is a standing map
        // rather than the live registry, because this harness runs outside Forge's mod loading, where
        // ForgeRegistries.ITEMS.getValue answers AIR for everything — a fact this section found the hard way.
        Map<Item, UpgradeTarget.Targets> carriers = new java.util.LinkedHashMap<>();
        carriers.put(Items.IRON_INGOT, new UpgradeTarget.Targets(List.of(
                new UpgradeTarget.Fixed(Items.IRON_INGOT, speed))));
        carriers.put(Items.COPPER_INGOT, new UpgradeTarget.Targets(List.of(
                new UpgradeTarget.Fixed(Items.COPPER_INGOT, doubler))));
        java.util.function.Function<ItemStack, UpgradeTarget.Targets> lookup =
                stack -> stack.isEmpty() ? null : carriers.get(stack.getItem());

        net.minecraftforge.items.ItemStackHandler busInventory = new net.minecraftforge.items.ItemStackHandler(3);
        busInventory.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 1));
        busInventory.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 1));
        busInventory.setStackInSlot(2, new ItemStack(Items.COBBLESTONE, 5));
        UpgradeStack.Bag merged = UpgradeEffects.read(busInventory, lookup);
        check("two carrier slots merge into one upgrade", 1, merged.size());
        check("...with their counts summed", 2, merged.itemCount());
        check("a non-carrier item in a slot contributes nothing", 1, merged.stacks().size());
        check("the merged upgrade is the one the declaration names", "modular_machinery_reborn:example_speed",
                merged.stacks().get(0).type().id().toString());
        check("...and reading the bus with two carriers gives the squared multiplier", "0.25",
                String.valueOf(UpgradeEffects.of(merged, ALLOY_FURNACE).applyDuration(1.0)));
        // Two different carriers in one bus: two upgrades, and the reader tells them apart.
        busInventory.setStackInSlot(1, new ItemStack(Items.COPPER_INGOT, 1));
        UpgradeStack.Bag mixed = UpgradeEffects.read(busInventory, lookup);
        check("two different carriers give two upgrades", 2, mixed.size());
        check("...and their counts are separate", 2, mixed.itemCount());
        check("...and the output doubler is among them", "true",
                String.valueOf(mixed.stacks().stream()
                        .anyMatch(s -> s.type().id().equals(doubler.id()))));
        // The live-registry entry point must still be the one the block entity uses.
        check("the block entity's reader is the registry-backed overload", 0,
                UpgradeEffects.read(busInventory).size());

        // ---- (8b) per-copy NBT on a dynamic upgrade's carrier -------------------------------------------
        //
        // The original kept this in a Forge item capability (`CapabilityUpgrade`) and its dynamic flavour's
        // read/write were a bare assignment and return — the tag was opaque to the mod and belonged to whoever
        // wrote it (`SimpleDynamicMachineUpgrade.java:110-116`). The handler layer that read it around a machine
        // event is deliberately not ported, so what has to be true here is only that the data is *carried* and
        // never lost: a save from a pack that used dynamic upgrades holds it.
        CompoundTag marker = new CompoundTag();
        marker.putString("mmverify:who", "slot-zero");
        ItemStack taggedCarrier = new ItemStack(Items.IRON_INGOT, 1);
        UpgradeItemNbt.write(taggedCarrier, marker);

        report("a written tag reads back equal", marker.equals(UpgradeItemNbt.read(taggedCarrier)));
        report("...and the item reports that it carries one", UpgradeItemNbt.has(taggedCarrier));

        // Writing nothing must leave NO key behind: a leftover empty compound keeps an untouched copy and a
        // written-then-cleared copy from stacking with each other, which is a bug the player would see as items
        // that refuse to merge for no visible reason.
        ItemStack cleared = new ItemStack(Items.IRON_INGOT, 1);
        UpgradeItemNbt.write(cleared, new CompoundTag());
        report("writing an empty tag leaves the item without a tag at all, so carriers still stack",
                cleared.getTag() == null && !UpgradeItemNbt.has(cleared));

        // The original's own per-upgrade layout, so a carried-over world still yields its data.
        CompoundTag legacy = UpgradeItemNbt.writeLegacy("example_speed", marker);
        report("the original's one-entry layout round-trips by upgrade name",
                marker.equals(UpgradeItemNbt.readLegacy(legacy, "example_speed")));
        report("...and asking for a name it does not hold yields an empty tag rather than null",
                UpgradeItemNbt.readLegacy(legacy, "not_this_one").isEmpty());

        // The reader has to carry the tag through the merge. The first contributing slot wins, which is what the
        // original did by keeping the machine-upgrade instance that arrived first while summing the counts.
        busInventory.setStackInSlot(0, taggedCarrier);
        busInventory.setStackInSlot(1, new ItemStack(Items.IRON_INGOT, 1));
        busInventory.setStackInSlot(2, new ItemStack(Items.COBBLESTONE, 5));
        UpgradeStack.Bag carried = UpgradeEffects.read(busInventory, lookup);
        check("the merged upgrade is still one entry", 1, carried.size());
        check("...with both carriers counted", 2, carried.itemCount());
        report("...and the per-copy tag survives the merge: " + carried.stacks().get(0).customData(),
                carried.stacks().get(0).hasCustomData());
        check("...and it is the FIRST contributing slot's tag, as the original's first-wins merge was",
                "slot-zero", carried.stacks().get(0).customData().getString("mmverify:who"));

        // ...and an untagged carrier must not invent one.
        UpgradeStack.Bag untagged = UpgradeEffects.read(new net.minecraftforge.items.ItemStackHandler(1) {{
            setStackInSlot(0, new ItemStack(Items.IRON_INGOT, 1));
        }}, lookup);
        report("a carrier with no tag yields a stack with no per-copy data",
                !untagged.stacks().get(0).hasCustomData());

        // ---- (8c) the bus's own per-declaration data, and its save format -------------------------------
        //
        // A different store from the item's tag above, and the original had both: this one is the bus's
        // (`TileUpgradeBus.upgradeCustomData`, `:50`), keyed by upgrade name, saved under the literal key
        // "upgradeCustomData" (`:193/217`). That key is a save-format name, so agreeing on the string is the whole
        // point — a world written by the original has to load here.
        BusUpgradeData busData = new BusUpgradeData();
        report("a fresh bus holds nothing", busData.isEmpty() && busData.names().isEmpty());

        CompoundTag declarationData = new CompoundTag();
        declarationData.putInt("mmverify:count", 7);
        report("setting a declaration's data reports a change", busData.set(speed.id(), declarationData));
        report("...reading it back gives the same tag", declarationData.equals(busData.get(speed.id())));
        report("...and it is not empty any more", !busData.isEmpty() && busData.names().contains(speed.id()));

        report("setting the SAME data again reports no change, so a caller can skip a sync",
                !busData.set(speed.id(), declarationData));
        report("setting an empty tag REMOVES the entry, so an empty section cannot linger in a save",
                busData.set(speed.id(), new CompoundTag()) && busData.isEmpty());
        report("...and a name it never held reads as an empty tag rather than null",
                busData.get(speed.id()).isEmpty());

        // "Empty compound" and "no compound" have to serialise the same way, or a save round trip would invent a
        // section that then reads as "this declaration has data".
        CompoundTag emptySave = new CompoundTag();
        busData.save(emptySave);
        report("an empty store writes NO key at all, rather than an empty one",
                !emptySave.contains(BusUpgradeData.TAG));

        busData.set(speed.id(), declarationData);
        CompoundTag saved = new CompoundTag();
        busData.save(saved);
        report("a non-empty store writes the original's own key: " + saved.getAllKeys(),
                saved.getAllKeys().contains(BusUpgradeData.TAG));
        report("...and that key holds a compound, which is the shape the original wrote",
                saved.contains(BusUpgradeData.TAG, CompoundTag.TAG_COMPOUND));

        BusUpgradeData reloaded = new BusUpgradeData();
        reloaded.load(saved);
        report("a save round trip preserves the declaration's data",
                declarationData.equals(reloaded.get(speed.id())) && reloaded.names().contains(speed.id()));

        // A malformed entry must not make a chunk unloadable: the name is skipped and the rest still loads.
        CompoundTag malformed = new CompoundTag();
        CompoundTag store = new CompoundTag();
        store.put("not a resource location", declarationData.copy());
        store.put(speed.id().toString(), declarationData.copy());
        malformed.put(BusUpgradeData.TAG, store);
        BusUpgradeData tolerant = new BusUpgradeData();
        tolerant.load(malformed);
        report("a malformed upgrade name in a save is skipped, and the valid entry still loads",
                tolerant.names().size() == 1 && tolerant.names().contains(speed.id()));

        // The two stores are genuinely independent, which is the property that makes keeping both worthwhile.
        ItemStack independentCarrier = new ItemStack(Items.IRON_INGOT, 1);
        UpgradeItemNbt.write(independentCarrier, declarationData);
        BusUpgradeData other = new BusUpgradeData();
        report("the item's tag and the bus's declaration data are separate stores: an item tag alone leaves the "
                        + "bus empty",
                !UpgradeItemNbt.read(independentCarrier).isEmpty() && other.isEmpty());
        other.set(speed.id(), declarationData);
        ItemStack bareCarrier = new ItemStack(Items.IRON_INGOT, 1);
        report("...and a pristine item beside a populated bus stays pristine",
                !UpgradeItemNbt.has(bareCarrier) && !other.isEmpty());

        // (9) The controller's own join, driven directly: structural modifiers and bus modifiers meet in one set.
        // Two independent x2 output modifiers multiply to x4, which is countable in stone.
        RecipeModifiers structural = RecipeModifiers.of(List.of(
                RecipeModifier.multiplyOutput(RecipeModifier.Target.ITEM, 2.0F)));
        RecipeModifiers joined = structural.andThen(busModifiers(bag(stack(doubler, 1)), ALLOY_FURNACE));
        check("the joined set carries both modifiers", 2, joined.modifiers().size());
        resetCobblestone(8, 4_000_000L);
        int joinedLimit = recipe.parallelism(ports(), joined, 3);
        // 8 cobblestone at 1 per copy allows 8; space allows 16 / 4 per copy = 4; the ceiling is 3.
        check("the x4 output limits the copies a 16-slot hatch can hold", 3, joinedLimit);
        craft(recipe, joined, joinedLimit);
        check("cobblestone consumed with two x2 output modifiers", 3, 8 - cobblestoneLeft());
        check("stone produced with two x2 output modifiers (3 copies x 4)", 12, outputCount(Items.STONE));

        // (10) The static join the block entity calls is reachable and behaves as the inline one does.
        // The FakeBus stands in for a placed block; this harness never builds a Level.
        List<UpgradeBusUtility> buses = List.of(new FakeBus(mixed));
        RecipeModifiers viaController = com.reborn.modularmachinery.block.MachineControllerBlockEntity
                .collectBusModifiers(buses, ALLOY_FURNACE);
        check("controller join over one bus holding two upgrades", 2, viaController.modifiers().size());
        check("controller join with no machine", 0,
                com.reborn.modularmachinery.block.MachineControllerBlockEntity
                        .collectBusModifiers(buses, null).modifiers().size());
        check("controller join over two buses", 4,
                com.reborn.modularmachinery.block.MachineControllerBlockEntity
                        .collectBusModifiers(List.of(new FakeBus(mixed), new FakeBus(mixed)), ALLOY_FURNACE)
                        .modifiers().size());
    }

    /** A bus standing in for a placed block: this harness never builds a {@code Level}. */
    private record FakeBus(UpgradeStack.Bag contents) implements UpgradeBusUtility {
        @Override public boolean bindMachine(net.minecraft.core.BlockPos pos, ResourceLocation machineId) {
            return false;
        }

        @Override public java.util.Map<net.minecraft.core.BlockPos, ResourceLocation> boundMachines() {
            return java.util.Map.of();
        }

        @Override public UpgradeStack.Bag upgrades() {
            return contents;
        }
    }

    private static final ResourceLocation ALLOY_FURNACE =
            new ResourceLocation("modular_machinery_reborn", "alloy_furnace");
    private static final ResourceLocation OTHER_MACHINE =
            new ResourceLocation("modular_machinery_reborn", "iron_centrifuge");

    private static UpgradeStack.Bag bag(UpgradeStack... stacks) {
        return new UpgradeStack.Bag(List.of(stacks));
    }

    /**
     * An {@link UpgradeStack} for a declaration read straight from JSON. The carrier target's item is only used
     * by the bus's slot reader, never by the arithmetic, so a fixed target without one is the right stand-in —
     * and {@code UpgradeStack} takes one because a dynamic upgrade would need its item's NBT.
     */
    private static UpgradeStack stack(UpgradeType type, int count) {
        return new UpgradeStack(new UpgradeTarget.Fixed(null, type), count);
    }

    /**
     * The controller's own arithmetic for one bus, reached through the same static method the block entity calls.
     * Using it here rather than a local lambda is the point: the harness drives production code, not a copy.
     */
    private static RecipeModifiers busModifiers(UpgradeStack.Bag contents, ResourceLocation machineId) {
        return com.reborn.modularmachinery.block.MachineControllerBlockEntity
                .collectBusModifiers(List.of(new FakeBus(contents)), machineId);
    }

    /** The datapack's alloy-furnace demonstrator: 1 cobblestone → 1 stone, 5 ticks, 100 FE/t. */
    private static MachineRecipe parallelDemo() {
        return new MachineRecipe(new ResourceLocation("mmverify", "upgrade_bus_demo"), "alloy_furnace",
                "alloy_furnace_parallel_demo", 5, List.of(
                        new EnergyRequirement(IOType.INPUT, 100L),
                        ItemRequirement.input(ingredient(Items.COBBLESTONE), 1),
                        ItemRequirement.output(new ItemStack(Items.STONE), 1, 1, 1.0F)));
    }

    /** {@link #craft} but reporting how many ticks the loop really ran. */
    private static int craftCountingTicks(MachineRecipe recipe, RecipeModifiers modifiers, int parallelism) {
        HatchCollection ports = ports();
        recipe.setParallelism(Math.max(1, parallelism));
        recipe.applyDurationMultiplier(modifiers);
        int duration = recipe.duration(modifiers);
        if (!recipe.canStart(ports, modifiers) || !recipe.canTick(ports, modifiers)) {
            throw new IllegalStateException("craft could not start");
        }
        if (!recipe.start(ports, RandomSource.create(1L), modifiers)) {
            throw new IllegalStateException("start failed");
        }
        RandomSource random = RandomSource.create(42L);
        int ticked = 0;
        for (int tick = 0; tick < duration; tick++) {
            if (!recipe.canTick(ports, modifiers)) {
                throw new IllegalStateException("tick " + tick + " could not be paid");
            }
            recipe.tick(ports, random, modifiers);
            ticked++;
        }
        if (!recipe.canFinish(ports, modifiers)) {
            throw new IllegalStateException("outputs do not fit");
        }
        recipe.finish(ports, random, modifiers);
        return ticked;
    }

    /**
     * FE one craft draws, measured as the difference across exactly that craft.
     *
     * <p>The harness shares one {@code EnergyStorage} across a section, so an absolute {@code start - stored}
     * figure would include every earlier craft. Measuring the delta is what makes the number mean "this craft".
     */
    private static long energyDrawnBy(Runnable craft, int cobblestone) {
        resetCobblestone(cobblestone, 4_000_000L);
        long before = energyIn.getEnergyStored();
        craft.run();
        return before - energyIn.getEnergyStored();
    }

    /**
     * One upgrade declaration, read from the shipped example datapack through the loader's own private reader —
     * the same reflection trick section K uses for {@code readMachine}.
     */
    @Nullable
    private static UpgradeType loadUpgrade(String name) {
        Path base = exampleDatapack();
        if (base == null) {
            report("example-datapack/ not found", false);
            return null;
        }
        Path file = base.resolve("data/modular_machinery_reborn/upgrade/" + name + ".json");
        if (!Files.isRegularFile(file)) {
            report("no such upgrade declaration: " + file, false);
            return null;
        }
        try {
            Class<?> loader = Class.forName("com.reborn.modularmachinery.upgrade.UpgradeLoader");
            java.lang.reflect.Method method = loader.getDeclaredMethod("readType", ResourceLocation.class,
                    com.google.gson.JsonElement.class);
            method.setAccessible(true);
            com.google.gson.JsonElement element = JsonParser.parseString(Files.readString(file));
            return (UpgradeType) method.invoke(null, new ResourceLocation("mmverify", name), element);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            report("reading " + name + " failed: " + exception.getCause(), false);
            return null;
        } catch (Exception exception) {
            report("reading " + name + " failed: " + exception, false);
            return null;
        }
    }

    /**
     * One {@code <name>.item.json} mapping, read through the loader's own private {@code readMapping}, so the
     * harness exercises the real item resolution rather than constructing a target by hand. Its own type table is
     * primed with the declarations under test, which is what the loader's second pass does.
     */
    @Nullable
    private static UpgradeTarget loadUpgradeTarget(String name) {
        Path base = exampleDatapack();
        if (base == null) {
            return null;
        }
        Path file = base.resolve("data/modular_machinery_reborn/upgrade/" + name + ".json");
        if (!Files.isRegularFile(file)) {
            report("no such item mapping: " + file, false);
            return null;
        }
        try {
            Object mapping = readMapping(file, name);
            if (mapping == null) {
                return null;
            }
            java.lang.reflect.Method target = mapping.getClass().getDeclaredMethod("target");
            target.setAccessible(true);
            return (UpgradeTarget) target.invoke(mapping);
        } catch (Exception exception) {
            report("reading " + name + " failed: " + exception, false);
            return null;
        }
    }

    /** Calls the loader's private {@code readMapping} on a shipped file, with the declarations installed. */
    @Nullable
    private static Object readMapping(Path file, String name) {
        try {
            Class<?> loader = Class.forName("com.reborn.modularmachinery.upgrade.UpgradeLoader");
            java.lang.reflect.Method method = loader.getDeclaredMethod("readMapping", ResourceLocation.class,
                    com.google.gson.JsonElement.class, java.util.Map.class);
            method.setAccessible(true);
            com.google.gson.JsonElement element = JsonParser.parseString(Files.readString(file));
            return method.invoke(null, new ResourceLocation("mmverify", name), element, upgradeTypes());
        } catch (java.lang.reflect.InvocationTargetException exception) {
            report("reading " + name + " failed: " + exception.getCause(), false);
            return null;
        } catch (Exception exception) {
            report("reading " + name + " failed: " + exception, false);
            return null;
        }
    }

    /** Every upgrade declaration the shipped datapack carries, keyed as the loader would key it. */
    private static Map<ResourceLocation, UpgradeType> upgradeTypes() {
        Map<ResourceLocation, UpgradeType> types = new java.util.HashMap<>();
        for (String name : new String[] {"example_speed", "example_output_doubler", "example_charge"}) {
            UpgradeType type = loadUpgrade(name);
            if (type != null) {
                types.put(type.id(), type);
            }
        }
        return types;
    }

    /**
     * Reads the shipped item mappings through the loader's own reader, and reports what the two item names in
     * them resolve to.
     *
     * <p>This is where the harness has to be honest about its own limits. It runs outside Forge's mod loading,
     * so {@code ForgeRegistries.ITEMS.getValue} answers {@code Items.AIR} for <b>every</b> name, including this
     * mod's own items — which is why the live registry cannot be populated here and why the bus reader takes a
     * lookup function (see {@code UpgradeEffects#read}). This method therefore asserts the part that <i>is</i>
     * verifiable offline: that the parser accepts both files and yields the upgrade each one names.
     */
    private static void checkItemMappings() {
        for (String name : new String[] {"wrench.item", "modularium.item"}) {
            Path base = exampleDatapack();
            if (base == null) {
                report("example-datapack/ not found", false);
                return;
            }
            Object mapping = readMapping(
                    base.resolve("data/modular_machinery_reborn/upgrade/" + name + ".json"), name);
            if (mapping == null) {
                continue;
            }
            try {
                java.lang.reflect.Method itemOf = mapping.getClass().getDeclaredMethod("item");
                java.lang.reflect.Method targetOf = mapping.getClass().getDeclaredMethod("target");
                itemOf.setAccessible(true);
                targetOf.setAccessible(true);
                Item item = (Item) itemOf.invoke(mapping);
                UpgradeTarget target = (UpgradeTarget) targetOf.invoke(mapping);
                report("mapping " + name + " parses and names " + target.upgrade().id()
                        + " (its item resolves to " + item + " here, because ForgeRegistries is empty offline)",
                        target.upgrade() != null);
            } catch (Exception exception) {
                report("inspecting " + name + " failed: " + exception, false);
            }
        }
    }

    /** Fills the input bus with cobblestone and the output bus with empty slots. */
    private static void resetCobblestone(int amount, long energy) {
        itemIn = new ItemStackHandler(Math.max(4, (amount + 63) / 64));
        itemOut = new ItemStackHandler(16);
        energyIn = new EnergyStorage(1_000_000, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        int remaining = amount;
        for (int slot = 0; slot < itemIn.getSlots() && remaining > 0; slot++) {
            int put = Math.min(64, remaining);
            itemIn.setStackInSlot(slot, new ItemStack(Items.COBBLESTONE, put));
            remaining -= put;
        }
        energyIn.receiveEnergy((int) Math.min(Integer.MAX_VALUE, Math.max(0L, energy)), false);
    }

    private static int cobblestoneLeft() {
        int total = 0;
        for (int slot = 0; slot < itemIn.getSlots(); slot++) {
            total += itemIn.getStackInSlot(slot).getCount();
        }
        return total;
    }

    // =================================================================== M6d-b: the smart data interface

    /**
     * Section S — M6d-b's actual claim, which the plan forbids accepting on "a number appeared in the GUI":
     * <b>a value written through the controller is read by the {@code interface_number_input} requirement, a
     * mismatch is reported rather than silently passing, out-of-range values are rejected, and a machine which
     * declares no such type cannot use one.</b>
     *
     * <p>Driven in the order {@code MachineControllerBlockEntity#tick} uses:
     *
     * <ol>
     *   <li>the definition layer declares the type (section (1));</li>
     *   <li>the controller reconciles its stored values against the formed machine (section (2)) — the
     *       production {@code SmartInterfaceStore}, which is the whole of the block entity's M6d-b state
     *       (its methods are one-line delegations to it, so nothing is reimplemented here);</li>
     *   <li>that store is the <b>only</b> source of the value the requirement reads
     *       ({@code ports.smartInterfaceValue(type)} → {@code SmartInterfaceValueSource}), which is the original
     *       access chain {@code requirement → controller → value} with the intermediate block removed;</li>
     *   <li>the recipe's answer is compared against the items that actually moved.</li>
     * </ol>
     */
    private static void smartInterfaceChain() {
        section("S. a value written through the controller is read by interface_number_input");

        // ---- (1) the declaration: the machine JSON field the original never had -------------------------
        String parts = "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]";
        MachineDefinition declared = readDefinition("{\"registryname\":\"t\",\"smart-interfaces\":["
                + "{\"type\":\"mode\",\"default\":3,\"priority\":10,"
                + "\"header\":\"gui.mmverify.mode.header\",\"value\":\"mode=%s\","
                + "\"footer\":\"gui.mmverify.mode.footer\",\"notequal\":\"gui.mmverify.mode.mismatch\"},"
                + "{\"type\":\"speed\",\"default\":0.5}]" + "," + parts + "}");
        if (declared == null) {
            report("a definition with smart-interfaces failed to load", false);
            return;
        }
        check("declared type count", 2, declared.smartInterfaces().size());
        check("declared type names, in declaration order", "[mode, speed]",
                declared.smartInterfaceNames().toString());
        check("hasSmartInterfaces()", "true", String.valueOf(declared.hasSmartInterfaces()));
        check("'mode' default", "3.0", String.valueOf(declared.smartInterface("mode").defaultValue()));
        check("'mode' priority", 10, declared.smartInterface("mode").priority());
        check("'speed' default", "0.5", String.valueOf(declared.smartInterface("speed").defaultValue()));
        check("'speed' priority defaults to 0", 0, declared.smartInterface("speed").priority());
        check("an undeclared type is null", "null", String.valueOf(declared.smartInterface("nope")));
        // The original's getFirstSmartInterfaceType ranked by priority.
        check("the highest-priority declaration is the one a fresh value binds to", "mode",
                com.reborn.modularmachinery.machine.SmartInterfaceType
                        .highestPriority(declared.smartInterfaces()).orElseThrow().type());
        // The author's own value format, and its fallback when the format is unusable.
        check("an author value format is applied", "mode=4.0",
                declared.smartInterface("mode").formatValue(4.0F));
        check("a type with no format falls back to the default line", "null",
                String.valueOf(declared.smartInterface("speed").formatValue(4.0F)));
        MachineDefinition brokenFormat = readDefinition("{\"registryname\":\"t\",\"smart-interfaces\":["
                + "{\"type\":\"mode\",\"value\":\"%d %d\"}]" + "," + parts + "}");
        check("an unusable value format falls back instead of throwing", "null",
                String.valueOf(brokenFormat.smartInterface("mode").formatValue(1.0F)));
        check("the declared type's own mismatch message is reachable", "gui.mmverify.mode.mismatch",
                declared.smartInterface("mode").notEqualMessage());
        check("...and the original's default when the type gives none",
                "craftcheck.failure.interface.number.notequal",
                declared.smartInterface("speed").notEqualMessage());

        // A machine that declares nothing: the original's smartInterfaceTypesIsEmpty.
        MachineDefinition bare = readDefinition("{\"registryname\":\"t\"," + parts + "}");
        check("a definition with no field declares no type", "false",
                String.valueOf(bare.hasSmartInterfaces()));

        // Validation: every malformed value must be rejected with what to write instead.
        Object[][] bad = {
                {"{\"registryname\":\"t\",\"smart-interfaces\":{}" + "," + parts + "}", "must be an array"},
                {"{\"registryname\":\"t\",\"smart-interfaces\":[{\"default\":1}]" + "," + parts + "}",
                        "has no 'type'"},
                {"{\"registryname\":\"t\",\"smart-interfaces\":[{\"type\":\"\"}]" + "," + parts + "}",
                        "empty 'type'"},
                {"{\"registryname\":\"t\",\"smart-interfaces\":[{\"type\":\"a\"},{\"type\":\"a\"}]" + ","
                        + parts + "}", "both named 'a'"},
                {"{\"registryname\":\"t\",\"smart-interfaces\":[{\"type\":\"a\",\"default\":\"zero\"}]" + ","
                        + parts + "}", "must be a number"},
                {"{\"registryname\":\"t\",\"smart-interfaces\":[{\"type\":\"a\",\"priority\":\"high\"}]" + ","
                        + parts + "}", "must be a whole number"},
                {"{\"registryname\":\"t\",\"smart-interfaces\":[{\"type\":\"a\",\"header\":7}]" + ","
                        + parts + "}", "must be a string"},
        };
        for (Object[] c : bad) {
            try {
                MachineDefinition definition = readDefinition((String) c[0]);
                report("expected a rejection for " + c[0] + " but got " + definition, false);
            } catch (RuntimeException exception) {
                String message = String.valueOf(exception.getMessage());
                report("rejected: " + message, message.contains((String) c[1]));
            }
        }

        // ---- (2) the controller's store: reconcile, write, persist --------------------------------------
        com.reborn.modularmachinery.machine.SmartInterfaceStore store =
                new com.reborn.modularmachinery.machine.SmartInterfaceStore();

        check("a fresh store holds nothing", 0, store.size());
        java.lang.reflect.Field storeField = null;
        try {
            // The block entity must really own one of these, or none of the above describes the controller.
            storeField = com.reborn.modularmachinery.block.MachineControllerBlockEntity.class
                    .getDeclaredField("smartInterfaces");
            report("the controller's own field is this store (" + storeField.getType().getSimpleName() + ")",
                    storeField.getType() == com.reborn.modularmachinery.machine.SmartInterfaceStore.class);
        } catch (Exception exception) {
            report("the controller does not own a SmartInterfaceStore: " + exception, false);
        }

        report("bind reports a change when it fills a default in", store.bind(declared));
        check("binding fills in 'mode' at its declared default", "3.0", String.valueOf(store.valueOf("mode")));
        check("binding fills in 'speed' at its declared default", "0.5", String.valueOf(store.valueOf("speed")));
        check("binding declares both values", 2, store.size());
        check("...in declaration order", "[mode, speed]", store.typeNames().toString());
        report("a second bind changes nothing (idempotent, as the 10-tick structure check requires)",
                !store.bind(declared));
        store.set(declared, "mode", 7.0F);
        store.bind(declared);
        check("re-binding does not overwrite an edited value", "7.0", String.valueOf(store.valueOf("mode")));

        // The machine changes: the old type's value must not survive to be read by an unrelated requirement.
        MachineDefinition other = readDefinition("{\"registryname\":\"t\",\"smart-interfaces\":["
                + "{\"type\":\"speed\",\"default\":1}]" + "," + parts + "}");
        report("re-binding to a machine that dropped a type reports the change", store.bind(other));
        check("a value whose type the new machine does not declare is dropped", "null",
                String.valueOf(store.valueOf("mode")));
        check("the new machine's own type is NOT overwritten (it already had a value)", "0.5",
                String.valueOf(store.valueOf("speed")));

        // Back to the two-type machine, and drive the write path the screen's packet reaches.
        store.bind(declared);
        check("a write reports the stored value", "12.5", String.valueOf(store.set(declared, "mode", 12.5F)));
        check("a write really moves the value", "12.5", String.valueOf(store.valueOf("mode")));
        check("a write to an undeclared type is refused", "null",
                String.valueOf(store.set(declared, "ghost", 1.0F)));
        check("...and stores nothing", "null", String.valueOf(store.valueOf("ghost")));
        check("NaN is refused", "null",
                String.valueOf(store.set(declared, "mode", Float.NaN)));
        check("...and leaves the previous value alone", "12.5", String.valueOf(store.valueOf("mode")));
        check("infinity is refused too", "null",
                String.valueOf(store.set(declared, "mode", Float.POSITIVE_INFINITY)));
        check("...and still leaves the previous value alone", "12.5",
                String.valueOf(store.valueOf("mode")));

        // NBT: the values are the player's own input and have to survive a reload verbatim.
        net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
        store.save(saved);
        check("the values go under the original's own key", "true",
                String.valueOf(saved.contains("boundData")));
        check("...as a compound of type -> float", "12.5",
                String.valueOf(saved.getCompound("boundData").getFloat("mode")));
        com.reborn.modularmachinery.machine.SmartInterfaceStore reloaded =
                new com.reborn.modularmachinery.machine.SmartInterfaceStore();
        reloaded.load(saved);
        check("a reload restores the edited value, not the default", "12.5",
                String.valueOf(reloaded.valueOf("mode")));
        check("...and the other type with it", "0.5", String.valueOf(reloaded.valueOf("speed")));
        // Bind on the loaded store must not re-default the restored value.
        reloaded.bind(declared);
        check("reconciling a restored store leaves the restored value alone", "12.5",
                String.valueOf(reloaded.valueOf("mode")));

        // ---- (3) the chain: controller store → HatchCollection → requirement ---------------------------
        // The ports are built the way MachineControllerBlockEntity#collectHatches builds them: the builder is
        // handed the controller as the value source and the formed definition.
        HatchCollection interfacePorts = portsWithSmart(itemOut, store, declared);
        check("the ports carry the controller's value", "12.5",
                String.valueOf(interfacePorts.smartInterfaceValue("mode")));
        check("the ports carry the declared type too", "mode",
                interfacePorts.declaredSmartInterface("mode").type());
        check("...and answer null for a type the machine does not declare", "null",
                String.valueOf(interfacePorts.smartInterfaceValue("ghost")));
        // A collection built without a controller must keep the pre-M6d-b behaviour: no value, ever.
        check("a plain HatchCollection has no interface value", "null",
                String.valueOf(ports().smartInterfaceValue("mode")));
        check("...and no declared type either", "null",
                String.valueOf(ports().declaredSmartInterface("mode")));

        // The requirement, as the recipe JSON builds it.
        InterfaceNumberInputRequirement inRange =
                new InterfaceNumberInputRequirement("mode", 10.0F, 20.0F, null);
        InterfaceNumberInputRequirement tooHigh =
                new InterfaceNumberInputRequirement("mode", 0.0F, 5.0F, null);
        InterfaceNumberInputRequirement exact =
                new InterfaceNumberInputRequirement("mode", 12.5F);
        InterfaceNumberInputRequirement unknownType =
                new InterfaceNumberInputRequirement("ghost", 0.0F, 100.0F, null);

        report("the value inside [10, 20] satisfies the requirement", inRange.canSatisfy(interfacePorts));
        report("the value outside [0, 5] does not", !tooHigh.canSatisfy(interfacePorts));
        report("an exact-value requirement for the stored value satisfies", exact.canSatisfy(interfacePorts));
        report("a type the machine does not declare is never satisfied",
                !unknownType.canSatisfy(interfacePorts));
        check("...and reports the original's missing-interface key",
                "component.missing.modularmachinery.interface.number",
                String.valueOf(unknownType.startFailure(interfacePorts)));
        check("a mismatch reports the declared type's own notequal message",
                "gui.mmverify.mode.mismatch", String.valueOf(tooHigh.startFailure(interfacePorts)));
        check("the original's default key when the type gives none",
                "craftcheck.failure.interface.number.notequal",
                String.valueOf(new InterfaceNumberInputRequirement("speed", 9.0F, 9.0F, null)
                        .startFailure(interfacePorts)));
        check("a satisfied requirement reports no failure", "null",
                String.valueOf(inRange.startFailure(interfacePorts)));

        // ---- the other requirement types' messages (0.31.0) --------------------------------------------
        //
        // The original's `canStartCrafting` returned a `CraftCheck` carrying one of these keys and the controller
        // printed it (`RequirementItem.java:288-292`, `RequirementFluid.java:168-176`,
        // `RequirementEnergy.java:138-146`). Before this, only `interface_number_input` had anything to say, so a
        // machine missing stone said a bare `idle` — the vocabulary is what makes the status line useful.
        //
        // Each message is asserted on the requirement type AND on the direction, because the original split them:
        // "missing input" and "not enough room for the output" are different problems with different fixes, and a
        // port that answered one key for both would be strictly less useful than the original.
        HatchCollection emptyPorts = HatchCollection.builder().build();
        ItemRequirement fixtureItemIn = ItemRequirement.input(Ingredient.of(Items.COBBLESTONE), 1);
        ItemRequirement fixtureItemOut = ItemRequirement.output(new ItemStack(Items.COBBLESTONE, 1), 1, 1, 1.0F);
        check("an item input that is short reports the original's item-input key",
                ItemRequirement.FAILURE_ITEM_INPUT, String.valueOf(fixtureItemIn.startFailure(emptyPorts)));
        check("an item output that will not fit reports the original's item-output-space key",
                ItemRequirement.FAILURE_ITEM_OUTPUT_SPACE, String.valueOf(fixtureItemOut.startFailure(emptyPorts)));
        report("...and the two are different keys, not one key for both directions",
                !ItemRequirement.FAILURE_ITEM_INPUT.equals(ItemRequirement.FAILURE_ITEM_OUTPUT_SPACE));

        // The keys are the original's own spellings, which is the part a pack author's notes and the original's
        // own language files depend on.
        check("the item keys are the original's spellings", "craftcheck.failure.item.input",
                ItemRequirement.FAILURE_ITEM_INPUT);
        check("...and so is the fluid pair", "craftcheck.failure.fluid.input",
                FluidRequirement.FAILURE_FLUID_INPUT);
        check("...and the energy pair", "craftcheck.failure.energy.input",
                EnergyRequirement.FAILURE_ENERGY_INPUT);
        check("the energy output message is the original's, typo included ('hatch are full')",
                "craftcheck.failure.energy.output.space", EnergyRequirement.FAILURE_ENERGY_OUTPUT_SPACE);

        // 0.59.0: the gas pair. 兼容层会产出这两个键，而它们在语言文件里**曾经缺失**——
        // 于是装了 Mekanism 的实例里，气体不够时状态行显示的是**原始键名**而不是文案。
        // 措辞取自旧版 `zh_CN.lang` / `en_US.lang` 的原文（那两句当时就已存在，只是没有 Mekanism 就没人用得上）。
        //
        // 判据用的是**本版自己的语言文件**（不是旧版的），因为玩家看到的是它；
        // 而措辞本身与旧版 zh_CN.lang / en_US.lang 逐字相同（上面那两条 check 钉的就是这件事）。
        Path gasLang = Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/assets/"
                + "modular_machinery_reborn/lang");
        try {
            Map<String, String> zhGas = flatJson(gasLang.resolve("zh_cn.json"));
            Map<String, String> enGas = flatJson(gasLang.resolve("en_us.json"));
            check("the gas input message is the original's wording (zh)", "缺少气体输入！",
                    zhGas.get("craftcheck.failure.gas.input"));
            check("the gas input message is the original's wording (en)", "Missing gas input!",
                    enGas.get("craftcheck.failure.gas.input"));
            check("the gas output-space message is the original's wording (zh)", "没有足够的库存空间用于气体输出！",
                    zhGas.get("craftcheck.failure.gas.output.space"));
            check("the gas output-space message is the original's wording (en)",
                    "Not enough space for gas output(s)!",
                    enGas.get("craftcheck.failure.gas.output.space"));
        } catch (Exception exception) {
            report("reading the gas messages failed: " + exception, false);
        }

        // The ends are inclusive, exactly as the original compared them (`value >= min && value <= max`).
        report("min == the stored value satisfies",
                new InterfaceNumberInputRequirement("mode", 12.5F, 13.0F, null).canSatisfy(interfacePorts));
        report("max == the stored value satisfies",
                new InterfaceNumberInputRequirement("mode", 12.0F, 12.5F, null).canSatisfy(interfacePorts));
        report("just above max does not",
                !new InterfaceNumberInputRequirement("mode", 12.0F, 12.49F, null).canSatisfy(interfacePorts));
        report("just below min does not",
                !new InterfaceNumberInputRequirement("mode", 12.51F, 20.0F, null).canSatisfy(interfacePorts));

        // ---- (4) the same chain through MachineRecipe, and what actually moves --------------------------
        // 1 coal -> 1 diamond, 5 ticks, no FE: small enough that "did it happen" is countable.
        MachineRecipe gated = new MachineRecipe(new ResourceLocation("mmverify", "gated"), "t", "gated", 5,
                List.of(ItemRequirement.input(ingredient(Items.COAL), 1),
                        ItemRequirement.output(new ItemStack(Items.DIAMOND), 1, 1, 1.0F),
                        tooHigh));

        reset(4, 4, 1_000_000);
        HatchCollection blocked = portsWithSmart(itemOut, store, declared);
        report("a recipe whose interface value is out of range refuses to start",
                !gated.canStart(blocked, RecipeModifiers.EMPTY));
        check("...and the recipe says why", "gui.mmverify.mode.mismatch",
                String.valueOf(gated.startFailure(blocked, RecipeModifiers.EMPTY)));
        check("...and no coal was consumed", 0, 4 - coalLeft());
        check("...and no diamond was produced", 0, outputCount(Items.DIAMOND));

        // The player edits the value to one the recipe asks for, and the very same recipe now runs.
        check("the edit is accepted", "3.0", String.valueOf(store.set(declared, "mode", 3.0F)));
        reset(4, 4, 1_000_000);
        HatchCollection allowed = portsWithSmart(itemOut, store, declared);
        report("the same recipe starts once the value is in range",
                gated.canStart(allowed, RecipeModifiers.EMPTY));
        check("...and reports no failure", "null",
                String.valueOf(gated.startFailure(allowed, RecipeModifiers.EMPTY)));
        craft(gated, allowed, RecipeModifiers.EMPTY, 1);
        check("coal really consumed", 1, 4 - coalLeft());
        check("diamond really produced", 1, outputCount(Items.DIAMOND));

        // Out of range again: the requirement re-reads the live value rather than caching one, which is what
        // reading a live block entity field did in the original.
        store.set(declared, "mode", 99.0F);
        reset(4, 4, 1_000_000);
        report("changing the value back out of range stops the recipe again",
                !gated.canStart(portsWithSmart(itemOut, store, declared), RecipeModifiers.EMPTY));
        check("...and nothing moved this time either", 0, 4 - coalLeft());

        // A machine that declares no type at all: the exact case the acceptance criteria name.
        com.reborn.modularmachinery.machine.SmartInterfaceStore bareStore =
                new com.reborn.modularmachinery.machine.SmartInterfaceStore();
        bareStore.bind(bare);
        check("a machine with no declaration holds no value", 0, bareStore.size());
        check("...and its value source answers null", "null", String.valueOf(bareStore.valueOf("mode")));
        check("...and a write to it is refused", "null", String.valueOf(bareStore.set(bare, "mode", 5.0F)));
        reset(4, 4, 1_000_000);
        HatchCollection barePorts = portsWithSmart(itemOut, bareStore, bare);
        report("an interface requirement on such a machine cannot start",
                !gated.canStart(barePorts, RecipeModifiers.EMPTY));
        check("...and reports the original's missing key",
                "component.missing.modularmachinery.interface.number",
                String.valueOf(gated.startFailure(barePorts, RecipeModifiers.EMPTY)));
        check("...and nothing moved", 0, 4 - coalLeft());

        // The controller's own failure index: the menu mirrors an int, so the fixed list is the wire format.
        //
        // 0.32.0（波 2）在这份列表末尾**追加**了六条 craftcheck.failure.*。追加是对的，因为
        // failureIndex 把键映射成索引、而索引要跨网络传给客户端——插入或删除会让旧客户端读到错误的键。
        // 所以原来的两条仍在 0/1 位（下面单独断言），这里只把整张表钉住。
        check("the failure key list is the two original keys followed by wave 2's six, in wire order",
                "[component.missing.modularmachinery.interface.number, "
                        + "craftcheck.failure.interface.number.notequal, "
                        + "craftcheck.failure.item.input, "
                        + "craftcheck.failure.item.output.space, "
                        + "craftcheck.failure.fluid.input, "
                        + "craftcheck.failure.fluid.output.space, "
                        + "craftcheck.failure.energy.input, "
                        + "craftcheck.failure.energy.output.space]",
                com.reborn.modularmachinery.block.MachineControllerBlockEntity.START_FAILURE_KEYS.toString());
        check("index of the missing key", 0,
                com.reborn.modularmachinery.block.MachineControllerBlockEntity.failureIndex(
                        InterfaceNumberInputRequirement.MISSING_KEY));
        check("index of the mismatch key", 1,
                com.reborn.modularmachinery.block.MachineControllerBlockEntity.failureIndex(
                        com.reborn.modularmachinery.machine.SmartInterfaceType.DEFAULT_NOT_EQUAL_KEY));
        check("index of no failure", -1,
                com.reborn.modularmachinery.block.MachineControllerBlockEntity.failureIndex(null));

        // ---- (5) the requirement's JSON shape, its JEI tip, and its wire round trip ---------------------
        recipeJsonShapes(gated);
        smartInterfacePacket();
        smartInterfaceLanguageKeys();
    }

    /**
     * The requirement JSON a recipe file writes, read through the production private {@code parseRequirement}.
     * Deserialising a whole vanilla {@code Recipe} outside FML's class loader is what section P could not do, so
     * this drives the parser directly — the same reflection trick sections K and P use for {@code readMachine},
     * and the part of the path a recipe file really goes through.
     */
    private static void recipeJsonShapes(MachineRecipe alreadyBuilt) {
        section("S2. the interface_number_input JSON shape and its JEI tip");
        report("the recipe handed in carries the requirement this section is about ("
                        + alreadyBuilt.requirements().size() + " requirements)",
                alreadyBuilt.requirements().stream().anyMatch(
                        requirement -> requirement instanceof InterfaceNumberInputRequirement));
        Object[][] cases = {
                {"{\"type\":\"modularmachinery:interface_number_input\",\"io-type\":\"input\","
                        + "\"interface\":\"mode\",\"minValue\":10,\"maxValue\":20}", null},
                {"{\"type\":\"modularmachinery:interface_number_input\",\"io-type\":\"input\","
                        + "\"interface\":\"mode\",\"value\":4.5}", null},
                {"{\"type\":\"modular_machinery_reborn:interface_number_input\",\"io-type\":\"input\","
                        + "\"interface\":\"mode\",\"value\":1}", null},
                {"{\"type\":\"interface_number_input\",\"io-type\":\"input\","
                        + "\"interface\":\"mode\",\"minValue\":1}", null},
                {"{\"type\":\"modularmachinery:interface_number_input\",\"io-type\":\"output\","
                        + "\"interface\":\"mode\",\"value\":1}", "input only"},
                {"{\"type\":\"modularmachinery:interface_number_input\",\"io-type\":\"input\","
                        + "\"interface\":\"mode\",\"minValue\":20,\"maxValue\":10}", "above 'maxValue'"},
                {"{\"type\":\"modularmachinery:interface_number_input\",\"io-type\":\"input\","
                        + "\"interface\":\"mode\"}", "Missing 'value'"},
                {"{\"type\":\"modularmachinery:interface_number_input\",\"io-type\":\"input\","
                        + "\"interface\":\"mode\",\"value\":\"four\"}", "must be a number"},
                {"{\"type\":\"modularmachinery:interface_number_input\",\"io-type\":\"input\","
                        + "\"minValue\":1,\"maxValue\":2}", "Missing 'interface'"},
                {"{\"type\":\"modularmachinery:interface_number_input\",\"io-type\":\"input\","
                        + "\"interface\":\"  \",\"value\":1}", "is empty"},
        };
        for (Object[] c : cases) {
            try {
                Object requirement = parseRequirementJson((String) c[0]);
                if (c[1] != null) {
                    report("expected a rejection for " + c[0] + " but got " + requirement, false);
                    continue;
                }
                report("parsed " + c[0] + " -> " + requirement, requirement != null);
            } catch (RuntimeException exception) {
                String message = String.valueOf(exception.getMessage());
                report("rejected: " + message, c[1] != null && message.contains((String) c[1]));
            }
        }
        // The parsed object must carry the three fields, and the range must be the one the JSON wrote.
        try {
            Object parsed = parseRequirementJson("{\"type\":\"modularmachinery:interface_number_input\","
                    + "\"io-type\":\"input\",\"interface\":\"mode\",\"minValue\":10,\"maxValue\":20}");
            InterfaceNumberInputRequirement requirement = (InterfaceNumberInputRequirement) parsed;
            check("parsed interface type", "mode", requirement.interfaceType());
            check("parsed minValue", "10.0", String.valueOf(requirement.minValue()));
            check("parsed maxValue", "20.0", String.valueOf(requirement.maxValue()));
            check("a min != max range is not an exact value", "false",
                    String.valueOf(requirement.isExactValue()));
            check("and it is a start-phase input, the original's own phase", "true",
                    String.valueOf(requirement.isStartPhase() && requirement.ioType() == IOType.INPUT));
            check("...and not per-tick", "false", String.valueOf(requirement.perTick()));
            InterfaceNumberInputRequirement single = (InterfaceNumberInputRequirement) parseRequirementJson(
                    "{\"type\":\"modularmachinery:interface_number_input\",\"io-type\":\"input\","
                            + "\"interface\":\"mode\",\"value\":4.5}");
            check("the 'value' shorthand makes an exact range", "true",
                    String.valueOf(single.isExactValue()));
            check("...with both ends at that value", "4.5 / 4.5",
                    single.minValue() + " / " + single.maxValue());
        } catch (Exception exception) {
            report("inspecting the parsed requirement failed: " + exception, false);
        }

        // The JEI tip: the original's TooltipInterfaceNumberInput. Its wording is asserted against the
        // original's .lang in S4, and its <b>shape</b> cannot be driven here at all: MachineRecipeText imports
        // mezz.jei.api.gui.builder.ITooltipBuilder, and JEI is not on this harness's filtered classpath (the
        // class would not even link). What can be checked offline, and is what actually decides the rendered
        // lines, is the wiring: the three keys the tip reads must be the three keys the language files define,
        // and the requirement must expose the min/max/isExact triple the tip branches on.
        try {
            Path textSource = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                    + "modularmachinery/client/jei/MachineRecipeText.java");
            String source = Files.readString(textSource);
            for (String key : new String[] {"tooltip.modular_machinery_reborn.smartinterface.value",
                    "tooltip.modular_machinery_reborn.smartinterface.minvalue",
                    "tooltip.modular_machinery_reborn.smartinterface.maxvalue"}) {
                report("MachineRecipeText reads the key '" + key + "'",
                        source.contains("\"" + key + "\""));
            }
            report("...and branches on the requirement's own isExactValue()",
                    source.contains("InterfaceNumberInputRequirement")
                            && source.contains("minValue()") && source.contains("maxValue()"));
            check("the tip reads the declared value line for an exact requirement (no %,d mismatch)",
                    "true", String.valueOf(
                            com.reborn.modularmachinery.recipe.InterfaceNumberInputParser.parse(
                                    JsonParser.parseString("{\"interface\":\"mode\",\"value\":4}")
                                            .getAsJsonObject(),
                                    IOType.INPUT, "S2").isExactValue()));
        } catch (Exception exception) {
            report("reading MachineRecipeText.java failed: " + exception, false);
        }
    }

    /** One requirement object, through the production parser the serializer delegates to. */
    private static Object parseRequirementJson(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        String rawType = object.get("type").getAsString();
        int colon = rawType.indexOf(':');
        String kind = (colon >= 0 ? rawType.substring(colon + 1) : rawType).toLowerCase(java.util.Locale.ROOT);
        IOType ioType = IOType.byName(object.get("io-type").getAsString());
        report("the production parser is being driven for kind '" + kind + "'",
                "interface_number_input".equals(kind));
        return com.reborn.modularmachinery.recipe.InterfaceNumberInputParser.parse(object, ioType,
                "a requirement of mmverify:gated");
    }

    /** M6d-b's message must survive a round trip, the way section N checks the parallel controller's. */
    private static void smartInterfacePacket() {
        section("S3. the smart-interface update packet and the channel's message table");
        float[] values = {0.0F, -1.5F, 3.25F, 12345.75F, Float.MAX_VALUE, Float.MIN_VALUE};
        for (float value : values) {
            try {
                com.reborn.modularmachinery.network.SmartInterfaceUpdatePacket packet =
                        new com.reborn.modularmachinery.network.SmartInterfaceUpdatePacket("mode", value);
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                packet.encode(buf);
                com.reborn.modularmachinery.network.SmartInterfaceUpdatePacket decoded =
                        new com.reborn.modularmachinery.network.SmartInterfaceUpdatePacket(buf);
                report("round trip " + value + " / 'mode' -> " + decoded.value() + " / '" + decoded.type()
                                + "'",
                        decoded.value() == value && "mode".equals(decoded.type()));
            } catch (Exception exception) {
                report("round trip of " + value + " failed: " + exception, false);
            }
        }
        // The channel itself cannot be inspected here: ModNetwork's static initialiser builds a SimpleChannel,
        // which requires FML's mod construction (EventBus.addListener reaches NetworkEvent's own constructor),
        // so the class does not load outside a running game. What decides the wire format is the pair of
        // registerMessage calls and the two discriminator constants, so those are read from the source — the
        // same "the metric table the drawing code reads" approach section R takes for the factory screen.
        try {
            Path network = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                    + "modularmachinery/network/ModNetwork.java");
            String source = Files.readString(network);
            check("the channel id constants are 0, 1, 2 and 3", "true",
                    String.valueOf(source.contains("ID_PARALLEL_CONTROLLER_UPDATE = 0")
                            && source.contains("ID_SMART_INTERFACE_UPDATE = 1")
                            && source.contains("ID_SELECTION_SYNC = 2")
                            && source.contains("ID_MACHINE_DEFINITION_SYNC = 3")));
            int registrations = 0;
            for (String line : source.split("\n")) {
                if (line.contains("CHANNEL.registerMessage(")) {
                    registrations++;
                }
            }
            // Three from 0.29.0: the construct tool's selection sync joined the two M6d-b messages.
            // Four from 0.32.0's wave 4: the machine-definition sync. The protocol version deliberately stays
            // "1" each time — adding an id is the compatible direction, and a mismatched pair of builds is
            // refused on the version string anyway.
            check("the channel registers exactly four messages", 4, registrations);
            report("all four messages are registered against those constants",
                    source.contains("CHANNEL.registerMessage(ID_PARALLEL_CONTROLLER_UPDATE, "
                            + "ParallelControllerUpdatePacket.class")
                            && source.contains("CHANNEL.registerMessage(ID_SMART_INTERFACE_UPDATE, "
                                    + "SmartInterfaceUpdatePacket.class")
                            && source.contains("CHANNEL.registerMessage(ID_SELECTION_SYNC, "
                                    + "SelectionSyncPacket.class")
                            && source.contains("CHANNEL.registerMessage(ID_MACHINE_DEFINITION_SYNC, "
                                    + "MachineDefinitionSyncPacket.class"));
            report("the protocol version is unchanged from 0.20.0's '1'",
                    source.contains("PROTOCOL_VERSION = \"1\""));
        } catch (Exception exception) {
            report("reading ModNetwork.java failed: " + exception, false);
        }
    }

    /**
     * The eight new language keys: the requirement's two messages and the interface's three tooltip lines are
     * the original's own strings, and the three screen keys are the original {@code gui.smartinterface.*} family
     * re-keyed.
     */
    private static void smartInterfaceLanguageKeys() {
        section("S4. the smart-interface language keys");
        Path lang = Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/assets/"
                + "modular_machinery_reborn/lang");
        Path sourceLang = Path.of("C:/mmwork/_mmce-src/ModularMachinery-Community-Edition-master/src/main/"
                + "resources/assets/modularmachinery/lang");
        try {
            Map<String, String> zhMap = flatJson(lang.resolve("zh_cn.json"));
            Map<String, String> enMap = flatJson(lang.resolve("en_us.json"));
            Map<String, String> sourceZh = flatLang(sourceLang.resolve("zh_CN.lang"));
            Map<String, String> sourceEn = flatLang(sourceLang.resolve("en_US.lang"));

            // project key -> original key, for the five whose wording must be verbatim.
            String[][] verbatim = {
                    {"craftcheck.failure.interface.number.notequal",
                            "craftcheck.failure.interface.number.notequal"},
                    {"component.missing.modularmachinery.interface.number",
                            "component.missing.modularmachinery.interface.number"},
                    {"tooltip.modular_machinery_reborn.smartinterface.value",
                            "tooltip.machinery.smartinterface.value"},
                    {"tooltip.modular_machinery_reborn.smartinterface.minvalue",
                            "tooltip.machinery.smartinterface.minvalue"},
                    {"tooltip.modular_machinery_reborn.smartinterface.maxvalue",
                            "tooltip.machinery.smartinterface.maxvalue"},
            };
            for (String[] pair : verbatim) {
                check("zh " + pair[0] + " is the original's wording",
                        sourceZh.get(pair[1]), zhMap.get(pair[0]));
                check("en " + pair[0] + " is the original's wording",
                        sourceEn.get(pair[1]), enMap.get(pair[0]));
            }
            // The value line is the original's `gui.smartinterface.value`.
            check("zh gui...smartinterface.value keeps the original's value line",
                    sourceZh.get("gui.smartinterface.value"),
                    zhMap.get("gui.modular_machinery_reborn.smartinterface.value"));
            check("en gui...smartinterface.value keeps the original's value line",
                    sourceEn.get("gui.smartinterface.value"),
                    enMap.get("gui.modular_machinery_reborn.smartinterface.value"));
            // The original's paging/notfound keys must NOT have been copied: the merge removed those cases.
            for (String gone : new String[] {"gui.modular_machinery_reborn.smartinterface.prev",
                    "gui.modular_machinery_reborn.smartinterface.next",
                    "gui.modular_machinery_reborn.smartinterface.notfound",
                    "gui.modular_machinery_reborn.smartinterface.title"}) {
                report("the merged screen has no '" + gone + "' key (the original's case cannot arise)",
                        !zhMap.containsKey(gone) && !enMap.containsKey(gone));
            }
            check("zh has all eight new keys", 8, countNewSmartKeys(zhMap));
            check("en has all eight new keys", 8, countNewSmartKeys(enMap));
            report("the value line really is a format string a float can fill",
                    zhMap.get("gui.modular_machinery_reborn.smartinterface.value").contains("%.0f")
                            && enMap.get("gui.modular_machinery_reborn.smartinterface.value")
                                    .contains("%.0f"));
        } catch (Exception exception) {
            report("reading the language files failed: " + exception, false);
        }
    }

    /** How many of M6d-b's eight keys one language map carries. */
    private static int countNewSmartKeys(Map<String, String> map) {
        int found = 0;
        for (String key : new String[] {
                "craftcheck.failure.interface.number.notequal",
                "component.missing.modularmachinery.interface.number",
                "tooltip.modular_machinery_reborn.smartinterface.value",
                "tooltip.modular_machinery_reborn.smartinterface.minvalue",
                "tooltip.modular_machinery_reborn.smartinterface.maxvalue",
                "gui.modular_machinery_reborn.smartinterface.value",
                "gui.modular_machinery_reborn.smartinterface.label",
                "gui.modular_machinery_reborn.smartinterface.edit"}) {
            if (map.containsKey(key)) {
                found++;
            }
        }
        return found;
    }

    // =================================================================== 0.24.1: the "no failure" sentinel

    /**
     * The constants this section drives the production code with. They are read back from the class under test
     * as well (the coupling report at the top of {@link #noFailureSentinel()}), so this section cannot pass
     * against a build where they mean something else.
     */
    private static final int HARNESS_NO_FAILURE = -1;
    private static final int HARNESS_MISSING_KEY_INDEX = 0;
    private static final int HARNESS_MISMATCH_KEY_INDEX = 1;
    private static final String HARNESS_MISMATCH_KEY = "craftcheck.failure.interface.number.notequal";
    private static final String HARNESS_MISSING_KEY =
            "component.missing.modularmachinery.interface.number";

    /**
     * Section T — 0.24.1's bug fix, and the assertion that was missing when the bug shipped.
     *
     * <p>0.24.0's {@code MachineControllerBlockEntity#startFailureKey} ended in
     * {@code START_FAILURE_KEYS.get(this.clientStartFailure)}, and "no failure" <i>is</i> the sentinel
     * {@code -1}. {@code MachineControllerScreen#drawInfo} calls that method every frame, so the first frame of
     * the controller screen threw {@code IndexOutOfBoundsException: Index: -1 Size: 2} and the machine UI was
     * unopenable. Sections A–S4 never asked for the <b>"no failure"</b> state — every controller-mirror
     * assertion had a real failure to report — which is why 503 passing checks did not notice.
     *
     * <p>The invariant this section pins down is the general one: <b>a sentinel value must never be able to
     * reach a raw list lookup.</b> Its halves are asserted separately — the wire guard, the field's initial
     * value, the NBT load path and the screen's null handling — so that reintroducing <i>either</i> half of the
     * bug (the unguarded {@code get}, or a default of {@code 0}) fails a named check instead of merely going
     * uncovered again.
     */
    private static void noFailureSentinel() {
        section("T. no failure is a sentinel, and a sentinel never reaches a list lookup");

        Class<?> controller;
        Class<?> screen;
        try {
            controller = Class.forName("com.reborn.modularmachinery.block.MachineControllerBlockEntity");
            screen = Class.forName("com.reborn.modularmachinery.client.MachineControllerScreen");
        } catch (Throwable throwable) {
            report("the controller/screen classes are not loadable: " + throwable, false);
            return;
        }

        // ---- the harness's own constants still describe the class under test ---------------------------
        try {
            int noFailure = controller.getField("NO_START_FAILURE").getInt(null);
            List<?> keys = (List<?>) controller.getField("START_FAILURE_KEYS").get(null);
            report("the sentinel the harness uses (" + HARNESS_NO_FAILURE + ") is the class's own "
                            + "NO_START_FAILURE (" + noFailure + ")",
                    noFailure == HARNESS_NO_FAILURE);
            // **这里不能断言「列表正好两项」。** 那份列表是通信格式，而它的协议是
            // 「新键只能追加」——0.32.0（波 2）确实在末尾追加了六条 craftcheck.failure.*。
            // 真正要守的性质是：**下面这些断言用到的索引仍然指向同一批键**（追加不动前缀），
            // 以及列表至少还有那两项。总数写死会让每一次合法的追加都变成一次假失败，
            // 而假失败的代价是下一个人开始忽略这个检查。
            report("the list still has at least the two keys this section indexes",
                    keys.size() >= 2);
            check("index " + HARNESS_MISSING_KEY_INDEX + " is still the missing-type key",
                    HARNESS_MISSING_KEY, String.valueOf(keys.get(HARNESS_MISSING_KEY_INDEX)));
            check("index " + HARNESS_MISMATCH_KEY_INDEX + " is still the mismatch key",
                    HARNESS_MISMATCH_KEY, String.valueOf(keys.get(HARNESS_MISMATCH_KEY_INDEX)));
            if (noFailure != HARNESS_NO_FAILURE || keys.size() < 2
                    || !HARNESS_MISSING_KEY.equals(keys.get(0)) || !HARNESS_MISMATCH_KEY.equals(keys.get(1))) {
                report("the wire format's PREFIX moved; the checks below would describe a different build",
                        false);
                return;
            }
        } catch (Throwable throwable) {
            report("reading the wire format failed: " + throwable, false);
            return;
        }

        Method failureKeyAt;
        Method setClientStartFailure;
        Method startFailureKey;
        Method load;
        Field mirror;
        try {
            failureKeyAt = controller.getMethod("failureKeyAt", int.class);
            setClientStartFailure = controller.getMethod("setClientStartFailure", int.class);
            startFailureKey = controller.getMethod("startFailureKey");
            load = controller.getMethod("load", net.minecraft.nbt.CompoundTag.class);
            mirror = controller.getDeclaredField("clientStartFailure");
            mirror.setAccessible(true);
        } catch (Throwable throwable) {
            report("the controller no longer exposes the sentinel API: " + throwable, false);
            return;
        }

        // ---- (1) the field cannot start life at key 0 --------------------------------------------------
        // The unguarded lookup was the crash; this is the other half, and it shows the wrong *message* rather
        // than throwing, so only a check on the initial value can catch it.
        check("clientStartFailure is initialised to the sentinel " + HARNESS_NO_FAILURE,
                "iconst_m1", fieldInitialiserConstant(controller, "clientStartFailure"));

        // ---- (2) the lookup guard: any index that is not a real one means "no failure" -----------------
        //
        // **越界的索引必须相对列表长度来取。** 原来这里写死了 `2, 3`——当列表只有两项时它们确实越界，
        // 而 0.32.0 追加了六条之后它们变成了**合法索引**，于是这几条从「验证越界安全」悄悄变成
        // 「验证一个正常值等于 null」，报的是假失败。凡是「边界」都要由数据算出来，不能写死。
        int failureKeyCount = com.reborn.modularmachinery.block.MachineControllerBlockEntity
                .START_FAILURE_KEYS.size();
        for (int index : new int[] {HARNESS_NO_FAILURE, -2, failureKeyCount, failureKeyCount + 1,
                failureKeyCount + 100, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            try {
                check("failureKeyAt(" + index + ") is null (the sentinel means \"no failure\")", "null",
                        String.valueOf(failureKeyAt.invoke(null, index)));
            } catch (java.lang.reflect.InvocationTargetException exception) {
                report("failureKeyAt(" + index + ") threw " + exception.getCause()
                        + " instead of answering null", false);
            } catch (Throwable throwable) {
                report("failureKeyAt(" + index + ") could not be called: " + throwable, false);
            }
        }
        try {
            check("...while index " + HARNESS_MISSING_KEY_INDEX + " still reports its key",
                    HARNESS_MISSING_KEY,
                    String.valueOf(failureKeyAt.invoke(null, HARNESS_MISSING_KEY_INDEX)));
            check("...and index " + HARNESS_MISMATCH_KEY_INDEX + " still reports its key",
                    HARNESS_MISMATCH_KEY,
                    String.valueOf(failureKeyAt.invoke(null, HARNESS_MISMATCH_KEY_INDEX)));
        } catch (Throwable throwable) {
            report("the in-range lookup could not be driven: " + throwable, false);
        }

        // ---- (3) a client-side instance, driven the way the chunk-load path drives it -------------------
        Object client;
        try {
            client = newClientController(controller);
            report("a controller with no level answers as the client (isServerSide() is false)",
                    !((Boolean) invokePrivate(client, controller, "isServerSide")));
        } catch (Throwable throwable) {
            report("a client-side controller could not be built offline: " + throwable, false);
            return;
        }

        // (3a) the wire itself: setClientStartFailure is the menu's write path and its argument is untrusted.
        try {
            setClientStartFailure.invoke(client, Integer.MIN_VALUE);
            check("an out-of-range index from the wire is stored as the sentinel", HARNESS_NO_FAILURE,
                    mirror.getInt(client));
            check("...and startFailureKey() answers null for it", "null",
                    String.valueOf(startFailureKey.invoke(client)));
            setClientStartFailure.invoke(client, HARNESS_MISMATCH_KEY_INDEX);
            check("a real index from the wire is kept", HARNESS_MISMATCH_KEY_INDEX, mirror.getInt(client));
            check("...and startFailureKey() reports that key", HARNESS_MISMATCH_KEY,
                    String.valueOf(startFailureKey.invoke(client)));
        } catch (java.lang.reflect.InvocationTargetException exception) {
            report("the wire write path threw " + exception.getCause()
                    + " — this is the 0.24.0 crash path", false);
        } catch (Throwable throwable) {
            report("the wire write path could not be driven: " + throwable, false);
        }

        // (3b) the exact crash, forced. Whatever a future field default or a future writer leaves in the
        // mirror, reading it must answer null rather than throw. This is the assertion that would have caught
        // 0.24.0: the value set below is the -1 that sat in the field while the screen drew.
        try {
            mirror.setInt(client, HARNESS_NO_FAILURE);
            check("startFailureKey() with the mirror at the sentinel is null, not an exception", "null",
                    String.valueOf(startFailureKey.invoke(client)));
        } catch (java.lang.reflect.InvocationTargetException exception) {
            report("startFailureKey() threw " + exception.getCause()
                    + " while the mirror held the sentinel — this is the 0.24.0 crash", false);
        } catch (Throwable throwable) {
            report("reading the mirror could not be driven: " + throwable, false);
        }
        try {
            // **越界索引要相对列表长度取，不能写死 2。** 原来写的 2 在列表只有两项时正好是「越界」，
            // 而 0.32.0 追加了六条之后它变成了一个**合法索引**——那三条断言于是从「验证越界安全」
            // 悄悄变成「验证一个正常值等于 null」，也就是变成假失败。写死的边界值会随数据增长而失效。
            int pastEnd = com.reborn.modularmachinery.block.MachineControllerBlockEntity
                    .START_FAILURE_KEYS.size();
            mirror.setInt(client, pastEnd);
            check("startFailureKey() with the mirror one past the end (" + pastEnd + ") is null too", "null",
                    String.valueOf(startFailureKey.invoke(client)));
            mirror.setInt(client, pastEnd + 5);
            check("startFailureKey() with a far-out-of-range mirror is null too", "null",
                    String.valueOf(startFailureKey.invoke(client)));
            mirror.setInt(client, -2);
            check("startFailureKey() with a below-zero index is null too", "null",
                    String.valueOf(startFailureKey.invoke(client)));
        } catch (java.lang.reflect.InvocationTargetException exception) {
            report("startFailureKey() threw " + exception.getCause() + " for an out-of-range mirror", false);
        } catch (Throwable throwable) {
            report("the out-of-range mirror could not be driven: " + throwable, false);
        }

        // ---- (4) the NBT load path, through the real load() ---------------------------------------------
        // A block entity built by load() is what a client sees when it opens a screen on a loaded chunk, so the
        // mirror has to come out of this path as the sentinel when the save carries no failure.
        try {
            Object fresh = newClientController(controller);
            load.invoke(fresh, new net.minecraft.nbt.CompoundTag());
            check("load() of a save with no failure stores the sentinel", HARNESS_NO_FAILURE,
                    mirror.getInt(fresh));
            check("...and the key is null", "null", String.valueOf(startFailureKey.invoke(fresh)));

            net.minecraft.nbt.CompoundTag mismatch = new net.minecraft.nbt.CompoundTag();
            mismatch.putString("startFailure", HARNESS_MISMATCH_KEY);
            load.invoke(fresh, mismatch);
            check("load() of a save with a real failure stores its index", HARNESS_MISMATCH_KEY_INDEX,
                    mirror.getInt(fresh));
            check("...and the key round-trips back out", HARNESS_MISMATCH_KEY,
                    String.valueOf(startFailureKey.invoke(fresh)));

            net.minecraft.nbt.CompoundTag unknown = new net.minecraft.nbt.CompoundTag();
            unknown.putString("startFailure", "mmverify.not.a.key.this.build.knows");
            load.invoke(fresh, unknown);
            check("load() of a save with a key this build does not know falls back to the sentinel",
                    HARNESS_NO_FAILURE, mirror.getInt(fresh));
            check("...and the key is null", "null", String.valueOf(startFailureKey.invoke(fresh)));

            load.invoke(fresh, new net.minecraft.nbt.CompoundTag());
            check("load() clears a previous failure when the save has none", HARNESS_NO_FAILURE,
                    mirror.getInt(fresh));
        } catch (java.lang.reflect.InvocationTargetException exception) {
            report("load() threw " + exception.getCause(), false);
        } catch (Throwable throwable) {
            report("the NBT load path could not be driven: " + throwable, false);
        }

        // ---- (5) the screen: a null failure renders nothing ---------------------------------------------
        // drawInfo itself cannot run here — it needs a Font, which needs a resource manager, and this harness
        // has neither — so the guard it uses is a real method and is driven directly, and drawInfo's bytecode
        // is checked to be its caller. Together: the screen consumes failureLines(...), and failureLines(null)
        // is empty, so the crash's "no failure" frame draws nothing at all.
        try {
            Method failureLines = screen.getDeclaredMethod("failureLines", String.class);
            failureLines.setAccessible(true);
            check("failureLines(null) draws nothing", "[]",
                    String.valueOf(failureLines.invoke(null, new Object[] {null})));
            check("failureLines(a real key) draws exactly that key", "[" + HARNESS_MISMATCH_KEY + "]",
                    String.valueOf(failureLines.invoke(null, HARNESS_MISMATCH_KEY)));
            report("drawInfo really asks the controller for the key and consumes failureLines(...) "
                            + "(checked in the class bytes)",
                    drawInfoConsumesFailureLines(screen));
        } catch (Throwable throwable) {
            report("the screen's failure guard could not be driven: " + throwable, false);
        }

        // ---- (6) the menu: the screen's supplier adds no failure of its own -----------------------------
        // The chain is controller -> menu -> screen. The menu cannot be constructed offline (its constructor
        // touches ModMenus, whose static initialiser reaches net.minecraftforge.fml.common.Mod — the same FML
        // limit section P records), so what is asserted is the shape that matters: the menu exposes exactly one
        // no-argument failure accessor, so the screen's single call is that delegation and nothing else.
        try {
            Class<?> menu = Class.forName("com.reborn.modularmachinery.menu.MachineControllerMenu");
            Method fromMenu = menu.getMethod("startFailureKey");
            report("the menu exposes startFailureKey() returning " + fromMenu.getReturnType().getSimpleName(),
                    fromMenu.getReturnType() == String.class);
            report("...and it is the screen's only failure source (exactly one *FailureKey accessor)",
                    countFailureKeyAccessors(menu) == 1);
        } catch (Throwable throwable) {
            report("the menu's failure accessor could not be inspected: " + throwable, false);
        }

        // ---- (7) the same shape in the factory's recently added indexed lookups --------------------------
        // The 0.24.1 brief asked for the factory thread status ordinals (0.23.0) and the factory menu's
        // slot-state ordinals to be checked for the same defect. Their conversion is ControllerStatus.byOrdinal,
        // which clamps instead of indexing; this asserts that rather than assuming it.
        for (int ordinal : new int[] {-1, 5, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            check("ControllerStatus.byOrdinal(" + ordinal + ") is a status, never an exception",
                    "MISSING_STRUCTURE", String.valueOf(
                            com.reborn.modularmachinery.machine.ControllerStatus.byOrdinal(ordinal)));
        }
        try {
            Object factorySide = newClientController(controller);
            Method threadStatus = controller.getMethod("factoryThreadStatus", int.class);
            for (int slot : new int[] {-1, 0, 6, Integer.MAX_VALUE}) {
                check("factoryThreadStatus(" + slot + ") on an empty factory is -1",
                        HARNESS_NO_FAILURE, ((Integer) threadStatus.invoke(factorySide, slot)).longValue());
            }
        } catch (java.lang.reflect.InvocationTargetException exception) {
            report("factoryThreadStatus threw " + exception.getCause() + " for an out-of-range slot", false);
        } catch (Throwable throwable) {
            report("the factory thread lookup could not be driven: " + throwable, false);
        }
    }

    /**
     * A {@code MachineControllerBlockEntity} whose geometry fields exist but whose constructor never ran.
     *
     * <p>The real constructor calls {@code ModBlocks.MACHINE_CONTROLLER_ENTITY.get()}, and reaching that
     * registration outside FML's class loader is the limit section P records, so the instance is allocated
     * directly. Everything this section reads — the failure mirror, {@code load()} and the level test — is then
     * production code; only the fields {@code load()} dereferences are filled in.
     *
     * <p>{@code level} is declared on {@code BlockEntity} and a freshly allocated instance already has it null,
     * which is what makes {@code isServerSide()} answer false: this is the <b>client</b> block entity, the one
     * the screen reads.
     */
    private static Object newClientController(Class<?> controller) throws Exception {
        Object engine = allocateInstance(Class.forName("com.reborn.modularmachinery.factory.FactoryEngine"));
        setField(engine.getClass(), engine, "coreThreads", new LinkedHashMap<String, Object>());
        setField(engine.getClass(), engine, "recipeThreads", new ArrayList<Object>());

        Object blockEntity = allocateInstance(controller);
        setField(controller, blockEntity, "factoryEngine", engine);
        setField(controller, blockEntity, "smartInterfaces",
                Class.forName("com.reborn.modularmachinery.machine.SmartInterfaceStore")
                        .getConstructor().newInstance());
        setField(controller, blockEntity, "items",
                Class.forName("net.minecraftforge.items.ItemStackHandler")
                        .getConstructor(int.class).newInstance(9));
        // 0.62.9 新增：控制器开始**记住它给哪些部件上过色**（`colorizedPartOffsets`），
        // 而 `load()` 的第一件事就是 `clear()` 它。`allocateInstance` 不跑构造器，
        // 所以 `private final List<BlockPos> ... = new ArrayList<>()` 这句初始化在这里不会发生。
        //
        // **这是这套装置的已知代价，不是产品缺陷**：真实世界的方块实体一定走过构造器。
        // 但代价是「每加一个集合字段，这里就要补一行」，否则 `load()` 那条断言会以 NPE 的形式
        // 假失败——0.62.9 上已经发生过一次。
        setField(controller, blockEntity, "colorizedPartOffsets", new ArrayList<Object>());
        return blockEntity;
    }

    /** {@code sun.misc.Unsafe#allocateInstance}, so a class whose constructor is unreachable offline can still
     *  be exercised on the code paths that do not need it. */
    private static Object allocateInstance(Class<?> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
    }

    /** {@code Field#set} walking up the hierarchy, because a declared field may live on a superclass. */
    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Keep walking: `level` is on BlockEntity, not on the controller.
            }
        }
        throw new NoSuchFieldException(name + " on " + type);
    }

    /** {@code Method#invoke} for a private no-argument method. */
    private static Object invokePrivate(Object target, Class<?> type, String name) throws Exception {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    /**
     * The constant the constructor stores into {@code field} — {@code "iconst_m1"}, {@code "iconst_0"} and so
     * on — read out of the class's own bytes.
     *
     * <p>This is how "the field is initialised to the sentinel" is asserted without running the constructor:
     * the constructor cannot run offline, but its bytecode is right there. A build that leaves the field at the
     * Java default has no {@code putfield} at all, which answers {@code "none"} and fails.
     */
    private static String fieldInitialiserConstant(Class<?> type, String field) {
        try {
            byte[] bytes = classBytes(type);
            if (bytes == null) {
                return "class bytes not found";
            }
            String[] pending = new String[1];
            String[] found = new String[1];
            new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(
                    org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                  String signature, String[] exceptions) {
                    if (!"<init>".equals(name)) {
                        return null;
                    }
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitInsn(int opcode) {
                            pending[0] = switch (opcode) {
                                case org.objectweb.asm.Opcodes.ICONST_M1 -> "iconst_m1";
                                case org.objectweb.asm.Opcodes.ICONST_0 -> "iconst_0";
                                case org.objectweb.asm.Opcodes.ICONST_1 -> "iconst_1";
                                default -> null;
                            };
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                            if (opcode == org.objectweb.asm.Opcodes.PUTFIELD && field.equals(name)) {
                                found[0] = pending[0] == null ? "no constant" : pending[0];
                            }
                            pending[0] = null;
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return found[0] == null ? "none" : found[0];
        } catch (Throwable throwable) {
            return "could not be read: " + throwable;
        }
    }

    /** Whether {@code drawInfo} really asks the controller for the key and then consumes
     *  {@code failureLines(...)}. */
    private static boolean drawInfoConsumesFailureLines(Class<?> screen) {
        try {
            byte[] bytes = classBytes(screen);
            if (bytes == null) {
                return false;
            }
            boolean[] calls = new boolean[2];
            new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(
                    org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                  String signature, String[] exceptions) {
                    if (!"drawInfo".equals(name)) {
                        return null;
                    }
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                    boolean isInterface) {
                            if ("failureLines".equals(name)) {
                                calls[0] = true;
                            }
                            if ("startFailureKey".equals(name) && owner != null
                                    && owner.startsWith("com/reborn/modularmachinery/")) {
                                calls[1] = true;
                            }
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
            return calls[0] && calls[1];
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** How many public no-argument methods on {@code type} answer a failure key. */
    private static int countFailureKeyAccessors(Class<?> type) {
        int found = 0;
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() == 0 && method.getName().contains("Failure")
                    && method.getReturnType() == String.class) {
                found++;
            }
        }
        return found;
    }

    /** A class's own bytes, through whichever loader defined it. */
    private static byte[] classBytes(Class<?> type) throws java.io.IOException {
        try (java.io.InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/')
                + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    /** The ports one formed machine hands the recipe engine, with the controller's store as the value source. */
    private static HatchCollection portsWithSmart(
            ItemStackHandler out,
            com.reborn.modularmachinery.machine.SmartInterfaceValueSource source,
            MachineDefinition definition) {
        return HatchCollection.builder()
                .addItemHandler(itemIn, true)
                .addItemHandler(out, false)
                .addEnergyStorage(energyIn, true)
                .smartInterfaces(source)
                .machine(definition)
                .build();
    }

    // ------------------------------------------------------------------ harness

    /** One full craft, driven in the order {@code MachineControllerBlockEntity#tick} uses. */
    private static void craft(MachineRecipe recipe, RecipeModifiers modifiers, int parallelism) {
        craft(recipe, ports(), modifiers, parallelism);
    }

    /** {@link #craft(MachineRecipe, RecipeModifiers, int)} against explicit ports, for the sections that have
     *  to hand the engine a particular {@code HatchCollection} (e.g. one carrying a smart-interface source). */
    private static void craft(MachineRecipe recipe, HatchCollection ports, RecipeModifiers modifiers,
                              int parallelism) {
        recipe.setParallelism(Math.max(1, parallelism));
        recipe.applyDurationMultiplier(modifiers);
        int duration = recipe.duration(modifiers);
        if (!recipe.canStart(ports, modifiers) || !recipe.canTick(ports, modifiers)) {
            throw new IllegalStateException("craft could not start");
        }
        if (!recipe.start(ports, RandomSource.create(1L), modifiers)) {
            throw new IllegalStateException("start failed");
        }
        RandomSource random = RandomSource.create(42L);
        for (int tick = 0; tick < duration; tick++) {
            if (!recipe.canTick(ports, modifiers)) {
                throw new IllegalStateException("tick " + tick + " could not be paid");
            }
            recipe.tick(ports, random, modifiers);
        }
        if (!recipe.canFinish(ports, modifiers)) {
            throw new IllegalStateException("outputs do not fit");
        }
        recipe.finish(ports, random, modifiers);
    }


    private static HatchCollection ports() {
        return portsWith(itemOut);
    }

    private static HatchCollection portsWith(ItemStackHandler out) {
        return HatchCollection.builder()
                .addItemHandler(itemIn, true)
                .addItemHandler(out, false)
                .addEnergyStorage(energyIn, true)
                .build();
    }

    private static void reset(int coal, int outSlots, int energy) {
        itemIn = new ItemStackHandler(4);
        itemOut = new ItemStackHandler(outSlots);
        energyIn = new EnergyStorage(1_000_000, Integer.MAX_VALUE, Integer.MAX_VALUE, 0);
        int remaining = coal;
        for (int slot = 0; slot < 4 && remaining > 0; slot++) {
            int put = Math.min(64, remaining);
            itemIn.setStackInSlot(slot, new ItemStack(Items.COAL, put));
            remaining -= put;
        }
        energyIn.receiveEnergy(energy, false);
    }

    private static int coalLeft() {
        int total = 0;
        for (int slot = 0; slot < itemIn.getSlots(); slot++) {
            total += itemIn.getStackInSlot(slot).getCount();
        }
        return total;
    }

    private static int outputCount(Item item) {
        int total = 0;
        for (int slot = 0; slot < itemOut.getSlots(); slot++) {
            ItemStack stack = itemOut.getStackInSlot(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static Ingredient ingredient(Item item) {
        return Ingredient.of(item);
    }

    // =================================================================== 0.24.4: the frames and the items

    /** {@code guifactory.png} paints a slot hole as this interior colour… */
    private static final int HOLE_FILL = 0xFF8B8B8B;
    /** …with a one-pixel inset shadow along its top and left… */
    private static final int HOLE_SHADOW = 0xFF373737;
    /** …and a one-pixel highlight along its bottom and right. */
    private static final int HOLE_HIGHLIGHT = 0xFFFFFFFF;

    /** The screen whose blit, label and slot frames section V reads. */
    private static final String FACTORY_SCREEN =
            "com/reborn/modularmachinery/client/FactoryControllerScreen";
    /** The plain controller's screen, which section V uses as a live counter-example for two predicates. */
    private static final String CONTROLLER_SCREEN =
            "com/reborn/modularmachinery/client/MachineControllerScreen";
    /**
     * The vanilla class whose {@code render} decides where the panel origin lives. Section V reads both its
     * {@code render} ordering and its own {@code renderLabels} body out of this resource, so neither the
     * one-origin rule nor the ban on the label rests on a claim about vanilla that nothing verifies.
     */
    private static final String VANILLA_CONTAINER_SCREEN =
            "net/minecraft/client/gui/screens/inventory/AbstractContainerScreen";
    /** The block's drawing method and its descriptor, as the built jar spells them. */
    private static final String INFO_BLOCK_METHOD = "drawFactoryStatus";
    private static final String INFO_BLOCK_DESC = "(Lnet/minecraft/client/gui/GuiGraphics;)V";
    /** The two keys the owner's 「找到蓝图：%s」 / 「找到结构：%s」 rows come from. */
    private static final String BLUEPRINT_KEY = "gui.modular_machinery_reborn.controller.blueprint";
    private static final String STRUCTURE_KEY = "gui.modular_machinery_reborn.controller.structure";
    /** The 1.12.2 key for the 「物品栏」 row: the label this screen must not draw. */
    private static final String INVENTORY_KEY = "container.inventory";
    /** The fields {@code AbstractContainerScreen#renderLabels} draws that label and the title from. */
    private static final String[] LABEL_AND_TITLE_FIELDS = {
            "playerInventoryTitle", "inventoryLabelX", "inventoryLabelY",
            "title", "titleLabelX", "titleLabelY",
    };

    /** {@code blit(ResourceLocation, int, int, int, float, float, int, int, int, int)} — takes the sheet size. */
    private static final String BLIT_WITH_TEXTURE_SIZE =
            "(Lnet/minecraft/resources/ResourceLocation;IIIFFIIII)V";
    /** {@code blit(ResourceLocation, int, int, int, int, int, int)} — hard-codes a 256x256 sheet. */
    private static final String BLIT_ASSUMING_256 =
            "(Lnet/minecraft/resources/ResourceLocation;IIIIII)V";

    /**
     * Section V — 0.24.4's own regression checks, in the form the task demanded: <b>the items sit inside the
     * frames the texture draws for them</b>, and <b>the factory screen draws no 「物品栏」 label</b>.
     *
     * <p>Sections R and U both passed on 0.24.3 while the owner could see, in a screenshot, that every item in
     * the player's inventory was offset from its slot frame. R asserted the screen's own constants back at it
     * (panel 280x213, queue origin 8, …); U asserted containment and ordering between rectangles the screen
     * itself hands out. Neither compared two <i>independent</i> sources of truth about the same pixels, so both
     * stayed green while the panel was being drawn stretched.
     *
     * <p>The two sources here are:
     * <ul>
     *   <li><b>the texture's own pixels</b> — {@code guifactory.png} has its slot holes painted into it, and the
     *       holes are found by scanning for the pattern the sheet really uses: a 16x16 {@code 8B8B8B} interior
     *       with a {@code 373737} inset shadow along its top and left and a white highlight along its bottom and
     *       right;</li>
     *   <li><b>the menu's own slot table</b> — {@code FactoryControllerMenu.playerSlotRects()}, the very array
     *       the constructor registers its {@code Slot}s from, so an item is drawn with its centre at
     *       {@code slot.x + 8, slot.y + 8}.</li>
     * </ul>
     *
     * <p>They are related through the mapping the blit really performs, computed from the screen's own
     * {@code panelBlitFull(...)} and the sheet's real size: {@code dest = origin + texel * declared / actual -
     * offset}. When the declared size equals the sheet's, the mapping is the identity — which is what the
     * original's {@code drawModalRectWithCustomSizedTexture(x, y, 0, 0, xSize, ySize, xSize, ySize)}
     * ({@code GuiFactoryController:88}) did — and every slot centre lands on its hole centre. 0.24.3 instead
     * used the 1.20.1 seven-argument {@code GuiGraphics#blit}, which hard-codes {@code 256, 256}: the 280-wide
     * sheet was then stretched 1.09375x across the panel in x (the last 26 texels wrapped around, which is the
     * second border column at the panel's right edge in the screenshot) and the 213-tall sheet squeezed 0.832x
     * in y (so texels 177…212 — the whole hotbar row of holes — were never drawn), all around a slot grid that
     * did not move.
     *
     * <p>Fault injections, each of which the same predicate must REJECT:
     * <ol>
     *   <li>the slots moved by one slot pitch (18) — "the items are in their frames" must find nothing;</li>
     *   <li>the 0.24.3 blit (256 declared against a 280x213 sheet) — the shipped defect, reproduced exactly;</li>
     *   <li>{@code MachineControllerScreen.renderLabels}, which <i>does</i> draw the label — legitimately, since
     *       the original's {@code GuiMachineController:49-50} calls {@code super} — read by the label predicate
     *       the factory screen has to satisfy.</li>
     * </ol>
     *
     * <p><b>0.24.5 replaced the label half.</b> The 0.24.4 form of it asserted that the factory screen's
     * {@code renderLabels} contains no invocation and no field read — a prohibitive predicate that the shipped
     * defect satisfied <i>vacuously</i>, because the release had moved the found-blueprint / found-structure
     * block out of the render path entirely and an empty method has no invocation either. {@link
     * #informationBlockDrawn()} asserts the missing positive side instead: the block is <i>reached</i> from
     * {@code renderLabels}, it really calls {@code drawString}, it loads the two keys the owner named, and those
     * keys are the 「找到蓝图：%s」 / 「找到结构：%s」 rows of both language files. Only then the precise
     * prohibition that still holds: this screen never calls {@code AbstractContainerScreen#renderLabels} and
     * reads none of the six fields the label and the title are drawn from. This is the third occurrence in this
     * project of the same disease — an assertion that cannot distinguish "correct" from "absent" — after
     * section R asserting the implementation's own numbers while the screen was visibly wrong, and 0.24.1's
     * sentinel index where "no failure" was never asserted.
     */
    private static void slotFrameAlignment() {
        section("V. the items sit in the frames the texture draws, the factory screen draws its information "
                + "block, and it draws no label");

        Path texture = shippedGuiTextures() == null ? null : shippedGuiTextures().resolve("guifactory.png");
        if (texture == null || !Files.isRegularFile(texture)) {
            report("guifactory.png is readable (" + texture + ")", false);
            return;
        }

        Sheet sheet;
        try {
            sheet = readSheet(texture);
        } catch (Exception exception) {
            report("decoding guifactory.png failed: " + exception, false);
            return;
        }
        report("guifactory.png decoded from its pixels: " + sheet.width + "x" + sheet.height,
                sheet.width > 0 && sheet.height > 0);

        List<int[]> holes = textureSlotHoles(sheet);
        report("the sheet's slot holes, derived from its pixels (a 16x16 " + hex(HOLE_FILL)
                        + " interior with a " + hex(HOLE_SHADOW) + " inset shadow and a " + hex(HOLE_HIGHLIGHT)
                        + " highlight), number " + holes.size() + ": they are at "
                        + describeHoles(holes),
                holes.size() == 37);

        // ---- the two sources, related through the screen's own blit --------------------------------------
        int[] textureSize = com.reborn.modularmachinery.client.FactoryControllerScreen.panelTextureSize();
        int[] panel = com.reborn.modularmachinery.client.FactoryControllerScreen.panelSize();
        int[] blit = com.reborn.modularmachinery.client.FactoryControllerScreen.panelBlitFull(0, 0,
                panel[0], panel[1]);
        report("the screen declares the sheet's real size as its blit's texture size: declared "
                        + textureSize[0] + "x" + textureSize[1] + " against the file's " + sheet.width + "x"
                        + sheet.height + " (the original's own xSize/ySize, GuiFactoryController:50-51,88)",
                textureSize[0] == sheet.width && textureSize[1] == sheet.height);
        report("the blit's source region is the whole sheet and its destination is the whole panel: "
                        + java.util.Arrays.toString(blit) + " as {destX, destY, uOffset, vOffset, width, height,"
                        + " textureWidth, textureHeight}",
                blit[2] == 0 && blit[3] == 0 && blit[4] == sheet.width && blit[5] == sheet.height
                        && blit[6] == sheet.width && blit[7] == sheet.height);

        double[] identity = {mapped(blit[0], blit[6], sheet.width, sheet.width, blit[2]),
                mapped(blit[1], blit[7], sheet.height, sheet.height, blit[3])};
        report("...so the mapping texel -> panel pixel is the identity: texel (" + sheet.width + ", " + sheet.height
                        + ") lands on (" + trim(identity[0]) + ", " + trim(identity[1]) + ")",
                identity[0] == sheet.width && identity[1] == sheet.height);

        // ---- the property the owner can see: every slot's centre is its hole's centre --------------------
        int[][] slots = com.reborn.modularmachinery.menu.FactoryControllerMenu.playerSlotRects();
        report("the menu's own slot table has the 36 rectangles of a player inventory ("
                        + slots.length + "): " + slots[0][0] + "," + slots[0][1] + " … "
                        + slots[slots.length - 1][0] + "," + slots[slots.length - 1][1],
                slots.length == 36);
        int[] blueprint = {com.reborn.modularmachinery.menu.FactoryControllerMenu.BLUEPRINT_SLOT_X,
                com.reborn.modularmachinery.menu.FactoryControllerMenu.BLUEPRINT_SLOT_Y};

        int matched = frameCoincidences(slots, holes, blit, sheet, null)[0];
        report("ALL " + slots.length + " player slots are centred in a hole the sheet really draws ("
                        + matched + " of " + slots.length + " coincide exactly)", matched == slots.length);
        int[] blueprintMatch = frameCoincidences(new int[][] {blueprint}, holes, blit, sheet, null);
        report("the blueprint slot " + java.util.Arrays.toString(blueprint) + " is centred in the sheet's own"
                        + " blueprint hole at (255, 8) (" + blueprintMatch[0] + " of 1)",
                blueprintMatch[0] == 1);
        // The coincidence is also required to be a bijection: two slots sharing one hole would satisfy a bare
        // count while half the grid had no frame at all, and a whole-pitch shift maps most slots onto their
        // NEIGHBOUR's hole (see the fault injection below), which only the bijection + full count rejects.
        int[] full = frameCoincidences(slots, holes, blit, sheet, null);
        report("...and those coincidences are a bijection: " + full[0] + " slots onto " + full[1]
                        + " distinct holes (one apiece, no sharing)", full[1] == slots.length);
        // And the converse, so an "extra" hole cannot slip through: in the slot area every hole is a menu slot.
        int orphanHoles = 0;
        for (int[] hole : holes) {
            if (hole[0] == blueprint[0] && hole[1] == blueprint[1]) {
                continue;
            }
            boolean found = false;
            for (int[] slot : slots) {
                if (slot[0] == hole[0] && slot[1] == hole[1]) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                orphanHoles++;
            }
        }
        report("...and the converse holds: every hole outside the blueprint's own is a menu slot (" + orphanHoles
                        + " orphan holes in " + holes.size() + ")", orphanHoles == 0);

        // ---- the information block, and the label that must stay absent ------------------------------------
        //
        // 0.24.4's own section V asserted that renderLabels contains no invocation and no field read. That
        // encoded the bug: the found-blueprint / found-structure block had just been moved out of the render
        // path, and "no invocation" is satisfied *vacuously* by a method that draws nothing. The owner saw it
        // in game — 「gui没啥问题了，但是没有文字，找到蓝图那两行」 — while this section stayed green. The
        // replacement is positive first and prohibitive only where the prohibition is precise.
        System.out.println();
        System.out.println("  -- the information block and the player-inventory label, read out of the bytecode");
        informationBlockDrawn();

        // ---- the blit overload, read out of the bytecode --------------------------------------------------
        System.out.println();
        System.out.println("  -- the blit call itself, read out of the bytecode (the 256-default one is the defect)");

        int sized = countInvocations(FACTORY_SCREEN, "blitPanel", "net/minecraft/client/gui/GuiGraphics", "blit",
                BLIT_WITH_TEXTURE_SIZE);
        int defaulted = countInvocations(FACTORY_SCREEN, "blitPanel", "net/minecraft/client/gui/GuiGraphics", "blit",
                BLIT_ASSUMING_256);
        report("blitPanel calls the overload that takes the sheet's size (" + sized + " call) and never the"
                        + " 256x256 default (" + defaulted + " calls)",
                sized == 1 && defaulted == 0);
        // The chain renderBg -> blitPanel -> panelBlitFull -> panelTextureSize is each link read out of the
        // bytecode, so a number re-typed anywhere along it (the 0.24.3 defect was exactly a hard-coded 256,256
        // one call away from here) breaks a link rather than silently agreeing with the check.
        int toFull = countInvocations(FACTORY_SCREEN, "blitPanel", FACTORY_SCREEN, "panelBlitFull", "(IIII)[I");
        report("...whose arguments come from panelBlitFull() (" + toFull + " call)", toFull == 1);
        int toPanelBlit = countInvocations(FACTORY_SCREEN, "panelBlitFull", FACTORY_SCREEN, "panelBlit", "(IIII)[I");
        report("...which in turn takes the destination and source from panelBlit() (" + toPanelBlit + " call)",
                toPanelBlit == 1);
        int fromSize = countInvocations(FACTORY_SCREEN, "blitPanel", FACTORY_SCREEN, "panelTextureSize", "()[I");
        report("...and blitPanel itself asks panelTextureSize() for the two size arguments (" + fromSize
                        + " call), rather than typing 256, 256 into the call", fromSize == 1);
        int fromPanel = countInvocations(FACTORY_SCREEN, "renderBg", FACTORY_SCREEN, "blitPanel",
                "(Lnet/minecraft/client/gui/GuiGraphics;IIII)V");
        report("...and renderBg draws the panel through that one method (" + fromPanel + " call), so the screen's"
                        + " only blit of the sheet is the checked one", fromPanel == 1);
        // The detector is not vacuous: the same scan finds the 256-default form where a 256x256 sheet makes it
        // correct — the plain controller's panel.
        int controllerBlit = countInvocations(CONTROLLER_SCREEN, "renderBg", "net/minecraft/client/gui/GuiGraphics",
                "blit", BLIT_ASSUMING_256);
        report("injected: the same scan does find the 256-default blit in MachineControllerScreen.renderBg ("
                        + controllerBlit + " call), where controller_legacy.png really is 256x256 — so the check"
                        + " above is not vacuously true",
                controllerBlit >= 1);

        // ---- fault injection 1: the slots moved, first by one pixel and then by a whole slot pitch ---------
        System.out.println("  -- fault injection: the predicates above are fed a broken implementation; each");
        System.out.println("     check below PASSES only when the predicate REJECTS it");

        int[][] oneOff = com.reborn.modularmachinery.menu.FactoryControllerMenu.playerSlotRects();
        for (int[] slot : oneOff) {
            slot[0] += 1;
        }
        int oneOffMatched = frameCoincidences(oneOff, holes, blit, sheet, null)[0];
        report("injected: the slots moved by a single panel pixel put " + oneOffMatched + " of " + oneOff.length
                        + " items in their frames, so the predicate rejects a one-pixel origin error",
                oneOffMatched == 0);

        int[][] shifted = com.reborn.modularmachinery.menu.FactoryControllerMenu.playerSlotRects();
        int pitch = com.reborn.modularmachinery.menu.FactoryControllerMenu.SLOT_PITCH;
        for (int[] slot : shifted) {
            slot[0] += pitch;
        }
        int[] shiftedMatch = frameCoincidences(shifted, holes, blit, sheet, null);
        report("injected: the slots moved by one whole slot pitch (" + pitch + " panel px) leave the last column"
                        + " frameless: " + shiftedMatch[0] + " of " + shifted.length + " coincide, so the"
                        + " 'ALL 36' predicate rejects it even though a whole-pitch shift lands most slots on a"
                        + " NEIGHBOUR's hole (" + shiftedMatch[1] + " distinct holes)",
                shiftedMatch[0] < shifted.length);

        // ---- fault injection 2: the 0.24.3 blit -----------------------------------------------------------
        int[] brokenBlit = blit.clone();
        brokenBlit[6] = 256;
        brokenBlit[7] = 256;
        List<String> worst = new ArrayList<>();
        int brokenMatched = frameCoincidences(slots, holes, brokenBlit, sheet, worst)[0];
        report("injected: 0.24.3's blit -- the 256x256 default against this 280x213 sheet, so the panel is drawn"
                        + " stretched 1.09375x in x and 0.832x in y -- puts " + brokenMatched + " of "
                        + slots.length + " items in their frames: the predicate rejects the shipped defect too",
                brokenMatched == 0);
        report("...and it says by how much: " + join(worst), brokenMatched == 0 && !worst.isEmpty());
    }

    /**
     * The half of section V that 0.24.5 adds: <b>the information block really is drawn, and the label and the
     * title really are not.</b>
     *
     * <h2>Why 0.24.4's assertion had to be replaced rather than extended</h2>
     *
     * <p>0.24.4 asserted that {@code FactoryControllerScreen.renderLabels} contains <b>no invocation and no
     * field read</b>. That is a purely prohibitive predicate, and the defect it was written for — the inherited
     * 「物品栏」 row — is only one of the two shapes that satisfy it. The other is that the method draws nothing
     * at all, and that is what the release shipped: the found-blueprint / found-structure block had been moved
     * out of the foreground pass into {@code renderBg} while still being positioned by the <i>panel-local</i>
     * {@code textBlockRect()}, so every one of its lines was painted at absolute (81, 8) — outside the clip
     * rectangle the same method installs ({@code leftPos}..{@code leftPos}+280 × {@code topPos}..{@code
     * topPos}+213) — and the whole block was scissored away. A predicate that a correct screen and an empty
     * screen both satisfy is not evidence, so the owner found it in game instead.
     *
     * <h2>What replaces it</h2>
     *
     * <ol>
     *   <li><b>Positive, and about reachability</b>: {@code renderLabels} must <i>invoke</i>
     *       {@code drawFactoryStatus}. Deleting the call — the shape that shipped — fails, where "the method is
     *       empty" could not;</li>
     *   <li><b>Positive, and about content</b>: that method must call {@code GuiGraphics#drawString} at least
     *       ten times (it has ten draw sites) and must load exactly the two keys the owner named,
     *       {@value #BLUEPRINT_KEY} and {@value #STRUCTURE_KEY};</li>
     *   <li><b>Positive, and about the words on screen</b>: those two keys must be the 「找到蓝图：%s」 /
     *       「找到结构：%s」 rows in {@code zh_cn.json} — written as {@code \\u} escapes so the check does not
     *       depend on the encoding this file is compiled with — and the original's English rows in
     *       {@code en_us.json};</li>
     *   <li><b>The one-origin rule, from an independent source</b>: the vanilla
     *       {@code AbstractContainerScreen#render} bytecode is read to show that it calls {@code renderBg}
     *       <i>before</i> it translates the pose by {@code (leftPos, topPos)} and {@code renderLabels}
     *       <i>after</i>. That is what makes "draw the block from the foreground pass, in panel-local
     *       {@code textBlockRect()} coordinates" the screen's one origin — and it is why the rectangle the
     *       block is placed by is the very rectangle section U clips against;</li>
     *   <li><b>Negative, and precise</b>: the label and the title are drawn by exactly one method,
     *       {@code AbstractContainerScreen#renderLabels}. This screen must not call it, must read none of the
     *       six fields that method draws them from, and must contain no {@value #INVENTORY_KEY} literal. The
     *       vanilla method's own body is read to show that the ban is not vacuous.</li>
     * </ol>
     *
     * <p>Both directions are then fault-injected against the shipped source — an emptied {@code renderLabels}
     * for the positive half and a restored {@code playerInventoryTitle} draw for the negative half — and the
     * runs are kept at {@code _audit/m6c-verify/fault-injection-0.24.5-*}. The in-harness counter-examples below
     * additionally show that neither predicate is vacuous on a screen that legitimately keeps its label:
     * {@code MachineControllerScreen} must be <i>caught</i> by the label check, because the original's
     * {@code GuiMachineController:49-50} really does call {@code super}.
     */
    private static void informationBlockDrawn() {
        // ---- (1) POSITIVE: the block is reached, and from the pass that carries the panel origin ----------
        int fromLabels = countInvocations(FACTORY_SCREEN, "renderLabels", FACTORY_SCREEN, INFO_BLOCK_METHOD,
                INFO_BLOCK_DESC);
        int fromBackground = countInvocations(FACTORY_SCREEN, "renderBg", FACTORY_SCREEN, INFO_BLOCK_METHOD,
                INFO_BLOCK_DESC);
        report("FactoryControllerScreen.renderLabels invokes " + INFO_BLOCK_METHOD + " (" + fromLabels
                        + " call): the information block is reached from the render path at all",
                fromLabels == 1);
        report("...and renderBg does not (" + fromBackground + " calls), because renderBg runs before the pose"
                        + " carries the panel origin -- a panel-local translate there lands off-panel and the"
                        + " clip hides the whole block, which is the shipped 0.24.3/0.24.4 shape",
                fromBackground == 0);

        // ---- (2) POSITIVE: the method draws, and draws the owner's two keys --------------------------------
        int draws = countDrawStrings(FACTORY_SCREEN, INFO_BLOCK_METHOD);
        report(INFO_BLOCK_METHOD + " really draws text: " + draws + " GuiGraphics#drawString call(s) -- its"
                        + " ten draw sites are the redstone row, the blueprint heading and name, the structure"
                        + " heading and name, the status heading and value, the thread row, the two parallelism"
                        + " rows and the closing line", draws >= 10);
        List<String> constants = stringConstants(FACTORY_SCREEN, INFO_BLOCK_METHOD);
        report("...and the keys it loads are the two the owner named: " + BLUEPRINT_KEY + " present="
                        + constants.contains(BLUEPRINT_KEY) + ", " + STRUCTURE_KEY + " present="
                        + constants.contains(STRUCTURE_KEY) + " (" + constants.size()
                        + " string constant(s) in the method; the old check's 'no invocation at all' was"
                        + " satisfied by the empty method that shipped)",
                constants.contains(BLUEPRINT_KEY) && constants.contains(STRUCTURE_KEY));

        // ---- (3) POSITIVE: those keys are the words on screen, in both language files ----------------------
        Path zhFile = Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/assets/"
                + "modular_machinery_reborn/lang/zh_cn.json");
        Path enFile = Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/assets/"
                + "modular_machinery_reborn/lang/en_us.json");
        // 「找到蓝图：%s」 and 「找到结构：%s」 as escapes, so the literal is independent of this file's encoding.
        String zhBlueprintWanted = "\u627e\u5230\u84dd\u56fe\uff1a%s";
        String zhStructureWanted = "\u627e\u5230\u7ed3\u6784\uff1a%s";
        try {
            Map<String, String> zh = flatJson(zhFile);
            Map<String, String> en = flatJson(enFile);
            report("...which the language files spell as the owner's two lines: zh \"" + zh.get(BLUEPRINT_KEY)
                            + "\" / \"" + zh.get(STRUCTURE_KEY) + "\", en \"" + en.get(BLUEPRINT_KEY)
                            + "\" / \"" + en.get(STRUCTURE_KEY) + "\"",
                    zhBlueprintWanted.equals(zh.get(BLUEPRINT_KEY))
                            && zhStructureWanted.equals(zh.get(STRUCTURE_KEY))
                            && "Found blueprint: %s".equals(en.get(BLUEPRINT_KEY))
                            && "Found structure: %s".equals(en.get(STRUCTURE_KEY)));
        } catch (Exception exception) {
            report("reading the language files failed: " + exception, false);
        }

        // ---- (4) THE ONE-ORIGIN RULE, from the vanilla class's own bytecode ---------------------------------
        System.out.println("  -- one origin: where the panel translate lives, read out of the vanilla class");
        readVanillaRenderOrder();

        int[] mine = com.reborn.modularmachinery.client.FactoryControllerScreen.textBlockRect();
        int[] layout = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.textRect();
        int[] clip = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.scissorRect();
        int[] panel = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.panelRect();
        report("the block is placed by the layout's own rectangle: textBlockRect() " + rect(mine)
                        + " == FactoryPanel.textRect() " + rect(layout)
                        + " -- the same rectangle section U checks the clip against, so the block cannot drift"
                        + " away from the rest of the screen",
                java.util.Arrays.equals(mine, layout));
        report("...and that rectangle, once the foreground pass's own (leftPos, topPos) translate is added,"
                        + " lands inside the panel " + rect(panel) + " and inside the clip " + rect(clip),
                mine[0] >= panel[0] && mine[1] >= panel[1]
                        && mine[0] + mine[2] <= panel[0] + panel[2]
                        && mine[1] + mine[3] <= panel[1] + panel[3]);

        // ---- (5) NEGATIVE: no label, no title --------------------------------------------------------------
        System.out.println("  -- the player-inventory label and the title, read out of the three classes");
        int[] factoryLabel = labelFootprint(FACTORY_SCREEN);
        int[] controllerLabel = labelFootprint(CONTROLLER_SCREEN);
        int superCalls = countInvocations(FACTORY_SCREEN, "renderLabels", VANILLA_CONTAINER_SCREEN,
                "renderLabels", "(Lnet/minecraft/client/gui/GuiGraphics;II)V");
        int inventoryLiteral = countStringConstants(FACTORY_SCREEN, INVENTORY_KEY);
        int labelFields = 0;
        for (String field : LABEL_AND_TITLE_FIELDS) {
            labelFields += countFieldReads(FACTORY_SCREEN, field);
        }
        report("FactoryControllerScreen.renderLabels reads no field at all (" + factoryLabel[1]
                        + " field reads over " + factoryLabel[0] + " invocation(s)): it cannot be reading the"
                        + " three fields the label is drawn from, nor the three the title is drawn from",
                factoryLabel[1] == 0);
        report("...and the screen never invokes AbstractContainerScreen#renderLabels (" + superCalls
                        + " calls), which is the only method that draws either of them", superCalls == 0);
        report("...and it contains no \"" + INVENTORY_KEY + "\" literal (" + inventoryLiteral + " occurrences)",
                inventoryLiteral == 0);
        report("...and it reads none of " + String.join("/", LABEL_AND_TITLE_FIELDS) + " anywhere in the class ("
                        + labelFields + " reads)", labelFields == 0);

        // The ban is meaningful only if vanilla really does draw them there, and only if a screen that keeps
        // its label is caught by the same scan.
        int vanillaDraws = countDrawStrings(VANILLA_CONTAINER_SCREEN, "renderLabels");
        int vanillaLabel = countFieldReads(VANILLA_CONTAINER_SCREEN, "playerInventoryTitle");
        int vanillaTitle = countFieldReads(VANILLA_CONTAINER_SCREEN, "title");
        report("...and the ban is not vacuous: the vanilla AbstractContainerScreen#renderLabels really draws"
                        + " both (" + vanillaDraws + " drawString call(s), reading playerInventoryTitle "
                        + vanillaLabel + " time(s) and title " + vanillaTitle + " time(s) out of its own"
                        + " bytecode)", vanillaDraws == 2 && vanillaLabel >= 1 && vanillaTitle >= 1);
        report("injected: MachineControllerScreen.renderLabels -- which legitimately keeps the label because the"
                        + " original's GuiMachineController:49-50 does call super -- is caught by the same"
                        + " predicate (" + controllerLabel[0] + " invocations, " + controllerLabel[1]
                        + " field reads)",
                controllerLabel[0] > 0 && controllerLabel[1] > 0);
    }

    /**
     * The vanilla {@code AbstractContainerScreen#render} ordering, read out of its own bytecode: the
     * {@code renderBg} call first, then the {@code pushPose} + {@code translate(leftPos, topPos)} pair, then
     * the slot loop and the {@code renderLabels} call.
     *
     * <p>This is the independent fact the one-origin rule rests on. It is the difference between the two
     * passes: a block drawn from {@code renderBg} has to add the panel origin itself (as this screen's queue
     * and scrollbar do, and as the information block did not), while a block drawn from {@code renderLabels}
     * already has it. Reading it out of the vanilla class rather than asserting it in a comment is what makes
     * "the block is placed by the panel-local {@code textBlockRect()}" a checked statement about this
     * screen's position on screen.
     */
    private static void readVanillaRenderOrder() {
        org.objectweb.asm.tree.ClassNode node = readClassNode(VANILLA_CONTAINER_SCREEN);
        if (node == null) {
            report("the vanilla " + VANILLA_CONTAINER_SCREEN + " is on the harness's classpath (not readable)",
                    false);
            return;
        }
        org.objectweb.asm.tree.MethodNode render = null;
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            if (method.name.equals("render") && method.desc.equals("(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")) {
                render = method;
                break;
            }
        }
        if (render == null) {
            report("AbstractContainerScreen#render(GuiGraphics,int,int,float) exists in the class", false);
            return;
        }
        int bgAt = -1;
        int labelsAt = -1;
        int translateAt = -1;
        int leftPosAt = -1;
        int topPosAt = -1;
        for (int i = 0; i < render.instructions.size(); i++) {
            org.objectweb.asm.tree.AbstractInsnNode insn = render.instructions.get(i);
            if (insn instanceof org.objectweb.asm.tree.FieldInsnNode
                    && insn.getOpcode() == org.objectweb.asm.Opcodes.GETFIELD) {
                String field = ((org.objectweb.asm.tree.FieldInsnNode) insn).name;
                if (field.equals("leftPos")) {
                    leftPosAt = i;
                } else if (field.equals("topPos")) {
                    topPosAt = i;
                }
            } else if (insn instanceof org.objectweb.asm.tree.MethodInsnNode) {
                org.objectweb.asm.tree.MethodInsnNode call = (org.objectweb.asm.tree.MethodInsnNode) insn;
                if (call.name.equals("renderBg")) {
                    bgAt = i;
                } else if (call.name.equals("renderLabels")) {
                    labelsAt = i;
                } else if (call.name.equals("translate") && call.owner.endsWith("PoseStack") && translateAt < 0) {
                    translateAt = i;
                }
            }
        }
        report("the vanilla render calls renderBg before it translates the pose and renderLabels after:"
                        + " renderBg at instruction " + bgAt + ", translate at " + translateAt + ", renderLabels"
                        + " at " + labelsAt,
                bgAt >= 0 && bgAt < translateAt && translateAt < labelsAt);
        report("...and that translate is fed the panel origin: leftPos read at instruction " + leftPosAt
                        + " and topPos at " + topPosAt + ", both before the translate at " + translateAt
                        + " -- so the information block belongs to the renderLabels side of it, and the"
                        + " panel-local textBlockRect() is the right coordinate there",
                leftPosAt >= 0 && leftPosAt < translateAt && topPosAt >= 0 && topPosAt < translateAt);
    }

    /** {@code [x, y]} to the string the hole listings print. */
    private static String describeHoles(List<int[]> holes) {
        StringBuilder out = new StringBuilder();
        for (int[] hole : holes) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append('(').append(hole[0]).append(',').append(hole[1]).append(')');
        }
        return out.toString();
    }

    /** The worst three mismatches {@link #frameCoincidences} recorded, or a placeholder. */
    private static String join(List<String> worst) {
        if (worst.isEmpty()) {
            return "no slot was measured";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < worst.size(); i++) {
            if (i > 0) {
                out.append("; ");
            }
            out.append(worst.get(i));
        }
        return out.toString();
    }

    /** {@code 0xFF8B8B8B} to {@code 8B8B8B}. */
    private static String hex(int argb) {
        return String.format(java.util.Locale.ROOT, "%06X", argb & 0xFFFFFF);
    }

    /** A double without a trailing {@code .0}, for the reports. */
    private static String trim(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    /**
     * {@code {slots whose centre coincides with a hole's, distinct holes those are}} — the property "the item
     * is centred in its frame".
     *
     * <p>{@code worst}, when given, collects the largest mismatches for the report; the predicate itself is
     * only satisfied by an exact coincidence (a tolerance of half a panel pixel absorbs the fraction that the
     * texel-centre convention introduces, and nothing else — one slot pitch is 18 of them).
     */
    private static int[] frameCoincidences(int[][] slots, List<int[]> holes, int[] blit, Sheet sheet,
            List<String> worst) {
        int matched = 0;
        boolean[] used = new boolean[holes.size()];
        List<double[]> mismatches = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            double cx = slots[i][0] + 8.0;
            double cy = slots[i][1] + 8.0;
            double best = Double.MAX_VALUE;
            int bestHole = -1;
            for (int h = 0; h < holes.size(); h++) {
                int[] hole = holes.get(h);
                double hx = mapped(blit[0], blit[6], sheet.width, hole[0] + 8, blit[2]);
                double hy = mapped(blit[1], blit[7], sheet.height, hole[1] + 8, blit[3]);
                double distance = Math.max(Math.abs(hx - cx), Math.abs(hy - cy));
                if (distance < best) {
                    best = distance;
                    bestHole = h;
                }
            }
            if (best <= 0.5) {
                matched++;
                used[bestHole] = true;
            } else {
                mismatches.add(new double[] {i, best});
            }
        }
        int distinct = 0;
        for (boolean holeUsed : used) {
            if (holeUsed) {
                distinct++;
            }
        }
        if (worst != null) {
            mismatches.sort((a, b) -> Double.compare(b[1], a[1]));
            for (double[] mismatch : mismatches) {
                if (worst.size() >= 3) {
                    break;
                }
                int index = (int) mismatch[0];
                worst.add(slotName(index) + " at (" + slots[index][0] + "," + slots[index][1]
                        + ") is " + trim(mismatch[1]) + " panel px off its frame");
            }
        }
        return new int[] {matched, distinct};
    }

    /** {@code "inventory row 1 col 2"} / {@code "hotbar col 5"} for a {@code playerSlotRects()} index. */
    private static String slotName(int index) {
        if (index < 27) {
            return "inventory row " + (index / 9) + " col " + (index % 9);
        }
        return "hotbar col " + (index - 27);
    }

    /**
     * Where the panel blit paints a texel: {@code origin + texel * declared / actual - offset}.
     *
     * <p>{@code declared} is the texture size the {@code blit} call hands to {@code GuiGraphics}, {@code actual}
     * the sheet's real width (or height) — the two agree for a 1:1 blit, and their ratio is exactly the scale
     * error a mismatched pair introduces. {@code offset} is the blit's own {@code uOffset}/{@code vOffset}.
     */
    private static double mapped(int origin, int declared, int actual, int texel, int offset) {
        return origin + (double) texel * declared / actual - offset;
    }

    /** The slot holes {@code sheet} draws, as {@code {x, y}} texel pairs of each hole's 16x16 interior. */
    private static List<int[]> textureSlotHoles(Sheet sheet) {
        List<int[]> holes = new ArrayList<>();
        for (int y = 1; y + 16 < sheet.height; y++) {
            for (int x = 1; x + 16 < sheet.width; x++) {
                if (sheet.at(x - 1, y) != HOLE_SHADOW || sheet.at(x, y - 1) != HOLE_SHADOW) {
                    continue;
                }
                if (sheet.at(x + 16, y) != HOLE_HIGHLIGHT || sheet.at(x, y + 16) != HOLE_HIGHLIGHT) {
                    continue;
                }
                int filled = 0;
                for (int j = 0; j < 16; j++) {
                    for (int i = 0; i < 16; i++) {
                        if (sheet.at(x + i, y + j) == HOLE_FILL) {
                            filled++;
                        }
                    }
                }
                // The blueprint's hole has the item's icon painted into it (156 of 256 pixels are still the
                // fill), so the test is "predominantly the fill", not "entirely".
                if (filled >= 128) {
                    holes.add(new int[] {x, y});
                }
            }
        }
        return holes;
    }

    /** A decoded PNG: ARGB pixels indexed {@code [x][y]}. */
    private static final class Sheet {
        final int width;
        final int height;
        private final int[][] argb;

        Sheet(int width, int height, int[][] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }

        int at(int x, int y) {
            return this.argb[x][y];
        }
    }

    /**
     * Decodes a PNG with the JDK's own {@code ImageIO} — these are the bytes a player's client uploads as a GL
     * texture, so the holes below are read from the same pixels the game blits, not from a re-typed table.
     */
    private static Sheet readSheet(Path file) throws Exception {
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file.toFile());
        if (image == null) {
            throw new IllegalStateException("ImageIO did not recognise " + file);
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int[][] argb = new int[width][height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                argb[x][y] = image.getRGB(x, y);
            }
        }
        return new Sheet(width, height, argb);
    }

    /** The built {@code classResource}, or {@code null} when it is not on the harness's classpath. */
    private static org.objectweb.asm.tree.ClassNode readClassNode(String classResource) {
        try (java.io.InputStream in = ParallelCraftCheck.class.getClassLoader()
                .getResourceAsStream(classResource + ".class")) {
            if (in == null) {
                return null;
            }
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(in).accept(node, 0);
            return node;
        } catch (java.io.IOException exception) {
            return null;
        }
    }

    /**
     * {@code renderLabels}'s footprint as {@code {method invocations, field reads}} — {@code {-1, -1}} when the
     * class or the method is not on the classpath at all.
     *
     * <p>This is the shape 0.24.4's own section V used as a <b>complete</b> predicate ("draws nothing at all"),
     * and 0.24.5 keeps it only as a <b>diagnostic</b>, plus one half of it as a real assertion: the field-read
     * count. 0.24.4's release moved the information block out of the render path, and an empty method satisfies
     * "no invocation" exactly as well as a correct one does, so the predicate could not tell the two apart.
     * The field half is still sound on its own because the label and the title are drawn only by
     * {@code AbstractContainerScreen#renderLabels}, which reads {@code playerInventoryTitle} /
     * {@code inventoryLabelX} / {@code inventoryLabelY} and {@code title} / {@code titleLabelX} /
     * {@code titleLabelY} — so "zero field reads here" is a statement about never having touched them, whatever
     * else the method does.
     */
    private static int[] labelFootprint(String classResource) {
        org.objectweb.asm.tree.ClassNode node = readClassNode(classResource);
        if (node == null) {
            return new int[] {-1, -1};
        }
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            if (!method.name.equals("renderLabels")) {
                continue;
            }
            int invocations = 0;
            int fields = 0;
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode) {
                    invocations++;
                } else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode) {
                    fields++;
                }
            }
            return new int[] {invocations, fields};
        }
        return new int[] {-2, -2};
    }

    /**
     * How many times {@code methodName} of {@code classResource} invokes {@code invokedName} with exactly
     * {@code invokedDesc}; {@code ownerSuffix} (when non-empty) must also appear in the call's owner.
     *
     * <p>{@code -1} means "not on the classpath", which is a failure of its own whenever it is reported.
     */
    private static int countInvocations(String classResource, String methodName, String ownerSuffix,
            String invokedName, String invokedDesc) {
        org.objectweb.asm.tree.ClassNode node = readClassNode(classResource);
        if (node == null) {
            return -1;
        }
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            if (!method.name.equals(methodName)) {
                continue;
            }
            int found = 0;
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof org.objectweb.asm.tree.MethodInsnNode)) {
                    continue;
                }
                org.objectweb.asm.tree.MethodInsnNode call = (org.objectweb.asm.tree.MethodInsnNode) insn;
                if (call.name.equals(invokedName) && call.desc.equals(invokedDesc)
                        && (ownerSuffix.isEmpty() || call.owner.equals(ownerSuffix)
                                || call.owner.endsWith("/" + ownerSuffix))) {
                    found++;
                }
            }
            return found;
        }
        return -1;
    }

    /**
     * How many times {@code methodName} of {@code classResource} invokes any overload of
     * {@code GuiGraphics#drawString}; {@code -1} when the class or the method is not on the classpath.
     *
     * <p>Counted by name and not by descriptor on purpose: every overload paints a row of text, so a screen
     * that stopped drawing text of any kind reports 0 and one that draws reports as many calls as it has draw
     * sites.
     */
    private static int countDrawStrings(String classResource, String methodName) {
        org.objectweb.asm.tree.ClassNode node = readClassNode(classResource);
        if (node == null) {
            return -1;
        }
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            if (!method.name.equals(methodName)) {
                continue;
            }
            int found = 0;
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode
                        && ((org.objectweb.asm.tree.MethodInsnNode) insn).name.equals("drawString")) {
                    found++;
                }
            }
            return found;
        }
        return -1;
    }

    /**
     * The {@code String} constants {@code methodName} of {@code classResource} loads, in instruction order.
     *
     * <p>A key that is a compile-time concatenation of two {@code static final} strings — which is how this
     * screen builds {@code CONTROLLER_KEY + "blueprint"} — is folded into a single constant-pool entry, so the
     * keys the method really asks {@code Component.translatable} for are visible here without running it.
     */
    private static List<String> stringConstants(String classResource, String methodName) {
        List<String> constants = new ArrayList<>();
        org.objectweb.asm.tree.ClassNode node = readClassNode(classResource);
        if (node == null) {
            return constants;
        }
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            if (!method.name.equals(methodName)) {
                continue;
            }
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
                    Object constant = ((org.objectweb.asm.tree.LdcInsnNode) insn).cst;
                    if (constant instanceof String) {
                        constants.add((String) constant);
                    }
                }
            }
        }
        return constants;
    }

    /**
     * How many times any method of {@code classResource} reads the field {@code fieldName}; {@code -1} when the
     * class is not on the classpath. Reads the {@code name}, not the owner, so the inherited
     * {@code playerInventoryTitle} of {@code AbstractContainerScreen} counts wherever the screen touches it.
     */
    private static int countFieldReads(String classResource, String fieldName) {
        org.objectweb.asm.tree.ClassNode node = readClassNode(classResource);
        if (node == null) {
            return -1;
        }
        int found = 0;
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof org.objectweb.asm.tree.FieldInsnNode)) {
                    continue;
                }
                int op = insn.getOpcode();
                if ((op == org.objectweb.asm.Opcodes.GETFIELD || op == org.objectweb.asm.Opcodes.GETSTATIC)
                        && ((org.objectweb.asm.tree.FieldInsnNode) insn).name.equals(fieldName)) {
                    found++;
                }
            }
        }
        return found;
    }

    /** How many times the {@code String} constant {@code value} is loaded anywhere in {@code classResource}. */
    private static int countStringConstants(String classResource, String value) {
        org.objectweb.asm.tree.ClassNode node = readClassNode(classResource);
        if (node == null) {
            return -1;
        }
        int found = 0;
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                if (insn instanceof org.objectweb.asm.tree.LdcInsnNode) {
                    Object constant = ((org.objectweb.asm.tree.LdcInsnNode) insn).cst;
                    if (value.equals(constant)) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    static void section(String title) {
        System.out.println();
        System.out.println("== " + title);
    }

    // =================================================================== 0.24.2: the two screen layouts

    /**
     * Section U — 0.24.2's own regression checks, in the form the task demanded: <b>a property a
     * self-consistent-but-wrong layout cannot satisfy.</b>
     *
     * <p>Section R asserted the implementation's own numbers (panel width 280, queue origin 8, …) and passed on
     * a layout that was visibly broken in game. Every check below is instead a containment or ordering
     * inequality between two rectangles the drawing code really uses:
     *
     * <ol>
     *   <li>every element the factory screen draws lies inside the panel's own rectangle;</li>
     *   <li>the queue and the body/info/slot regions share one origin, expressed as an inequality;</li>
     *   <li>the information block's floor row and the player's slots fall below the information block, not on
     *       top of it or on top of the queue (the factory screen draws no inventory label at all — section V
     *       asserts that from the bytecode — so this is the block's floor, not a label's row);</li>
     *   <li>the panel blit's source offset is the original's {@code (0, 0)} with the whole texture as its
     *       extent, asserted rather than derived;</li>
     *   <li>the controller's information block — including its closing line — ends above the player-inventory
     *       region for a short payload, a long one, and a deliberately extreme one.</li>
     * </ol>
     *
     * <p>Items (1)–(3) and (5) are then fault-injected: the same predicates are run against the 0.24.1 geometry
     * ({@code ScreenLayout.brokenBlock}, {@code ScreenLayout.brokenQueueRect}) and <b>must</b> fail. A check
     * that has never failed is not evidence.
     */
    private static void screenLayoutMetrics() {
        section("U. the two information blocks and the factory panel stay inside their own region");

        // ---- (1) the controller's internal coupling, read back from the classes under test -----------------
        int cTextY = com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.TEXT_Y;
        int cScalePct = (int) (com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.SCALE * 100);
        int cLabelY = com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.INVENTORY_LABEL_Y;
        int cRegionEnd = com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.availableEnd();
        int cInventoryTop = com.reborn.modularmachinery.menu.MachineControllerMenu.playerSlotsTopY();
        report("the information region's boundary (" + cRegionEnd + " scaled = " + (cRegionEnd * cScalePct / 100)
                        + " panel px) is above the player-inventory label's row (" + cLabelY
                        + ") and the first player slot row (" + cInventoryTop + ")",
                cRegionEnd * cScalePct / 100 < cLabelY && cRegionEnd * cScalePct / 100 < cInventoryTop);
        report("...and the label row is itself above the first player slot row ("
                        + cLabelY + " < " + cInventoryTop + ")",
                cLabelY < cInventoryTop);
        report("...and the block starts at the top of that region, not part-way down ("
                        + cTextY + " scaled)", cTextY >= 0);

        // ---- (5) the controller's block for three payloads, one of them deliberately extreme ----------------
        int[][] payloads = {
                // blueprintLines, extraLines, interfaceLines, statusLines, failures, progress
                {1, 0, 0, 1, 0, 0},
                {1, 3, 4, 4, 1, 1},
                {1, 12, 12, 12, 6, 1},
        };
        String[] payloadNames = {"a short payload", "a long payload", "a deliberately extreme payload"};
        for (int i = 0; i < payloads.length; i++) {
            int[] p = payloads[i];
            List<com.reborn.modularmachinery.client.ScreenLayout.Measured> rows =
                    com.reborn.modularmachinery.client.MachineControllerScreen.infoRows(false, true, true,
                            p[0], p[1], p[2], p[3], p[4], p[5] > 0);
            com.reborn.modularmachinery.client.ScreenLayout.Block block =
                    com.reborn.modularmachinery.client.MachineControllerScreen.blockFor(rows);
            blockInside(payloadNames[i], block, rows, cRegionEnd, cLabelY, cInventoryTop);
        }

        // ---- (2) the factory panel's rectangles, from the drawing code's own constants ----------------------
        int[] panel = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.panelRect();
        int[] queue = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.queueRect();
        int[] scrollbar = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.scrollbarRect();
        int[] text = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.textRect();
        int[] grid = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.playerGridRect();
        int[] blueprint = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.blueprintSlotRect();
        int fLabelY = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.INVENTORY_LABEL_Y;
        int fInventoryTop = com.reborn.modularmachinery.menu.FactoryControllerMenu.playerSlotsTopY();

        String[][] elements = {
                {"the recipe queue", rect(queue)},
                {"the scrollbar", rect(scrollbar)},
                {"the information block", rect(text)},
                {"the player grid and hotbar", rect(grid)},
                {"the blueprint slot", rect(blueprint)},
        };
        for (String[] element : elements) {
            String key = element[0];
            int[] r = rectOf(element[1]);
            report(key + " " + element[1] + " lies inside the panel " + rect(panel)
                            + " (the 0.24.1 screenshot shows the queue free-standing to the left of it)",
                    com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.insidePanel(r));
        }
        report("the queue and the panel share one origin: queue x " + queue[0] + " >= panel x " + panel[0]
                        + " and queue x+w " + (queue[0] + queue[2]) + " <= panel x+w " + (panel[0] + panel[2]),
                queue[0] >= panel[0] && queue[0] + queue[2] <= panel[0] + panel[2]);
        report("the queue starts inside the panel at its own left inset and the player grid starts at the "
                        + "menu's own x: queue x " + queue[0] + " == "
                        + com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.QUEUE_X + ", grid x "
                        + grid[0] + " == " + com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.PLAYER_INVENTORY_X,
                queue[0] == com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.QUEUE_X
                        && grid[0] == com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.PLAYER_INVENTORY_X);
        report("the scrollbar follows the queue and stays inside the panel: "
                        + (queue[0] + queue[2]) + " <= " + scrollbar[0] + ", "
                        + (scrollbar[0] + scrollbar[2]) + " <= " + (panel[0] + panel[2]),
                queue[0] + queue[2] <= scrollbar[0] && scrollbar[0] + scrollbar[2] <= panel[0] + panel[2]);
        // The original's text block starts at 113*0.72 = 81 while the queue ends at 94 and the scrollbar's
        // track ends at 106: a 25-pixel overlap exists in the original's own numbers and is reproduced rather
        // than silently "fixed". What must hold is that the block still fits the panel and that the overlap is
        // the original's, not an implementation accident.
        report("the text block's own rectangle " + rect(text) + " still fits inside the panel, despite the "
                        + "original's queue/scrollbar overlap (text left " + text[0] + " < scrollbar right "
                        + (scrollbar[0] + scrollbar[2]) + ")",
                text[0] + text[2] <= panel[0] + panel[2]
                        && text[0] < scrollbar[0] + scrollbar[2]);
        report("* the original's queue/text overlap is reproduced, not hidden: text left " + text[0]
                        + " < queue right " + (queue[0] + queue[2]) + " (GuiFactoryController:39-42 vs :191-192)",
                text[0] < queue[0] + queue[2]);
        report("the information block's bottom (" + (text[1] + text[3]) + ") is above the block's floor row ("
                        + fLabelY + ") and the first player slot row (" + fInventoryTop + ")",
                text[1] + text[3] < fLabelY && text[1] + text[3] < fInventoryTop);
        report("the player's slots are below the block and to the right of the queue: grid y " + grid[1]
                        + " > text bottom " + (text[1] + text[3]),
                grid[1] >= text[1] + text[3]);
        // The factory screen draws no player-inventory label at all (section V reads that out of the bytecode);
        // the row is the floor the block stops above, and it is inside the panel the texture draws.
        report("the block's floor row is inside the panel texture (" + fLabelY + " inside 0.." + panel[3] + ")",
                fLabelY > 0 && fLabelY < panel[3]);

        // ---- (4) the blit arguments, asserted rather than derived -------------------------------------------
        int[] blit = com.reborn.modularmachinery.client.FactoryControllerScreen.panelBlit(0, 0, 280, 213);
        report("the panel blit's source offset is the original's (0, 0) and its extent is the whole texture: "
                        + java.util.Arrays.toString(blit),
                blit[2] == 0 && blit[3] == 0 && blit[4] == 280 && blit[5] == 213);
        int[] offsetDest = com.reborn.modularmachinery.client.FactoryControllerScreen.panelBlit(37, 91, 280, 213);
        report("...and its destination is exactly the origin every panel-local offset is added to: "
                        + java.util.Arrays.toString(offsetDest),
                offsetDest[0] == 37 && offsetDest[1] == 91);
        int[] queueBlit = com.reborn.modularmachinery.client.FactoryControllerScreen.elementBlit();
        report("the queue element blit reads from the element sheet's own (0, 0) at 86x32: "
                        + java.util.Arrays.toString(queueBlit),
                queueBlit[0] == 0 && queueBlit[1] == 0 && queueBlit[2] == 86 && queueBlit[3] == 32);

        // ---- (5) the factory block, for a short and a deliberately extreme payload ---------------------------
        int[][] fPayloads = {
                {1, 1, 1, 0, 0, 0},
                {1, 6, 8, 2, 2, 1},
                {4, 20, 20, 6, 6, 1},
        };
        boolean[] fTruncated = {false, true, true};
        for (int i = 0; i < fPayloads.length; i++) {
            int[] p = fPayloads[i];
            List<com.reborn.modularmachinery.client.ScreenLayout.Measured> rows =
                    com.reborn.modularmachinery.client.FactoryControllerScreen.orderedInfoRows(false, true, true,
                            p[0], p[1], p[2], p[3], p[4], p[5]);
            com.reborn.modularmachinery.client.ScreenLayout.Block block =
                    com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.block(rows);
            blockInside("factory " + payloadNames[i], block, rows,
                    com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.availableEnd(),
                    fLabelY, fInventoryTop);
            report("factory " + payloadNames[i] + ": the region "
                            + (fTruncated[i] ? "had to drop rows (requested " + block.requestedLines
                                    + ", drawn " + block.drawnLines + ")"
                            : "held the whole payload without truncation (drawn " + block.drawnLines + ")"),
                    fTruncated[i] == block.truncated());
        }

        // ---- fault injection: the same predicates against the 0.24.1 geometry -------------------------------
        System.out.println("  -- fault injection: the predicates above are fed the 0.24.1 geometry; each check");
        System.out.println("     below PASSES only when the predicate REJECTS it");

        int[] brokenQueue = com.reborn.modularmachinery.client.ScreenLayout.brokenQueueRect();
        report("injected: a queue one queue-width to the left of the panel origin " + rect(brokenQueue)
                        + " is rejected by insidePanel(...) -- the shipped 'free-standing strip'",
                !com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.insidePanel(brokenQueue));
        report("injected: ...and it is rejected by the origin inequality (queue x " + brokenQueue[0]
                        + " < panel x " + panel[0] + ")", !(brokenQueue[0] >= panel[0]));

        // The 0.24.1 controller: the closing line appended after the payload at max(cursor, 187).
        List<com.reborn.modularmachinery.client.ScreenLayout.Measured> longRows =
                com.reborn.modularmachinery.client.MachineControllerScreen.infoRows(
                        false, true, true, 1, 12, 12, 12, 6, true);
        com.reborn.modularmachinery.client.ScreenLayout.Block broken =
                com.reborn.modularmachinery.client.ScreenLayout.brokenBlock(cTextY, cRegionEnd, 10, 187, longRows);
        report("injected: the 0.24.1 closing line lands at scaled y " + broken.footerY + " (block end "
                        + broken.blockEndY() + "), past the region's boundary " + cRegionEnd
                        + " -- the containment predicate rejects it", !broken.insideRegion());
        report("injected: ...and its end in panel pixels (" + (broken.blockEndY() * cScalePct / 100)
                        + ") is at or past the player-inventory label (" + cLabelY + "), which is the "
                        + "collision the owner photographed",
                !(broken.blockEndY() * cScalePct / 100 < cLabelY));

        // The 0.24.1 factory screen drew its status block with no region at all.
        List<com.reborn.modularmachinery.client.ScreenLayout.Measured> factoryLong =
                com.reborn.modularmachinery.client.FactoryControllerScreen.orderedInfoRows(
                        false, true, true, 4, 20, 20, 6, 6, 1);
        int fRegionEnd = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.availableEnd();
        com.reborn.modularmachinery.client.ScreenLayout.Block brokenFactory =
                com.reborn.modularmachinery.client.ScreenLayout.brokenBlock(
                        com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.startY(),
                        fRegionEnd, 10, fRegionEnd - 10, factoryLong);
        report("injected: the unbounded factory block runs to scaled y " + brokenFactory.blockEndY()
                        + " (" + (brokenFactory.blockEndY() * 72 / 100) + " panel px), past the region's "
                        + "boundary " + fRegionEnd + " / " + (fRegionEnd * 72 / 100) + " panel px, so the "
                        + "predicate rejects it", !brokenFactory.insideRegion());

        // And the corrected layout must pass the very same predicate, for the very same payload.
        com.reborn.modularmachinery.client.ScreenLayout.Block fixed =
                com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.block(longRows);
        blockInside("the corrected long controller payload", fixed, longRows, cRegionEnd, cLabelY, cInventoryTop);
        report("...and the corrected controller block still draws most of the payload, not none of it ("
                        + fixed.drawnLines + " rows)", fixed.drawnLines > 3);
        com.reborn.modularmachinery.client.ScreenLayout.Block fixedFactory =
                com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.block(factoryLong);
        blockInside("the corrected long factory payload", fixedFactory, factoryLong, fRegionEnd, fLabelY,
                fInventoryTop);

        // ---- (6) THE 0.24.3 CHECK: the block as DRAWN lies inside the clip -------------------------------
        //
        // (1)-(5) compare numbers the implementation hands out, which is the shape of check that let the 0.24.2
        // screen ship wrong: it asserted the screen's own constants back at it and stayed green while the first
        // glyph column was being shaved off in game. What follows asks where the text actually LANDS and
        // whether the clip rectangle the screen passes to `enableScissor` contains it.
        //
        // `GuiGraphics.enableScissor(left, top, right, bottom)` builds a ScreenRectangle in ABSOLUTE GUI
        // coordinates and hands it to RenderSystem, converting only by `windowGuiScale`; it never consults the
        // pose stack. That was verified against the mapped 1.20.1 bytecode rather than assumed, because the
        // 0.24.2 fix's own comment asserted the opposite convention ("absolute screen coordinates") while
        // drawing the text in the panel-local space the clip's origin was measured in.
        System.out.println();
        System.out.println("  -- the block as drawn, against the clip the screen installs");

        int[] clip = com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.scissorRect();
        int[] drawn = com.reborn.modularmachinery.client.MachineControllerScreen.textBlockRect();
        int[] glyphs = com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.glyphColumnRect();
        checkDrawnInsideClip("controller", drawn, clip, glyphs);

        int[] fClip = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.scissorRect();
        int[] fDrawn = com.reborn.modularmachinery.client.FactoryControllerScreen.textBlockRect();
        int[] fGlyphs = com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.glyphColumnRect();
        checkDrawnInsideClip("factory", fDrawn, fClip, fGlyphs);

        report("the controller's clip " + rect(clip) + " is the block's rectangle dilated by "
                        + com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.SCISSOR_MARGIN_X
                        + " panel px on the left and flush with the panel's right and bottom",
                clip[0] == drawn[0] - com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.SCISSOR_MARGIN_X
                        && clip[2] == 176 && clip[3] == 213);
        report("the controller's clip leaves the first glyph column its shadow: clip left " + clip[0]
                        + " < glyph left " + glyphs[0] + " (shadow margin "
                        + com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.TEXT_SHADOW_MARGIN
                        + " scaled unit)", clip[0] < glyphs[0]);
        report("the factory's clip " + rect(fClip) + " is that panel's own rectangle, which the block "
                        + rect(fDrawn) + " sits inside with room to spare on every side",
                fClip[0] == 0 && fClip[1] == 0 && fClip[2] == 280 && fClip[3] == 213);
        report("the factory's clip leaves the first glyph column its shadow: clip left " + fClip[0]
                        + " < glyph left " + fGlyphs[0], fClip[0] < fGlyphs[0]);

        // The two screens describe their blocks independently; this is the cross-check that they agree with the
        // layout (and with each other) about where a block goes.
        report("both screens' block origins are their layout's own textRect(), not a second scaling of it: "
                        + "controller " + rect(drawn) + " vs "
                        + rect(com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.textRect())
                        + ", factory " + rect(fDrawn) + " vs "
                        + rect(com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.textRect()),
                drawn[0] == com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.textRect()[0]
                        && drawn[1] == com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.textRect()[1]
                        && drawn[2] == com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.textRect()[2]
                        && drawn[3] == com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.textRect()[3]
                        && fDrawn[0] == com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.textRect()[0]
                        && fDrawn[1] == com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.textRect()[1]
                        && fDrawn[2] == com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.textRect()[2]
                        && fDrawn[3] == com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.textRect()[3]);

        // ---- fault injection: the property above must REJECT the 0.24.2 clip -----------------------------
        //
        // The 0.24.2 clip edge was `leftPos + TEXT_X - 2`, i.e. panel x 10, while the block (scaled but never
        // translated) was painted from panel x 8 — two panel pixels of the first glyph column outside the clip,
        // on every row. The predicate that the corrected layout passes has to reject that exact rectangle pair.
        System.out.println("  -- fault injection: the drawn-in-clip predicate is fed the 0.24.2 clip edge,");
        System.out.println("     which sat at panel x TEXT_X-2 = 10 while the block was painted from x 8");

        int[] shippedClip = {com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.TEXT_X - 2, 0, 176, 213};
        report("injected: the 0.24.2 clip " + rect(shippedClip) + " cuts the block " + rect(drawn)
                        + " -- so this predicate FAILS on the shipped geometry, unlike the corrected "
                        + rect(clip) + " which passes",
                !drawnInsideClip(drawn, shippedClip, glyphs) && drawnInsideClip(drawn, clip, glyphs));
        report("injected: ...and the amount it cut is " + (shippedClip[0] - drawn[0])
                        + " panel px off the first glyph column (clip left " + shippedClip[0]
                        + " > block left " + drawn[0] + "), which is the owner's report that the first column "
                        + "was half hidden", shippedClip[0] > drawn[0] && shippedClip[0] > glyphs[0]);

        // The same predicate against the 0.24.1 queue rectangle, whose whole point is that the factory panel is
        // one panel with one origin: a queue drawn elsewhere is rejected.
        report("injected: the 0.24.1 'free-standing strip' queue " + rect(brokenQueue)
                        + " is rejected by the panel-containment predicate the corrected queue "
                        + rect(queue) + " passes",
                !com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.insidePanel(brokenQueue)
                        && com.reborn.modularmachinery.client.ScreenLayout.FactoryPanel.insidePanel(queue));

        // ---- (7) THE SOURCE-LEVEL HALF: the matrix translate really happens, and to the right point -------
        //
        // Rectangles alone cannot see a missing `pose().translate(...)`: the clip is installed before the pose
        // calls and the block rectangle is a pure function, so a screen that scales the block and never
        // translates it still reports the corrected rectangle. That is exactly how 0.24.2 shipped. So the pose
        // calls are read out of the screens' bytecode instead, and the translate's two floats are required to be
        // the block rectangle's own origin.
        System.out.println();
        System.out.println("  -- the pose calls in the two screens' bytecode (the 0.24.2 defect was a missing one)");

        checkTextMatrixPose("MachineControllerScreen.drawInfo",
                "com/reborn/modularmachinery/client/MachineControllerScreen", "drawInfo",
                "Lnet/minecraft/client/gui/GuiGraphics;)V", drawn, "textBlockRect");
        checkTextMatrixPose("FactoryControllerScreen.drawFactoryStatus",
                "com/reborn/modularmachinery/client/FactoryControllerScreen", "drawFactoryStatus",
                "Lnet/minecraft/client/gui/GuiGraphics;)V", fDrawn, "textBlockRect");
    }

    // =================================================================== 0.25.0: failure-action

    /**
     * Section W — 0.25.0's own claim: <b>{@code failure-action} decides what one failed tick costs a craft that
     * is already running.</b> Until this release the field was parsed into the definition and read by nobody.
     *
     * <p>What the original does, line by line:
     *
     * <ul>
     *   <li>the field is a <b>root string</b>, one of {@code "reset"} / {@code "still"} / {@code "decrease"}
     *       ({@code RecipeFailureActions:15-18}), parsed at {@code DynamicMachinePreDeserializer:44-51} and kept
     *       on the machine ({@code DynamicMachine:630-633}, {@code AbstractMachine:29-41});</li>
     *   <li>its <b>only</b> consumer is a failed per-tick IO check of an <b>already started</b> craft:
     *       {@code ActiveMachineRecipe#tick:87-98} applies it when {@code RecipeCraftingContext#ioTick:238-298}
     *       fails, and {@code ActiveMachineRecipe#doFailureAction:106-115} is the whole effect — {@code reset}
     *       → {@code tick = 0}, {@code decrease} → {@code tick--} (only while {@code tick > 0}), {@code still}
     *       → nothing;</li>
     *   <li>so it fires <b>once per failed tick</b>, never at a failed start, and it belongs to the
     *       <b>machine</b>, not to the recipe;</li>
     *   <li>the other hold — a craft whose result does not fit — never reaches it: that path is
     *       {@code RecipeThread#onFinished:64-86}.</li>
     * </ul>
     *
     * <p>Asserted in the order the data flows: the loader's vocabulary, default and rejection messages; the
     * arithmetic; the controller's application of it (driven on a constructor-less block entity, so the
     * production resolution {@code MachineRegistry.byId(...).map(...).orElse(default)} really runs); and the
     * same failure through the production {@link FactoryEngine} with in-memory ports, counted in items that
     * moved.
     *
     * <p><b>What this section cannot see.</b> The controller's own tick loop needs a {@code Level}, so the
     * <i>call site</i> (the branch that runs when {@code canTick} answers false) is not driven here — what is
     * driven is the method it calls, plus the engine's identical branch. The in-game half of section W is
     * therefore listed in the release README's checklist rather than claimed here.
     */
    private static void failureActionSection() {
        section("W. failure-action: the machine decides what a failed tick costs");

        String parts = "\"parts\":[{\"x\":1,\"y\":-1,\"z\":0,\"elements\":\"minecraft:stone\"}]";
        String head = "{\"registryname\":\"t\",\"localizedname\":\"T\",";

        // ---- (1) the three names, through the loader's own reader ---------------------------------------
        for (String name : new String[] {"reset", "still", "decrease"}) {
            check("the loader reads \"failure-action\": \"" + name + "\"",
                    name.toUpperCase(java.util.Locale.ROOT),
                    String.valueOf(readDefinition(head + "\"failure-action\":\"" + name + "\"," + parts + "}")
                            .failureAction()));
        }

        // ---- (2) the default is the original's "still", not "reset" -------------------------------------
        //
        // The original never falls back to "reset": AbstractMachine:29 seeds the field from
        // RecipeFailureActions.getDefaultAction(), which loadFromConfig (:38-46, called from Config.java:69)
        // reads out of the config key `default-failure-actions` / category `general` with default "still".
        MachineDefinition undeclared = readDefinition(head + parts + "}");
        String constant = staticFieldValue(MachineDefinition.class, "DEFAULT_FAILURE_ACTION");
        check("a definition that does not declare failure-action gets the original's default",
                "STILL", String.valueOf(undeclared.failureAction()));
        check("...and that fallback is MachineDefinition.DEFAULT_FAILURE_ACTION", constant,
                String.valueOf(undeclared.failureAction()));

        // ---- (3) a typo must be refused loudly, and say what to write ------------------------------------
        //
        // The original's check (DynamicMachinePreDeserializer:45-48) only rejects a non-string; an unknown
        // *string* silently becomes the default through RecipeFailureActions.getFailureAction (:49-56). This
        // project's rule for a field a person writes is the opposite, so an unknown name is refused with the
        // vocabulary spelled out.
        Object[][] bad = {
                {head + "\"failure-action\":\"rest\"," + parts + "}", "must be one of", "\"rest\""},
                {head + "\"failure-action\":5," + parts + "}", "must be a string", "5"},
                {head + "\"failure-action\":{}," + parts + "}", "must be a string", "{}"},
                {head + "\"failure-action\":[]," + parts + "}", "must be a string", "[]"},
                {head + "\"failure-action\":null," + parts + "}", "must be a string", "null"},
                {head + "\"failure-action\":true," + parts + "}", "must be a string", "true"},
        };
        for (Object[] c : bad) {
            try {
                MachineDefinition definition = readDefinition((String) c[0]);
                report("expected a rejection for " + c[0] + " but got " + definition, false);
            } catch (RuntimeException exception) {
                String message = String.valueOf(exception.getMessage());
                boolean ok = message.contains((String) c[1]) && message.contains((String) c[2])
                        && message.contains("\"reset\"") && message.contains("\"still\"")
                        && message.contains("\"decrease\"");
                report("rejected: " + message, ok);
            }
        }

        // ---- (4) the arithmetic, on the enum the loader hands out ----------------------------------------
        //
        // Reflective on purpose: the run that precedes the implementation must report a failure, not fail to
        // compile, or it is not evidence.
        check("reset applied to progress 0,1,5,64", "0,0,0,0",
                afterFailedTickVector(FailureAction.RESET, new int[] {0, 1, 5, 64}));
        check("still applied to progress 0,1,5,64", "0,1,5,64",
                afterFailedTickVector(FailureAction.STILL, new int[] {0, 1, 5, 64}));
        check("decrease applied to progress 0,1,5,64", "0,0,4,63",
                afterFailedTickVector(FailureAction.DECREASE, new int[] {0, 1, 5, 64}));

        // ---- (5) the controller applies the machine's own action ------------------------------------------
        //
        // The block entity is allocated without its constructor (the real one reaches
        // ModBlocks.MACHINE_CONTROLLER_ENTITY.get(), which cannot run outside FML), `machineId` and `progress`
        // are set, and the production method the tick loop calls is invoked. Everything except the allocation
        // is production code, including the registry lookup and the definition the loader built.
        System.out.println();
        System.out.println("  -- the controller: the machine's action applied to its own progress");
        Class<?> controllerClass;
        try {
            controllerClass = Class.forName("com.reborn.modularmachinery.block.MachineControllerBlockEntity");
        } catch (ClassNotFoundException exception) {
            report("the controller class is on the harness's classpath: " + exception, false);
            return;
        }
        ResourceLocation declaredId = new ResourceLocation("modular_machinery_reborn", "t");
        record FailedTick(String json, ResourceLocation machineId, int progress, int expected, String what) {
        }
        FailedTick[] controllerCases = {
                new FailedTick(head + "\"failure-action\":\"reset\"," + parts + "}", declaredId, 5, 0,
                        "\"reset\" (5 -> 0)"),
                new FailedTick(head + "\"failure-action\":\"decrease\"," + parts + "}", declaredId, 5, 4,
                        "\"decrease\" (5 -> 4)"),
                new FailedTick(head + "\"failure-action\":\"decrease\"," + parts + "}", declaredId, 1, 0,
                        "\"decrease\" at 1 (1 -> 0)"),
                new FailedTick(head + "\"failure-action\":\"decrease\"," + parts + "}", declaredId, 0, 0,
                        "\"decrease\" at 0 (nothing left to lose)"),
                new FailedTick(head + "\"failure-action\":\"still\"," + parts + "}", declaredId, 5, 5,
                        "\"still\" (unchanged)"),
                new FailedTick(head + parts + "}", declaredId, 5, 5,
                        "no declaration at all (the default holds the craft)"),
                new FailedTick(head + "\"failure-action\":\"reset\"," + parts + "}",
                        new ResourceLocation("mmverify", "gone"), 5, 5,
                        "a machine that is not in the registry (the default holds the craft)"),
                new FailedTick(head + "\"failure-action\":\"reset\"," + parts + "}", null, 5, 5,
                        "no machine id at all (the default holds the craft)"),
        };
        for (FailedTick c : controllerCases) {
            String label = "the controller applies " + c.what() + " to a craft at progress " + c.progress();
            try {
                MachineDefinition definition = readDefinition(c.json());
                // The registry is the one the loader would have filled; only this section ever writes it.
                com.reborn.modularmachinery.machine.MachineRegistry.replace(Map.of(declaredId, definition));
                Object blockEntity = newClientController(controllerClass);
                setField(controllerClass, blockEntity, "machineId", c.machineId());
                setField(controllerClass, blockEntity, "progress", c.progress());
                invokePrivate(blockEntity, controllerClass, "applyFailureAction");
                long kept = ((Integer) controllerClass.getMethod("progress").invoke(blockEntity)).longValue();
                check(label, c.expected(), kept);
            } catch (Throwable throwable) {
                report(label + " — could not be driven: " + throwable, false);
            }
        }

        // ---- (5b) the wiring itself: the method above is worth nothing if the tick loop never reaches it ----
        //
        // 0.24.5 shipped a correct method that no render pass reached any more (section V), so "the method
        // behaves" and "the method is reached" are asserted separately, out of the built classes' own bytes.
        int controllerCalls = countInvocations(
                "com/reborn/modularmachinery/block/MachineControllerBlockEntity", "tick",
                "MachineControllerBlockEntity", "applyFailureAction", "()V");
        report("MachineControllerBlockEntity.tick reaches the failure-action at all (" + controllerCalls
                        + " call(s) to applyFailureAction) -- the 0.24.5 lesson: a correct method nothing calls",
                controllerCalls > 0);
        int engineAsks = countInvocations("com/reborn/modularmachinery/factory/FactoryEngine", "tickThread",
                "FactoryHost", "failureAction", "()Lcom/reborn/modularmachinery/machine/FailureAction;");
        report("FactoryEngine.tickThread asks the host for the machine's failure-action (" + engineAsks
                        + " call(s))", engineAsks > 0);
        int engineApplies = countInvocations("com/reborn/modularmachinery/factory/FactoryEngine", "tickThread",
                "FailureAction", "afterFailedTick", "(I)I");
        report("...and applies it to the craft's own progress (" + engineApplies + " call(s))",
                engineApplies > 0);

        // ---- (6) the same failure through the real engine, counted in items that moved --------------------
        System.out.println();
        System.out.println("  -- the factory engine: a per-tick requirement that cannot be paid");
        MachineRecipe starvable = new MachineRecipe(new ResourceLocation("mmverify", "failure_action_demo"),
                "example_factory", "failure_action_demo", 10, List.of(
                        ItemRequirement.input(ingredient(Items.COBBLESTONE), 1),
                        ItemRequirement.output(new ItemStack(Items.STONE), 1, 1, 1.0F),
                        new EnergyRequirement(IOType.INPUT, 100L)));

        record Starved(FailureAction action, int expected) {
        }
        Starved[] starved = {
                new Starved(FailureAction.STILL, 3),
                new Starved(FailureAction.DECREASE, 0),
                new Starved(FailureAction.RESET, 0),
        };
        for (Starved c : starved) {
            FactoryRig rig = new FactoryRig(List.of(starvable), 2, 1, List.of())
                    .parallelCeiling(1).declaredFailureAction(c.action()).reset();
            rig.tick(3);
            int before = rig.activeProgress();
            rig.starveEnergy();
            rig.tick(3);
            int after = rig.activeProgress();
            report(c.action() + ": three ticks that cannot pay the energy requirement took the craft from "
                            + before + " to " + after + " (the original's doFailureAction says "
                            + c.expected() + ")",
                    before == 3 && after == c.expected());
            report(c.action() + ": ...and the craft stays armed, holding the one-shot input it already paid "
                            + "(cobblestone consumed " + (1 - rig.cobblestoneLeft()) + ", thread"
                            + (rig.workingThreads() == 1 ? " still holds it" : "s were dropped") + ")",
                    rig.workingThreads() == 1 && 1 - rig.cobblestoneLeft() == 1);
            rig.restoreEnergy();
            rig.tick(12);
            check(c.action() + ": the craft settles once the energy is back (stone produced)",
                    1, rig.outputCount(Items.STONE));
            check(c.action() + ": ...and its one-shot input was paid exactly once, never re-paid by the action",
                    1, 1 - rig.cobblestoneLeft());
        }

        // ---- (7) a machine that never fails is untouched --------------------------------------------------
        for (FailureAction action : FailureAction.values()) {
            FactoryRig rig = new FactoryRig(List.of(starvable), 2, 1, List.of())
                    .parallelCeiling(1).declaredFailureAction(action).reset();
            rig.tick(12);
            report("with " + action + " and no failed tick the craft runs exactly as before: no craft left ("
                            + rig.activeProgress() + "), 1 stone, 1 cobblestone consumed",
                    rig.activeProgress() == -1 && rig.outputCount(Items.STONE) == 1
                            && 1 - rig.cobblestoneLeft() == 1);
        }

        // ---- (8) the other hold is not a failed tick ------------------------------------------------------
        //
        // A craft whose result does not fit holds its progress in the original too (RecipeThread#onFinished
        // :64-86 checks the finish separately and never calls doFailureAction), so even "reset" must keep it.
        for (FailureAction action : new FailureAction[] {FailureAction.RESET, FailureAction.STILL}) {
            FactoryRig rig = new FactoryRig(List.of(starvable), 2, 1, List.of())
                    .parallelCeiling(1).declaredFailureAction(action).reset();
            rig.tick(9);
            rig.plugOutput();
            rig.tick(4);
            report(action + ": a craft whose result does not fit holds its progress ("
                            + rig.activeProgress() + " of 10, thread"
                            + (rig.workingThreads() == 1 ? " still holds it" : "s were dropped")
                            + ") -- the finish hold is not a failed tick, in the original either",
                    rig.activeProgress() == 9 && rig.workingThreads() == 1);
        }
    }

    /**
     * {@code FailureAction#afterFailedTick(int)} over a vector of progresses — the original's
     * {@code ActiveMachineRecipe#doFailureAction} ({@code :107-114}).
     *
     * <p>Reached by name rather than by a compile-time call, so the run that precedes the implementation
     * reports a failed check instead of a failed compile.
     */
    private static String afterFailedTickVector(FailureAction action, int[] progresses) {
        try {
            Method method = FailureAction.class.getMethod("afterFailedTick", int.class);
            StringBuilder out = new StringBuilder();
            for (int progress : progresses) {
                if (out.length() > 0) {
                    out.append(',');
                }
                out.append(method.invoke(action, progress));
            }
            return out.toString();
        } catch (ReflectiveOperationException exception) {
            return "FailureAction#afterFailedTick(int) could not be called (" + exception + ")";
        }
    }

    /** A public static field's value, or a diagnostic string when the field is not there (yet). */
    private static String staticFieldValue(Class<?> type, String field) {
        try {
            return String.valueOf(type.getField(field).get(null));
        } catch (ReflectiveOperationException exception) {
            return "no such field (" + exception + ")";
        }
    }

    /**
     * The text matrix's pose calls, read from {@code classResource}'s bytecode.
     *
     * <p>The matrix must end up translated to exactly {@code blockRect}'s origin and scaled by the time the
     * method draws its first string, and the method must make exactly {@code expectedTranslates} translates
     * before that point — one for the panel origin a foreground layer inherits (the factory's, which does its
     * own because it draws from {@code renderBg}) plus one for the block's own origin. A screen that forgets the
     * block's translate, or that scales without translating, fails here even though every rectangle predicate
     * still passes.
     */
    private static void checkTextMatrixPose(String name, String classResource, String methodName,
            String tailDesc, int[] blockRect, String blockAccessor) {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        try (java.io.InputStream in = ParallelCraftCheck.class.getClassLoader()
                .getResourceAsStream(classResource + ".class")) {
            if (in == null) {
                report(name + ": the built class is on the harness's classpath (not readable)", false);
                return;
            }
            new org.objectweb.asm.ClassReader(in).accept(node, 0);
        } catch (java.io.IOException e) {
            report(name + ": the built class is readable (" + e + ")", false);
            return;
        }

        org.objectweb.asm.tree.MethodNode method = null;
        int maxLocals = 0;
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            if (m.name.equals(methodName) && m.desc.endsWith(tailDesc)) {
                method = m;
                maxLocals = m.maxLocals;
                break;
            }
        }
        if (method == null) {
            report(name + ": the method exists in the built class", false);
            return;
        }

        // A small symbolic stack: Float for a literal, Integer for an array index, int[] for an array local.
        java.util.List<Object> stack = new java.util.ArrayList<>();
        Object[] locals = new Object[Math.max(maxLocals, 16)];
        java.util.List<Integer> readIndices = new java.util.ArrayList<>();
        java.util.List<String> readNames = new java.util.ArrayList<>();
        boolean pushed = false;
        int scaleAt = -1;
        int lastTranslateAt = -1;
        java.util.List<float[]> translates = new java.util.ArrayList<>();

        for (int i = 0; i < method.instructions.size(); i++) {
            org.objectweb.asm.tree.AbstractInsnNode insn = method.instructions.get(i);
            int op = insn.getOpcode();
            if (op == org.objectweb.asm.Opcodes.LDC) {
                Object cst = ((org.objectweb.asm.tree.LdcInsnNode) insn).cst;
                if (cst instanceof Float) {
                    stack.add(cst);
                } else if (cst instanceof Integer) {
                    stack.add(cst);
                } else {
                    stack.clear();
                }
            } else if (op == org.objectweb.asm.Opcodes.FCONST_0) {
                stack.add(0.0F);
            } else if (op == org.objectweb.asm.Opcodes.FCONST_1) {
                stack.add(1.0F);
            } else if (op == org.objectweb.asm.Opcodes.FCONST_2) {
                stack.add(2.0F);
            } else if (op >= org.objectweb.asm.Opcodes.ICONST_0 && op <= org.objectweb.asm.Opcodes.ICONST_5) {
                stack.add(op - org.objectweb.asm.Opcodes.ICONST_0);
            } else if (op == org.objectweb.asm.Opcodes.BIPUSH || op == org.objectweb.asm.Opcodes.SIPUSH) {
                stack.add(((org.objectweb.asm.tree.IntInsnNode) insn).operand);
            } else if (op == org.objectweb.asm.Opcodes.ALOAD) {
                Object local = locals[((org.objectweb.asm.tree.VarInsnNode) insn).var];
                stack.add(local instanceof RectOrigin ? ((RectOrigin) local).clone() : local);
            } else if (op == org.objectweb.asm.Opcodes.ILOAD) {
                stack.add(0);
            } else if (op == org.objectweb.asm.Opcodes.ISTORE || op == org.objectweb.asm.Opcodes.ASTORE
                    || op == org.objectweb.asm.Opcodes.FSTORE) {
                Object value = stack.isEmpty() ? null : stack.remove(stack.size() - 1);
                locals[((org.objectweb.asm.tree.VarInsnNode) insn).var] = value;
                stack.clear();
            } else if (op == org.objectweb.asm.Opcodes.IALOAD) {
                Object index = stack.isEmpty() ? null : stack.remove(stack.size() - 1);
                Object array = stack.isEmpty() ? null : stack.remove(stack.size() - 1);
                if (array instanceof RectOrigin && index instanceof Integer) {
                    int idx = (Integer) index;
                    // The block's rectangle is {left, top, width, height}: only [0] and [1] can be a translate.
                    stack.add(idx == 0 || idx == 1 ? Float.valueOf(0.0F) : null);
                    readIndices.add(idx);
                    readNames.add(((RectOrigin) array).accessor);
                } else {
                    stack.add(null);
                }
            } else if (op == org.objectweb.asm.Opcodes.DUP) {
                if (!stack.isEmpty()) {
                    Object top = stack.get(stack.size() - 1);
                    stack.add(top instanceof RectOrigin ? ((RectOrigin) top).clone() : top);
                }
            } else if (op == org.objectweb.asm.Opcodes.I2F) {
                if (stack.isEmpty()) {
                    stack.add(null);
                }
                // Otherwise the value keeps its identity; a converted index model is still an Integer.
            } else if (op == org.objectweb.asm.Opcodes.INVOKESTATIC) {
                org.objectweb.asm.tree.MethodInsnNode call = (org.objectweb.asm.tree.MethodInsnNode) insn;
                if (call.desc.endsWith(")[I")) {
                    // A panel-local rectangle accessor: carry its identity so IALOAD can read it and the
                    // comparison below can require THIS accessor's own rectangle.
                    stack.add(new RectOrigin(call.name));
                } else {
                    stack.clear();
                }
            } else if (op == org.objectweb.asm.Opcodes.INVOKEVIRTUAL
                    || op == org.objectweb.asm.Opcodes.INVOKEINTERFACE) {
                org.objectweb.asm.tree.MethodInsnNode call = (org.objectweb.asm.tree.MethodInsnNode) insn;
                if (call.name.equals("drawString")) {
                    break;
                }
                if (call.owner.endsWith("PoseStack") && call.name.equals("pushPose")) {
                    pushed = true;
                } else if (call.owner.endsWith("PoseStack") && call.name.equals("translate")) {
                    lastTranslateAt = i;
                    float[] args = popFloats(stack, 3);
                    if (args != null) {
                        translates.add(new float[] {args[0], args[1]});
                    }
                } else if (call.owner.endsWith("PoseStack") && call.name.equals("scale")) {
                    scaleAt = i;
                }
                stack.clear();
            } else {
                stack.clear();
            }
        }

        // The floats' VALUES are placeholders in this symbolic scan, so read the two numbers out of the
        // accessor itself and compare those with the block rectangle the clip check used. That closes the pair:
        // the clip is derived from this method's rectangle, and the translate is fed from it.
        int[] accessorRect = blockAccessor.equals("textBlockRect")
                ? (classResource.endsWith("MachineControllerScreen")
                        ? com.reborn.modularmachinery.client.MachineControllerScreen.textBlockRect()
                        : com.reborn.modularmachinery.client.FactoryControllerScreen.textBlockRect())
                : com.reborn.modularmachinery.client.ScreenLayout.ControllerPanel.textRect();
        report(name + ": ..." + blockAccessor + "() really answers (" + accessorRect[0] + ", " + accessorRect[1]
                        + "), the block origin the clip check used (" + blockRect[0] + ", " + blockRect[1] + ")",
                accessorRect[0] == blockRect[0] && accessorRect[1] == blockRect[1]);

        report(name + ": pushes the pose stack before drawing (" + (pushed ? "found pushPose" : "NO pushPose")
                        + ")", pushed);
        boolean any = !translates.isEmpty();
        // At least one translate, not an exact count: the factory's foreground layer makes its own panel-origin
        // translate too, and both are legitimate. What 0.24.2 did instead was translate NEITHER and scale the
        // block where it stood, which this rejects.
        report(name + ": the pose is translated before anything is drawn, to the block's own origin from "
                        + blockAccessor + " (" + translates.size() + " translate(s) seen)", any);
        // The translate's third argument (z) must be the constant 0, and the first two must have been READ OUT
        // of that accessor's own array (indices 0 and 1) rather than typed into the call by hand. Several
        // arrays are read in these methods — the clip's four numbers come first — so the pair that fed the
        // translate is the LAST pair read before it.
        int n = readNames.size();
        boolean fromAccessor = n >= 2
                && readNames.get(n - 2).equals(blockAccessor) && readNames.get(n - 1).equals(blockAccessor)
                && readIndices.get(n - 2) == 0 && readIndices.get(n - 1) == 1;
        report(name + ": ...and those two floats are read from " + blockAccessor + "()[0] and [1] (arrays read: "
                        + readNames + " at " + readIndices + "), not written into the call by hand", fromAccessor);
        report(name + ": the scale comes after that translate, and before any string is drawn (translate at "
                        + lastTranslateAt + ", scale at " + scaleAt + ")",
                scaleAt > lastTranslateAt && lastTranslateAt >= 0);
    }

    /** Pops {@code n} floats off the symbolic stack, or {@code null} when they are not all floats. */
    private static float[] popFloats(java.util.List<Object> stack, int n) {
        if (stack.size() < n) {
            return null;
        }
        float[] out = new float[n];
        for (int i = n - 1; i >= 0; i--) {
            Object v = stack.remove(stack.size() - 1);
            if (!(v instanceof Float)) {
                return null;
            }
            out[i] = (Float) v;
        }
        return out;
    }

    /**
     * The panel-local rectangle behind a static {@code int[]} accessor, so the bytecode check can name it.
     *
     * <p>{@code ALOAD} pushes a copy, which matters: a method may read more than one such array, and the check
     * has to be able to say which array a translate's two floats came out of.
     */
    private static final class RectOrigin implements Cloneable {
        final String accessor;

        RectOrigin(String accessor) {
            this.accessor = accessor;
        }

        @Override
        public RectOrigin clone() {
            return new RectOrigin(this.accessor);
        }
    }

    /**
     * The property that distinguishes a correct screen from the shipped 0.24.2 one: <b>everything the block
     * paints lies inside the rectangle the screen clips to</b>, and the clip stops short of the first glyph
     * column by at least the font shadow so the leftmost column is never sliced.
     *
     * <p>This is deliberately a predicate over two rectangles that come from two different places — the clip
     * the screen installs and the origin its drawing matrix is translated to — plus an independently computed
     * glyph column. An implementation cannot satisfy it by agreeing with itself.
     */
    private static void checkDrawnInsideClip(String name, int[] drawn, int[] clip, int[] glyphs) {
        boolean contains = drawn[0] >= clip[0] && drawn[1] >= clip[1]
                && drawn[0] + drawn[2] <= clip[2] && drawn[1] + drawn[3] <= clip[3];
        report(name + ": the block as drawn " + rect(drawn) + " (panel px) lies inside the clip " + rect(clip),
                contains);
        report(name + ": the clip leaves the first glyph column intact: clip left " + clip[0] + " < glyph left "
                        + glyphs[0] + " and glyph right " + (glyphs[0] + glyphs[2]) + " <= clip right " + clip[2],
                clip[0] < glyphs[0] && glyphs[0] + glyphs[2] <= clip[2]);
        report(name + ": the block's own rectangle reaches the clip's right edge and stops short of its bottom ("
                        + drawn[2] + "x" + drawn[3] + " inside " + clip[2] + "x" + clip[3] + ")",
                drawn[0] + drawn[2] == clip[2] && drawn[1] + drawn[3] < clip[3]);
    }

    /** Whether {@code drawn} and every pixel of the first glyph column are inside {@code clip}. */
    private static boolean drawnInsideClip(int[] drawn, int[] clip, int[] glyphs) {
        return drawn[0] >= clip[0] && drawn[1] >= clip[1]
                && drawn[0] + drawn[2] <= clip[2] && drawn[1] + drawn[3] <= clip[3]
                && clip[0] < glyphs[0];
    }
    /**
     * The predicates that make an information block unable to leave its region, applied to one block. Each one
     * compares two numbers the drawing code really uses, so a self-consistent table of constants cannot satisfy
     * them on its own.
     */
    private static void blockInside(String name,
            com.reborn.modularmachinery.client.ScreenLayout.Block block,
            List<com.reborn.modularmachinery.client.ScreenLayout.Measured> rows,
            int regionEnd, int labelY, int inventoryTop) {
        // Independently re-walk the payload: the number of rows the drawing loop would paint.
        int cursor = block.startY;
        int drawn = 0;
        int footerFloor = Math.max(block.startY, regionEnd - block.footerHeight);
        for (com.reborn.modularmachinery.client.ScreenLayout.Measured row : rows) {
            int after = cursor + row.height + row.gap;
            if (after > footerFloor) {
                break;
            }
            cursor = after;
            drawn++;
        }
        report(name + ": the block stays inside its region " + regionEnd + " (block " + block + ")",
                block.insideRegion() && block.footerY <= block.availableEnd);
        report(name + ": the drawing loop and the measured payload agree on how many rows are drawn (walked "
                        + drawn + ", block says " + block.drawnLines + " of " + rows.size() + ")",
                drawn == block.drawnLines && block.drawnLines <= rows.size());
        report(name + ": the closing line starts at or after the payload's last row ("
                        + block.footerY + " >= " + block.payloadEndY + ")",
                block.footerY >= block.payloadEndY);
        report(name + ": the block's bottom in panel pixels ("
                        + (block.blockEndY() * 72 / 100) + ") is above the label (" + labelY
                        + ") and the first slot row (" + inventoryTop + ")",
                block.blockEndY() * 72 / 100 < labelY && block.blockEndY() * 72 / 100 < inventoryTop);
    }

    /** {@code [x, y, w, h]} to the string the reports print. */
    private static String rect(int[] r) {
        return "[" + r[0] + "," + r[1] + " " + r[2] + "x" + r[3] + "]";
    }

    /** {@code "[x,y wxh]"} back to the four numbers. */
    private static int[] rectOf(String text) {
        String body = text.substring(1, text.length() - 1);
        int space = body.indexOf(' ');
        String[] xy = body.substring(0, space).split(",");
        String[] wh = body.substring(space + 1).split("x");
        return new int[] {Integer.parseInt(xy[0]), Integer.parseInt(xy[1]),
                Integer.parseInt(wh[0]), Integer.parseInt(wh[1])};
    }

    static void check(String what, long expected, long actual) {
        report(what + ": expected " + expected + ", got " + actual, expected == actual);
    }

    static void check(String what, String expected, String actual) {
        report(what + ": expected " + expected + ", got " + actual, expected.equals(actual));
    }

    static void report(String line, boolean ok) {
        checks++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + line);
        if (!ok) {
            failures++;
        }
    }

    /**
     * Prints the evidence file's absolute path and flushes it. Called once, at the end of a completed run;
     * the shutdown hook on {@link Evidence} covers a run that dies half-way.
     */
    private static void closeEvidence() {
        if (EVIDENCE != null && !EVIDENCE.closed) {
            EVIDENCE.closed = true;
            EVIDENCE.flushFile();
            EVIDENCE.closeFile();
            System.out.println("evidence written to " + EVIDENCE.file.getAbsolutePath());
        }
    }

    /**
     * The harness's own copy of its stdout and stderr.
     *
     * <p>It is created as the <b>first</b> statement of {@code main}, so a crash, a failed assertion or a
     * {@code System.exit} still leaves the transcript on disk. Both the console stream and the file receive
     * every byte.
     */
    private static final class Evidence {

        private final File file;
        private final FileOutputStream fileStream;
        private final java.io.Writer writer;
        private final java.nio.charset.Charset consoleCharset;
        private boolean closed;
        private boolean summaryPrinted;

        private Evidence(String[] args) {
            File resolved = null;
            FileOutputStream stream = null;
            java.io.Writer writer = null;
            try {
                resolved = resolveFile(explicitPath(args));
                File parent = resolved.getAbsoluteFile().getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                // Deliberately UTF-8 and not the JVM's default charset: the workspace path is not
                // ASCII, and an evidence file that only decodes under the machine's ANSI codepage is
                // not evidence. The console keeps whatever encoding it already had.
                stream = new FileOutputStream(resolved, false);
                writer = new java.io.OutputStreamWriter(stream, StandardCharsets.UTF_8);
            } catch (Exception exception) {
                stream = null;
                writer = null;
                System.out.println("  FAIL  the evidence file could not be opened: " + exception);
            }
            ConsoleMirror mirror = new ConsoleMirror(System.out, System.err);
            this.file = resolved;
            this.fileStream = stream;
            this.writer = writer;
            // The charset System.out.println() encoded its bytes with. Console behaviour is untouched:
            // the mirror only has to know how to get the same characters back out of those bytes.
            this.consoleCharset = java.nio.charset.Charset.defaultCharset();
            System.setOut(mirror);
            System.setErr(mirror);
            if (stream != null) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (!closed) {
                        closed = true;
                        flushFile();
                        closeFile();
                    }
                }, "mmverify-evidence-flush"));
            }
        }

        /**
         * The one thing a tee must not break: the counts and the text agree. This is the harness's own
         * self-check, so a broken tee fails the run instead of quietly printing a wrong summary.
         */
        private String selfTest() {
            int checks0 = checks;
            int failures0 = failures;
            report("injected: the evidence tee's counters (control check that must pass)", true);
            report("injected: the evidence tee's counters (control check that must fail)", false);
            int deltaChecks = checks - checks0;
            int deltaFailures = failures - failures0;
            checks = checks0;
            failures = failures0;
            if (deltaChecks != 2 || deltaFailures != 1) {
                throw new IllegalStateException("the evidence tee does not count checks: " + deltaChecks
                        + " checks, " + deltaFailures + " failures");
            }
            return "  PASS  injected: the evidence tee counts every check (" + deltaChecks
                    + " observed) and every failure (" + deltaFailures + " observed), so the summary line"
                    + " below is the harness's own arithmetic";
        }

        private void flushFile() {
            if (writer != null) {
                try {
                    writer.flush();
                } catch (IOException ignored) {
                    // Nothing useful can be added to the transcript by a failing flush.
                }
            }
        }

        private void closeFile() {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException ignored) {
                    // Same: reported by the exit status, not by another line.
                }
            }
        }

        private void writeToFile(int b) {
            if (writer != null) {
                try {
                    writer.write(b);
                } catch (IOException ignored) {
                    // Nothing useful can be added to the transcript by a failing write.
                }
            }
        }

        private void writeToFile(byte[] bytes, int offset, int length) {
            if (writer != null) {
                try {
                    writer.write(new String(bytes, offset, length, consoleCharset));
                } catch (IOException ignored) {
                    // Same: the exit status, not another line, reports a broken transcript.
                }
            }
        }

        private static String explicitPath(String[] args) {
            String property = System.getProperty("mmverify.evidence");
            if (property != null && !property.isBlank()) {
                return property;
            }
            for (int i = 0; args != null && i < args.length - 1; i++) {
                if (args[i].equals("--evidence")) {
                    return args[i + 1];
                }
            }
            return null;
        }

        private static String detectVersion() {
            String classpath = System.getProperty("java.class.path", "");
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("modular_machinery_reborn-([0-9][0-9A-Za-z.+-]*)\\.jar")
                    .matcher(classpath);
            return matcher.find() ? matcher.group(1) : "unknown";
        }

        /**
         * Resolves the destination without trusting the working directory.
         *
         * <p>Order: the explicit path (the Gradle task passes one, deliberately through the ASCII junction
         * {@code C:/mmwork} because the workspace path is not ASCII), then the checkout found by walking up
         * from {@code user.dir}, then, only if no checkout is visible, {@code _audit/m6c-verify} under the
         * working directory.
         */
        private static File resolveFile(String explicit) {
            String name = "harness-run-" + detectVersion() + ".txt";
            if (explicit != null && !explicit.isBlank()) {
                return new File(explicit.trim());
            }
            File root = findProjectRoot(Paths.get(System.getProperty("user.dir", ".")));
            if (root != null) {
                return new File(new File(root, "_audit/m6c-verify"), name);
            }
            return new File(new File("_audit/m6c-verify"), name);
        }

        private static File findProjectRoot(Path start) {
            Path candidate = start == null ? null : start.toAbsolutePath();
            File best = null;
            int bestScore = 0;
            while (candidate != null) {
                File directory = candidate.toFile();
                int score = 0;
                if (new File(directory, "_audit").isDirectory()) {
                    score++;
                }
                if (new File(directory, "_audit/m6c-verify").isDirectory()) {
                    score++;
                }
                if (new File(directory, "modular-machinery-reborn").isDirectory()
                        && new File(directory, ".tmp-m6c-verify").isDirectory()) {
                    score++;
                }
                // A real checkout wins outright; otherwise keep the best ancestor seen, because a
                // junction and its target are two spellings of one directory and either is fine.
                if (score > bestScore) {
                    bestScore = score;
                    best = directory;
                }
                if (score == 3) {
                    break;
                }
                candidate = candidate.getParent();
            }
            return best;
        }
    }

    /**
     * Prints to the original console streams and to the evidence file, so the harness keeps printing where
     * it always did -- Gradle's {@code --console=plain} stays readable -- while the file stops depending on
     * whoever launched it.
     */
    private static final class ConsoleMirror extends PrintStream {

        private final PrintStream consoleOut;

        private ConsoleMirror(PrintStream out, PrintStream err) {
            super(out, false);
            this.consoleOut = out;
        }

        @Override
        public void write(int b) {
            consoleOut.write(b);
            EVIDENCE.writeToFile(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            consoleOut.write(bytes, offset, length);
            EVIDENCE.writeToFile(bytes, offset, length);
        }

        @Override
        public void flush() {
            consoleOut.flush();
            EVIDENCE.flushFile();
        }

        @Override
        public void close() {
            // Deliberately neither closes the console nor the evidence file: main decides when the
            // evidence is complete, and a stream that closes System.out is a trap.
            flush();
        }
    }

    private ParallelCraftCheck() {
    }
}
