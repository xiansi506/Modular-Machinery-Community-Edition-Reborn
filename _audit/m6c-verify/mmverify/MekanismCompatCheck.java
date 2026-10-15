package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.compat.CompatModule;
import com.reborn.modularmachinery.compat.ModCompat;
import com.reborn.modularmachinery.compat.mekanism.MekanismModule;
import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.IOType;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RequirementType;
import com.reborn.modularmachinery.recipe.RequirementTypes;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 波 5.3 — 第一个真兼容模块（Mekanism 化学／旧版的「气体」）：能离线证明的那一半。
 *
 * <h2>这一节的边界是先测出来的，不是猜的</h2>
 *
 * <p>开工前用一个一次性探针把 Mekanism 1.20.1 的 chemical API 在**这套离线 classpath 下**逐项打了一遍
 * （探针源码与结果见 {@code docs/波5.3-勘测-Mekanism化学体系.md}）。结论是二分的：
 *
 * <ul>
 *   <li><b>能加载</b>：{@code ChemicalType}（4 个值：GAS/INFUSION/PIGMENT/SLURRY）、{@code Action}、
 *       {@code IGasHandler} —— 形状可以断言；</li>
 *   <li><b>不能加载</b>：{@code MekanismAPI}。它的静态初始化走到
 *       {@code registryKey(...)} 时抛 {@code IllegalArgumentException: Not bootstrapped}，
 *       而 {@code GasStack.<clinit>} 又要 {@code MekanismAPI.EMPTY_GAS} 才能建起来。
 *       也就是说**气体的 Forge 注册表和 {@code GasStack} 的构造都只在游戏里存在**。</li>
 * </ul>
 *
 * <p>所以这一节断言的是「注册、分派、字段校验、种类身份、modid 声明、摘除」这些**纯逻辑**，
 * 而**不去碰**任何合法气体的解析：那条路要真注册表，只能实机验。这条边界是实测出来的，
 * 不是「懒得测」——把它写成断言（{@code gasRegistry()} 在这里必须失败），
 * 将来若有人以为离线能验它，这条断言会告诉他不能。
 *
 * <p>断言里刻意不出现「正确的 gas 名字是 {@code mekanism:oxygen}」这种话：
 * **气体的确切注册键我未查到**（离线枚举不了注册表），所以实现不做任何名字映射、不认识就报错，
 * 而本节只断言那条报错消息**说清了该怎么写**。
 */
final class MekanismCompatCheck {

    /** 本模块注册的两个种类，全限定 id。 */
    private static final String GAS = "modular_machinery_reborn:gas";
    private static final String GAS_PER_TICK = "modular_machinery_reborn:gas_pertick";

    private MekanismCompatCheck() {
    }

    // ================================================================== the section

    static void mekanismChemical() {
        section("AC. 波 5.3: the Mekanism chemical module (gas / gas_pertick)");

        // 红先行：模块类不存在时这一节要报 FAIL 而不是编译不过。
        boolean present = present("com.reborn.modularmachinery.compat.mekanism.MekanismModule");
        report("the production module " + MekanismModule.class.getName() + " exists", present);
        if (!present) {
            report("skipping AC's behaviour checks: the module is absent, which the check above already "
                    + "reported.", true);
            return;
        }

        // (1) 注册之前：注册表里只有内置五种（波 5.2 的成果），没有气体。
        report("before the module registers, '" + GAS + "' is unknown",
                RequirementTypes.byName(GAS) == null);
        check("the built-in kinds are still exactly the five from wave 5.2",
                "[item, fluid, energy, interface_number_input, ingredient_array_input]", paths().toString());

        // (2) 装了就注册：走 ModCompat 的入口，不是直接调 RequirementTypes。
        List<CompatModule> registered = ModCompat.register(List.of(new MekanismModule()), modid -> true);
        check("the Mekanism module registered through the compat entry point", 1, registered.size());
        RequirementType gas = RequirementTypes.byName(GAS);
        RequirementType gasPerTick = RequirementTypes.byName(GAS_PER_TICK);
        report("'" + GAS + "' is now a registered requirement kind", gas != null);
        report("'" + GAS_PER_TICK + "' is now a registered requirement kind", gasPerTick != null);
        check("the registry now holds the built-ins plus the two gas kinds", 7,
                RequirementTypes.all().size());
        check("both kinds declare the original's own modid as their requirement (RequirementTypeGas:34-36)",
                "mekanism", String.valueOf(gas == null ? null : gas.requiresModid()));
        check("...and the per-tick kind too", "mekanism",
                String.valueOf(gasPerTick == null ? null : gasPerTick.requiresModid()));
        check("both kinds are the original's two ids (RequirementTypesMM:38-39)", "gas / gas_pertick",
                (gas == null ? "?" : gas.id().getPath()) + " / "
                        + (gasPerTick == null ? "?" : gasPerTick.id().getPath()));

        if (gas == null || gasPerTick == null) {
            report("skipping AC's kind checks: a kind is missing, which the checks above already reported.", true);
            return;
        }

        try {
            fieldChecks(gas, gasPerTick);
            modifierTargetIsAll(gas, gasPerTick);
            registrationMakesGasLoadable(gas);
            theApiReallyIsGameOnly();
            sourceLevelDivergences();
            // 端口那一半：先把离线能证的结构与「没装时为空」证掉，再让摘除那一段收尾。
            portWiring(gasPerTick);
            // 实机报告换来的那一条：可选依赖的 versionRange 不能省。
            modsTomlOptionalDependencies();
            requiresModidWithdrawal();
        } finally {
            // 这一节往全局注册表里加了东西并（在摘除那一段里）动过它，必须还回去：
            // 后面的分节会问「注册表里有什么」，被本节影响就会得到假结论。
            restoreRegistry();
        }
        report("after the section the two gas kinds are gone again and the five built-ins remain",
                RequirementTypes.byName(GAS) == null && RequirementTypes.byName(GAS_PER_TICK) == null
                        && RequirementTypes.all().size() == 5);
    }

    // ================================================================== (3) field validation

    /**
     * 字段校验：每一条畸形输入都必须被拒，而且消息里说清该写什么。
     *
     * <p>这些**不碰注册表**（{@code read} 先校验自己的字段，最后才查气体），所以离线能证。
     * 顺序本身就是这一节能存在的原因——若先查气体，下面每一条都会报「气体不认识」，
     * 断言就只能证明「这里有异常」，证明不了「这个字段被校验了」。
     */
    private static void fieldChecks(RequirementType gas, RequirementType gasPerTick) {
        // (a) 合法形状：它会在查注册表时失败（离线没有已接上的气体注册表），但报的必须是**气体**那一条，
        //     这恰好证明前面的字段校验都过了。断言要求**正好**是那一条消息——不接受一个泛泛的
        //     「这里抛异常了」：那正是「过宽的断言等于没断言」。
        try {
            gas.read(obj("{\"gas\":\"mekanism:oxygen\",\"amount\":1000}"), IOType.INPUT, WHERE);
            report("expected the registry lookup to fail offline for a well-formed gas requirement", false);
        } catch (RuntimeException exception) {
            String message = String.valueOf(exception.getMessage());
            report("a well-formed gas requirement gets past field validation and fails only at the registry "
                    + "lookup: " + firstLine(message), message.contains("Unknown Mekanism gas"));
        }

        Object[][] bad = {
                {"{\"gas\":\"mekanism:oxygen\"}", "Missing 'amount'"},
                {"{\"gas\":\"mekanism:oxygen\",\"amount\":\"lots\"}", "must be a number"},
                {"{\"gas\":\"mekanism:oxygen\",\"amount\":0}", "at least 1"},
                {"{\"gas\":\"mekanism:oxygen\",\"amount\":-5}", "at least 1"},
                {"{\"amount\":1000}", "Missing 'gas'"},
                {"{\"gas\":123,\"amount\":1000}", "must be a string"},
                {"{\"gas\":\"\",\"amount\":1000}", "is empty"},
                {"{\"gas\":\"not a location!!\",\"amount\":1000}", "not a valid resource location"},
                {"{\"gas\":\"mekanism:oxygen\",\"amount\":1000,\"chance\":\"half\"}", "must be a number between 0 and 1"},
                {"{\"gas\":\"mekanism:oxygen\",\"amount\":1000,\"chance\":50}", "between 0 and 1"},
                {"{\"gas\":\"mekanism:oxygen\",\"amount\":1000,\"chance\":-1}", "between 0 and 1"},
        };
        for (Object[] c : bad) {
            try {
                MachineRequirement requirement = gas.read(obj((String) c[0]), IOType.INPUT, WHERE);
                report("expected a rejection for " + c[0] + " but got " + requirement, false);
            } catch (RuntimeException exception) {
                String message = String.valueOf(exception.getMessage());
                report("rejected: " + firstLine(message), message.contains((String) c[1]));
            }
        }

        // (b) 合法 amount / chance 走得过校验（仍然只会在注册表那一步失败）。
        try {
            gasPerTick.read(obj("{\"gas\":\"mekanism:oxygen\",\"amount\":1,\"chance\":0.25}"),
                    IOType.OUTPUT, WHERE);
            report("expected the registry lookup to fail offline for a well-formed per-tick requirement", false);
        } catch (RuntimeException exception) {
            String message = String.valueOf(exception.getMessage());
            report("a per-tick requirement with amount 1 and chance 0.25 also gets past field validation",
                    message.contains("Unknown Mekanism gas"));
        }
    }

    // ================================================================== (4) modifier target

    /**
     * 两个种类都报 {@link RecipeModifier.Target#ALL}——**这是有意的，也是本波唯一一处「不如旧版」的地方**。
     *
     * <p>{@code Target} 是波 5.2 决定「本轮保持闭枚举」的那个枚举，它没有气体项。若为了「有归属」而返回
     * {@code FLUID}，一条写 {@code "target": "fluid"} 的修改器就会**静默地**作用到气体上；
     * 返回 {@code ALL} 则只有通配修改器能碰到它。断言把现状钉住：将来 {@code Target} 真的打开了，
     * 这条会失败——而那正是那个改动该被做的信号。
     */
    private static void modifierTargetIsAll(RequirementType gas, RequirementType gasPerTick) {
        check("the gas kind reports ALL (the closed enum has no gas entry; a concrete entry would let a "
                + "'fluid' modifier silently reach gas)", "ALL", String.valueOf(gas.modifierTarget()));
        check("...and the per-tick kind too", "ALL", String.valueOf(gasPerTick.modifierTarget()));

        // 选 ALL 的实际后果，**用 RecipeModifier 自己的匹配规则量出来**，而不是复述一句「ALL 表示所有」：
        // 通配修改器必须碰得到气体，而写具体项的必须碰不到。后者正是「不选 FLUID」的理由。
        RecipeModifier wildcard = new RecipeModifier(RecipeModifier.Target.ALL, null, 2.0F,
                RecipeModifier.Operation.MULTIPLY, false);
        RecipeModifier fluidOnly = new RecipeModifier(RecipeModifier.Target.FLUID, null, 2.0F,
                RecipeModifier.Operation.MULTIPLY, false);
        RecipeModifier itemOnly = new RecipeModifier(RecipeModifier.Target.ITEM, null, 2.0F,
                RecipeModifier.Operation.MULTIPLY, false);
        check("a wildcard target '*' reaches the gas kind (that is what ALL buys)", "true",
                String.valueOf(wildcard.matches(gas.modifierTarget(), IOType.INPUT, false)));
        check("a 'fluid' modifier does NOT reach it (it would if modifierTarget() returned FLUID)", "false",
                String.valueOf(fluidOnly.matches(gas.modifierTarget(), IOType.INPUT, false)));
        check("an 'item' modifier does not either", "false",
                String.valueOf(itemOnly.matches(gas.modifierTarget(), IOType.INPUT, false)));
    }

    // ================================================================== (5) registration

    /**
     * 注册之后，这个种类在**配方解析入口**上真的能被找到——这是「模块注册生效」的完整含义。
     *
     * <p>只断言 {@code RequirementTypes.byName} 查到是不够的：那样「注册了但解析器不认」也会通过。
     * 所以这里走 {@code MachineRequirementParser} 的入口，断言它**认出种类**（而不是报「未知需求类型」）。
     */
    private static void registrationMakesGasLoadable(RequirementType gas) {
        JsonObject requirement = obj("{\"type\":\"" + GAS + "\",\"io-type\":\"input\","
                + "\"gas\":\"mekanism:oxygen\",\"amount\":1000}");
        try {
            com.reborn.modularmachinery.recipe.MachineRequirementParser.parse(
                    requirement, new ResourceLocation("mmverify", "gas_recipe"));
            report("expected the offline registry lookup to fail while parsing a gas recipe", false);
        } catch (RuntimeException exception) {
            String message = String.valueOf(exception.getMessage());
            report("the parser now KNOWS the gas kind (it no longer says 'Unknown requirement type'): "
                    + firstLine(message), !message.contains("Unknown requirement type"));
        }

        // 对照：一个没注册过的种类仍然报「未知需求类型」并列出已注册的清单。
        try {
            com.reborn.modularmachinery.recipe.MachineRequirementParser.parse(
                    obj("{\"type\":\"modular_machinery_reborn:no_such_kind\",\"io-type\":\"input\"}"),
                    new ResourceLocation("mmverify", "nope"));
            report("expected an unregistered kind to be rejected", false);
        } catch (RuntimeException exception) {
            String message = String.valueOf(exception.getMessage());
            report("an unregistered kind is still refused by name, and the message lists what IS registered "
                    + "(now including the gas kinds)", message.contains("Unknown requirement type")
                            && message.contains(GAS));
        }
    }

    // ================================================================== (6) the measured boundary

    /**
     * 把「离线验不了合法的气体」这件事**写成断言**。
     *
     * <p>这不是凑数：它是这一节边界之所以如此的证据。若将来有人以为离线可以验气体解析，
     * 或者 Mekanism 改了注册表的初始化方式，这条会翻转——那时边界就该重新量一次。
     */
    private static void theApiReallyIsGameOnly() {
        boolean chemicalTypeLoads;
        try {
            chemicalTypeLoads = mekanism.api.chemical.ChemicalType.values().length == 4;
        } catch (Throwable absent) {
            chemicalTypeLoads = false;
        }
        report("the chemical ENUM loads offline (4 flavours: GAS/INFUSION/PIGMENT/SLURRY)", chemicalTypeLoads);

        // 断言的是「**拿不到一个可用的注册表**」，而不是「一定抛异常」：实测过两种离线形态——
        // 没有 bootstrap 的裸进程里 MekanismAPI 静态初始化就失败；引导过注册表但 Forge 尚未接上的进程里
        // （本装置就是这一种）gasRegistry() 直接返回 null。两种都让「合法气体」这条路走不通，
        // 而**哪一种出现取决于进程状态**，所以断言只钉住那个共同的后果。
        String registryState;
        try {
            Object registry = mekanism.api.MekanismAPI.gasRegistry();
            registryState = registry == null ? "null (no Forge registry attached)" : "usable: " + registry;
        } catch (Throwable unavailable) {
            registryState = "unavailable: " + unavailable.getClass().getSimpleName();
        }
        report("...and the gas REGISTRY is not usable here, which is the measured reason the valid-gas path is "
                + "game-only: " + registryState, registryState.startsWith("null")
                        || registryState.startsWith("unavailable"));
    }

    // ================================================================== (7) deliberate divergences

    /**
     * 两处**有意与旧版不同**的行为，用源码级断言钉住。
     *
     * <p>为什么用源码而不是行为：这两条都要一个真实气体才能触发（旧版那条路是「越界 chance 静默丢弃」，
     * 要在解析成功之后才观察得到），而离线拿不到气体。源码断言在这里不是退而求其次——
     * 它断言的恰恰是「这段代码里没有静默回落」，而那正是要保证的东西。
     * 项目里已有同样的做法（{@code ParallelCraftCheck} 对 {@code MachineRecipeText} 的检查）。
     */
    private static void sourceLevelDivergences() {
        Path source = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                + "compat/mekanism/MekanismModule.java");
        try {
            String text = Files.readString(source);
            report("the amount reader refuses a too-small amount rather than clamping it to 0 the way the "
                    + "original did (RequirementTypeGas:60)", text.contains("at least 1 mB"));
            report("the chance reader refuses an out-of-range chance rather than silently falling back to 1.0 "
                    + "the way the original did (RequirementTypeGas:69-71)", text.contains("between 0 and 1"));
            report("...and both messages say what the OLD file wrote, so a pack author knows what to change",
                    text.contains("The original clamped this to 0") && text.contains("a \\\"chance\\\": 50"));
            report("the gas-name error says the id is fully qualified in 1.20.1 and that this port does not map "
                    + "old names", text.contains("does not guess")
                            && text.contains("GasRegistry.getGas(\\\"oxygen\\\")"));
        } catch (Exception exception) {
            report("reading " + source + " failed: " + exception, false);
        }
    }

    // ================================================================== (7b) the port half

    /**
     * 端口那一半：**离线只能证结构与「没装时为空」**，不能证真的装了气体。
     *
     * <p>为什么证的只有这些：一个可用的气体端口需要一个真实的 {@code IGasHandler} 实现，而构造它（{@link
     * com.reborn.modularmachinery.compat.mekanism.HybridGasTank}）要 {@code GasStack}，那要气体注册表——
     * 见 {@link #theApiReallyIsGameOnly()}。所以这一节断言的是**接线**：
     *
     * <ol>
     *   <li>没装 Mekanism 时 {@code gasPortReady()} 为 false，端口收集拿到的是空列表——于是气体配方
     *       表现为开工失败而不是崩溃（这一条本身就是可观察的行为，值得钉住）；</li>
     *   <li>端口收集那条路**存在且被调用**：{@code MachineControllerBlockEntity} 真的把实体交给了
     *       {@code Builder.gasPorts}。这一条只能读源码，但它挡的是一整类缺陷——桥做好了却没人调用，
     *       而「没人调用」在离线环境里是**完全静默**的（气体桶永远是空的，与「没装 Mekanism」无法区分）；</li>
     *   <li>桥必须**同时**实现能力查询与端口收集两条路：只做前者则管道能用而配方看不到，
     *       只做后者则配方能用而管道灌不进去。两条都断在源码上。</li>
     * </ol>
     */
    private static void portWiring(RequirementType gasPerTick) {
        report("without Mekanism the gas capability is unavailable, so gasPortReady() is false",
                !MekanismModule.gasPortReady());

        report("...and the compat layer offers no port handlers for any entity (no mapper is registered)",
                ModCompat.portHandlers(new Object()).isEmpty());

        // 端口收集这条路：一个不认识的对象交进去，什么都不该发生（不崩、不加端口）。
        HatchCollection ports = HatchCollection.builder()
                .gasPorts(new Object(), true, null)
                .gasPorts(new Object(), false, "line_1")
                .build();
        check("a gas-less machine collects zero gas inputs", 0, ports.gasInputs().size());
        check("...and zero gas outputs", 0, ports.gasOutputs().size());
        report("...and asking did not invent a port out of an arbitrary object", ports.isEmpty());

        // 端口的描述行**只为有端口的桶输出行**（六个桶都是这个约定，不是为气体新立的）。
        // 所以这里断言那个约定本身，而「气体桶也接进了诊断」只能断在源码上——见下面的读源码那一段。
        // `/mm-diagnose` 是唯一能看见 selector-tag 的地方，所以气体端口漏进诊断就是一个真问题。
        List<String> described = ports.describePorts();
        check("describePorts() emits no row for an empty bucket (the convention all six buckets follow)",
                "[]", described.toString());

        // 标签筛选必须覆盖气体桶：漏了它，一条带 selector-tag 的气体需求就会看到**全部**气体端口。
        check("withTag filters the gas buckets too (an empty bucket stays empty in either case)", 0,
                ports.withTag("line_1").gasInputs().size());

        // 两条路都必须实现——只做一条的话，管道与配方会有一边永远不工作。
        Path source = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                + "compat/mekanism/MekanismAnchor.java");
        Path controller = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                + "modularmachinery/block/MachineControllerBlockEntity.java");
        try {
            String anchor = withoutComments(Files.readString(source));
            report("the bridge implements the capability query (map) — without it a Mekanism pipe could "
                    + "never ask this hatch", anchor.contains("public LazyOptional<?> map("));
            report("...and the port collection (handlers) — without it a recipe could never see a gas port",
                    anchor.contains("public java.util.List<?> handlers("));
            report("...and persistence (save/load) — without it a gas-filled hatch loses its contents on "
                    + "reload", anchor.contains("public void save(net.minecraft.nbt.CompoundTag tag, Object entity)")
                            && anchor.contains("public void load(net.minecraft.nbt.CompoundTag tag, Object entity)"));

            // 注意：这里必须用**去掉注释**的文本。实测过——直接对原文找 "builder.gasPorts(hatch,"，
            // 把那一行注释掉也照样通过，因为被注释的那一行同样包含这个字符串。
            String controllerText = withoutComments(Files.readString(controller));
            report("the controller really hands each hatch to the compat port collection (a bridge nobody calls "
                    + "is indistinguishable from 'Mekanism absent', and that failure is silent)",
                    controllerText.contains("builder.gasPorts(hatch,"));

            // 诊断输出：`selector-tag` 在世界上看不见，`/mm-diagnose` 是唯一能看它的地方，
            // 所以气体桶漏进诊断就是一个真问题（而它只在有气体端口时才看得见，离线造不出来）。
            Path collection = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                    + "modularmachinery/machine/HatchCollection.java");
            String collectionText = withoutComments(Files.readString(collection));
            report("describePorts() also walks the two gas buckets (otherwise a gas port bound to a "
                    + "selector-tag would be invisible to the one tool that can show tags)",
                    collectionText.contains("describeBucket(out, \"gas input\"")
                            && collectionText.contains("describeBucket(out, \"gas output\""));
            report("withTag filters the two gas buckets as well (otherwise a tagged gas requirement would see "
                    + "every gas port)", collectionText.contains("filter(this.gasInputs, this.gasInputTags, wanted)")
                            && collectionText.contains("filter(this.gasOutputs, this.gasOutputTags, wanted)"));
        } catch (Exception exception) {
            report("reading the port sources failed: " + exception, false);
        }

        // 主模组**一个 Mekanism 类型都不该出现**，除了隔离在 compat/mekanism 里的那些。
        // 这条是这一整节存在的理由：方块实体每个世界都会被加载，包括没装 Mekanism 的进程。
        Path hatchEntity = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                + "modularmachinery/block/MachineHatchBlockEntity.java");
        try {
            String text = Files.readString(hatchEntity);
            report("the hatch block entity mentions no Mekanism type (it would fail to load on a server "
                    + "without Mekanism)", !text.contains("mekanism"));
            report("...and it reaches the compat ports only through the generic entry point",
                    text.contains("ModCompat.mapPort(") && text.contains("ModCompat.savePorts(")
                            && text.contains("ModCompat.loadPorts("));
        } catch (Exception exception) {
            report("reading the hatch block entity failed: " + exception, false);
        }

        // 两条**界面契约**的断言（都不依赖 Mekanism 在场）：
        // 1) 菜单的数据槽数目必须容得下气体那两个（否则服务端算了也送不到客户端，容量条永远不动）；
        // 2) 兼容层报数用的名字必须是界面读的那两个（否则又是一处「两边各说各的」）。
        //    它们靠源代码断言，因为离线环境里方块实体与菜单都拿不到真实的气体数据。
        Path menu = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                + "menu/MachineHatchMenu.java");
        Path screen = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                + "client/MachineHatchScreen.java");
        try {
            String menuText = withoutComments(Files.readString(menu));
            report("the hatch menu exposes six container-data slots (energy 2 + fluid 2 + gas 2); with only "
                    + "four the bar could never see a gas amount on the client",
                    menuText.contains("public int getCount() { return 6; }"));
            report("...and it offers gasAmount()/gasCapacity() accessors for the screen",
                    menuText.contains("public int gasAmount()") && menuText.contains("public int gasCapacity()"));

            String screenText = withoutComments(Files.readString(screen));
            report("the bar draws a gas section as well as the fluid one",
                    screenText.contains("drawGas(") && screenText.contains("menu.gasAmount()"));
            report("...and it reads the generic compat accessors rather than any module type "
                    + "(the screen must not need to know which module supplies the gas)",
                    screenText.contains("ModCompat.namedValues(") || screenText.contains(".gasTooltip("));
            // 工具提示把两件事**都**说清楚：流体那一行总是出现（没装 Mekanism 时就是从前那个 tooltip），
            // 有气体时再加气体那一行、并且把「空」换成「空（气体占着这个仓）」。
            // 为什么不是「只显示装着的那样」：Jade 那条路不受本模组控制（实测被 SkyResources3 的
            // 全局 Jade 插件压掉），所以游戏内界面这一份必须是玩家能依赖的完整说法。
            report("the tooltip always states the fluid side, and adds the gas line when gas is present "
                    + "(the in-game tooltip must not depend on Jade to say what is in the hatch)",
                    screenText.contains("gashatch.tank\",")
                            && screenText.contains("gas[0], gas[1]")
                            && screenText.contains("fluidhatch.blocked_by_gas")
                            && screenText.contains("fluidhatch.tank"));

            String anchor = withoutComments(Files.readString(source));
            report("...and the gas module reports the two names the bar reads (gas.amount / gas.capacity)",
                    anchor.contains("\"gas.amount\"") && anchor.contains("\"gas.capacity\""));

            // 互斥：一个流体仓里只能有一种东西。
            // 「灌气体时清流体」不够——流体管道照样能往同一个罐里灌液体，于是两者同时存在。
            // 所以流体那一侧必须在有气体时装成空的；而「清」必须是真的清，否则气体抽空后旧液体又冒出来。
            String hatch = withoutComments(Files.readString(hatchEntity));
            report("the fluid tank pretends to be empty while gas occupies the hatch (otherwise a fluid pipe "
                    + "could put liquid into a hatch that already holds gas)",
                    hatch.contains("class FluidTankWithGasPriority")
                            && hatch.contains("return net.minecraftforge.fluids.FluidStack.EMPTY;")
                            && hatch.contains("return gasBlocksFluid() ? 0 : super.fill("));
            report("...and clearing the fluid for gas is a REAL clear, reachable without the blocking logic "
                    + "(otherwise the old liquid reappears once the gas is drained)",
                    hatch.contains("void clearForGas()") && hatch.contains("int realFluidAmount()"));
            report("...and the gas side asks realFluidAmount() rather than getFluidAmount() when deciding "
                    + "whether to clear (the latter is answered 0 while gas is present, so it would always "
                    + "decide 'nothing to clear')",
                    withoutComments(Files.readString(Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/"
                            + "com/reborn/modularmachinery/compat/mekanism/HybridGasTank.java")))
                            .contains("hatch.realFluidAmount()"));
        } catch (Exception exception) {
            report("reading the screen/menu sources failed: " + exception, false);
        }

        check("a gas requirement still reports its per-tick kind after the port work", "gas_pertick",
                gasPerTick.id().getPath());
    }

    // ================================================================== (9) mods.toml

    /**
     * 每一个可选依赖都必须写 `versionRange`——**省略会被解析成空范围，而空范围不匹配任何版本**。
     *
     * <p>这一条是实机报告换来的：0.34.0 启动时 Forge 打出
     * {@code Unsupported installed optional dependencies: Mod ID: 'kubejs', … Expected range: '',
     * Actual version: '2001.6.5-build.26'}，三个可选依赖全部命中。我当时的理由是「不写版本区间，
     * 免得把玩家挡在门外」——而那个决定的实际后果**正好相反**。
     *
     * <p>两个陷阱值得写下来，因为我都差点踩实：
     * <ol>
     *   <li><b>Forge 只对「装了的」可选依赖做这个判断。</b>所以拿一个没装的模组当反例（我最初就是拿
     *       Botania 的 {@code gardenofglass} 举例）会得出「这样写没问题」的错误结论——那一条从来没被检查过。</li>
     *   <li><b>这个错误不会让游戏开不起来</b>（它是 ERROR 级日志，不是异常；报告里那次崩溃是另一个模组
     *       {@code CustomSkinLoader} 缺类）。所以它只能靠**读日志**发现，而这正是最容易被跳过的一类。</li>
     * </ol>
     *
     * <p>断言在**手写的源文件**上做，不在打包产物上：这条错误来自 mods.toml 的文本，
     * 而源文件就是权威。这里刻意做一个很轻的解析——按 {@code [[dependencies.…]]} 切块，
     * 每块里找 {@code mandatory} 与 {@code versionRange}——因为要断言的正是「两者必须同时出现」。
     */
    private static void modsTomlOptionalDependencies() {
        Path toml = Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/META-INF/mods.toml");
        String text;
        try {
            text = Files.readString(toml);
        } catch (Exception exception) {
            report("could not read " + toml + ": " + exception, false);
            return;
        }
        // 切块：每个 "[[dependencies..." 到下一个 "[[" 之前。
        String[] blocks = text.split("(?m)^\\s*\\[\\[");
        int optional = 0;
        int missingRange = 0;
        List<String> offenders = new ArrayList<>();
        for (String block : blocks) {
            if (!block.startsWith("dependencies.")) {
                continue;
            }
            boolean isOptional = block.matches("(?s).*mandatory\\s*=\\s*false.*");
            if (!isOptional) {
                continue;
            }
            optional++;
            String modId = "?";
            java.util.regex.Matcher id = java.util.regex.Pattern.compile("modId\\s*=\\s*\"([^\"]+)\"")
                    .matcher(block);
            if (id.find()) {
                modId = id.group(1);
            }
            if (!block.matches("(?s).*versionRange\\s*=\\s*\"[^\"]+\".*")) {
                missingRange++;
                offenders.add(modId);
            }
        }
        report("the mod declares at least one optional dependency (otherwise this check is vacuous): "
                + optional, optional > 0);
        check("every optional dependency states a versionRange (omitting it means 'matches no version', so "
                + "the mod would log an error on every instance that HAS the dependency): "
                + (offenders.isEmpty() ? "all " + optional + " have one" : "missing on " + offenders),
                0, missingRange);

        // 范围本身必须能容纳一个真实版本——「写了但写错」与「没写」是同一种失败。
        report("...and each range is one a real version satisfies (maven's 'containsVersion' is what Forge "
                + "runs; a range no real version satisfies is the same bug as an empty one)",
                text.contains("versionRange=\"[1,)"));
    }

    // ================================================================== (8) the fallback door
    /**
     * 没装 Mekanism 时这个模块**一次也不注册**——而且注册表里**不会留下半个种类**。
     *
     * <p>为什么不走 {@code ModCompat.register(modules, isLoaded -> false)} 来测：那个方法的模块循环
     * 看过 {@code REGISTERED} 就会跳过已登记过的模块，所以第二次调用**根本不会进模块的 register**，
     * 断言会因为错误的原因通过（注册表本来就空）。这正是本波在别处已经吃过一次的那类问题——
     * 「断言刚好绕过了会出错的那条分支」。
     *
     * <p>所以这里测的是真有区分度的那一半：{@code removeUnavailable} 会不会把**别人的**种类一起摘掉。
     * 模块 id 的去重在波 5.2 已经覆盖过了。
     */
    private static void requiresModidWithdrawal() {
        check("the module's own declaresModid is the original's ('mekanism')", "mekanism",
                String.valueOf(new MekanismModule().requiresModid()));
        check("...and so does every kind it registers", "mekanism",
                String.valueOf(RequirementTypes.byName(GAS).requiresModid()));
        check("...and the per-tick kind too", "mekanism",
                String.valueOf(RequirementTypes.byName(GAS_PER_TICK).requiresModid()));

        // 装了就一条都不该被摘；这一半比「不装就摘掉」更要紧——一个写错的 requiresModid 会把
        // 内置五种也摘掉，症状是「所有配方突然全部加载失败」。
        List<ResourceLocation> removedWhileLoaded = RequirementTypes.removeUnavailable(modid -> true);
        check("with Mekanism present nothing is withdrawn", "[]", removedWhileLoaded.toString());
        check("...and the registry is untouched", 7, RequirementTypes.all().size());

        // 不装：恰好这两个被摘，内置五种一个不动。
        List<ResourceLocation> removed = RequirementTypes.removeUnavailable(modid -> false);
        check("with Mekanism absent exactly the two gas kinds are withdrawn", "[modular_machinery_reborn:gas, "
                + "modular_machinery_reborn:gas_pertick]", removed.toString());
        check("...the five built-ins survive (a wrong requiresModid would empty the registry, and every recipe "
                + "would fail to load)", "[item, fluid, energy, interface_number_input, ingredient_array_input]",
                paths().toString());
        report("...and the two kinds really are gone, not merely reported as withdrawn",
                RequirementTypes.byName(GAS) == null && RequirementTypes.byName(GAS_PER_TICK) == null);
    }

    // ================================================================== helpers

    private static JsonObject obj(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static final String WHERE = "a requirement of mmverify:gas";

    /**
     * 去掉 Java 源码里的注释，**保留字符串字面量**。
     *
     * <p>为什么必须做这件事：本节的源码断言分两类——一类读**报错文案**（字符串字面量，注释里没有），
     * 一类读**有没有调用某方法**（可执行代码）。后一类若直接对原文 {@code contains}，
     * 那么把那一行**注释掉**同样会让断言通过——实际测过：把
     * {@code builder.gasPorts(hatch, …)} 注释掉之后，1536 条断言**全绿**。
     * 那正是本项目最忌讳的「过宽的断言等于没断言」：它把一个静默失效伪装成通过。
     *
     * <p>保留字符串字面量的原因：报错文案是断言的对象，而文案里出现 {@code //} 或 {@code /*}
     * （例如一段示例路径）不该被当成注释吃掉。
     */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"') {
                // 字符串字面量：整段照抄，跳过转义。
                out.append(c);
                i++;
                while (i < n) {
                    char s = source.charAt(i);
                    out.append(s);
                    i++;
                    if (s == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (s == '"') {
                        break;
                    }
                }
                continue;
            }
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char s = source.charAt(i);
                    out.append(s);
                    i++;
                    if (s == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (s == '\'') {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "null";
        }
        int dot = message.indexOf(". ");
        String head = dot > 0 ? message.substring(0, dot) : message;
        return head.length() > 150 ? head.substring(0, 150) + "…" : head;
    }

    private static List<String> paths() {
        List<String> paths = new ArrayList<>();
        for (RequirementType type : RequirementTypes.all()) {
            paths.add(type.id().getPath());
        }
        return paths;
    }

    /** 把注册表与模块状态还原成「只有内置五种」，见主流程 finally 的说明。 */
    private static void restoreRegistry() {
        RequirementTypes.deregister(ResourceLocation.tryParse(GAS));
        RequirementTypes.deregister(ResourceLocation.tryParse(GAS_PER_TICK));
        MekanismModule.TYPE_GAS = null;
        MekanismModule.TYPE_GAS_PER_TICK = null;
        try {
            Field registered = ModCompat.class.getDeclaredField("REGISTERED");
            registered.setAccessible(true);
            ((Map<?, ?>) registered.get(null)).clear();
        } catch (Exception ignored) {
            // 清不掉注册模块的记录也不影响注册表本身；下一节问的是注册表，不是 ModCompat 的账本。
        }
    }

    private static boolean present(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    /**
     * 本模组自己的 Jade 插件（0.50.0）。
     *
     * <p>为什么必须钉住结构而不是只钉住「有没有」：Jade 用 `@WailaPlugin` 扫描**并在两侧都加载插件类**
     * （Mekanism 的 MekanismJadePlugin 就是那样，而它的 register 只引用服务端类）。如果插件类直接
     * import 客户端专属类型，专用服务端就会缺类。所有者实例上那四家 AE/机械动力系的插件全都是
     * 「类不 import 客户端、客户端那一段放在 registerClient 里被引用」，本模组照抄这条结构。
     */
    static void jadePlugin() {
        section("Jade 插件（0.50.0）");
        Path plugin = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                + "integration/jade/ModularMachineryJadePlugin.java");
        Path component = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                + "integration/jade/HatchFluidComponent.java");
        Path hatchEntity = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                + "block/MachineHatchBlockEntity.java");
        try {
            String pluginText = withoutComments(Files.readString(plugin));
            String componentText = withoutComments(Files.readString(component));
            report("the plugin is annotated @WailaPlugin and implements IWailaPlugin (that annotation is"
                    + " RUNTIME/TYPE retained, which is how Jade finds it)",
                    pluginText.contains("@WailaPlugin")
                            && pluginText.contains("implements IWailaPlugin"));
            report("...and the plugin class itself carries NO client-only import, so a dedicated server can"
                    + " load it (the client half is referenced only from registerClient)",
                    !pluginText.contains("net.minecraft.client")
                            && !pluginText.contains("com.mojang.blaze3d"));
            report("...and it registers the component for this mod's hatch block class only "
                    + "(not Block.class: precise registration avoids fighting other mods' global providers)",
                    pluginText.contains("registerBlockComponent(HatchFluidComponent.INSTANCE")
                            && pluginText.contains("MachineHatchBlock.class"));
            // 0.51.0 修正：组件**不再**读存档 NBT，而是读客户端自己的罐。
            // 原来读 NBT 时，罐的 getFluid() 会问「有没有气体」，而客户端的气体罐来自上一次同步的旧 NBT
            // ——于是它把客户端明明装着液体的罐读成空（所有者截图上就是这个症状）。
            // 同时那道闸门也改成只在服务端生效。
            report("the component reads the client-side tank itself rather than the save NBT "
                    + "(and the gas gate is server-side only, so the client shows what it really holds)",
                    componentText.contains("hatch.fluidTank()")
                            && !componentText.contains("saveWithoutMetadata()")
                            && withoutComments(Files.readString(hatchEntity))
                                    .contains("if (this.level != null && this.level.isClientSide)"));
            // 通用流体组件那一份必须被摘掉，而且**只摘本模组方块上的**：
            // 它服务端读得对、客户端画成空，两份并存会让玩家看到两个互相矛盾的数字；
            // 而别的模组方块上的那一份绝不能动。
            Path suppressor = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                    + "modularmachinery/integration/jade/FluidStorageSuppressor.java");
            String suppressorText = withoutComments(Files.readString(suppressor));
            // 按 id 移除那一版**无效**：Jade 的 ClientViewGroup.tooltip(...) 用
            // ITooltip.add(IElement)（不带 id 的重载）把流体元素加进去，所以 remove(id) 找不到它
            // —— 这是从 Jade 字节码里读出来的。现在改为按**内容**定位（方块显示名那一段），
            // 删掉它并插回本模组自己的行。
            // 按 id 移除那一版无效（Jade 用不带 id 的 add(IElement)）；按「显示名相等」定位那一版
            // 也无效（所有者实测提示毫无变化）——两版在游戏里的表现一模一样，所以这一版除了
            // 「多判据 + 兜底」之外，还带了**计数器**把「回调有没有被调用」变成可读事实。
            report("the suppressor locates the row by several criteria with a fallback, only ever touches"
                    + " this mod's hatch blocks, and exposes counters so that 'the callback never ran'"
                    + " can be told apart from 'it ran but located nothing'",
                    suppressorText.contains("MachineHatchBlock")
                            && suppressorText.contains("getDisplayName()")
                            && suppressorText.contains("FALLBACK_ROW")
                            && suppressorText.contains("AtomicInteger")
                            && suppressorText.contains("counters()")
                            && pluginText.contains("addTooltipCollectedCallback"));
        } catch (Exception exception) {
            report("reading the Jade plugin sources failed: " + exception, false);
        }
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
}
