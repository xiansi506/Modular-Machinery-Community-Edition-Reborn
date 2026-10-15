package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.compat.CompatModule;
import com.reborn.modularmachinery.compat.ModCompat;
import com.reborn.modularmachinery.compat.mekanism.MekanismModule;
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
            // 摘除那一段必须最后跑：它把两个种类从注册表里拿走（没装 Mekanism 的方向）。
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
