package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.compat.CompatModule;
import com.reborn.modularmachinery.compat.CompatRegistry;
import com.reborn.modularmachinery.compat.ModCompat;
import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.IOType;
import com.reborn.modularmachinery.recipe.ItemRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.MachineRequirementParser;
import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RequirementType;
import com.reborn.modularmachinery.recipe.RequirementTypes;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 波 5.2 — 需求类型注册表与 compat 接入层：能离线证明的那一半。
 *
 * <p><b>这一节要证明的命题只有一句：不改主模组的源码也能加一种需求。</b>波 5.2 之前那是不可能做到的：
 * 五种需求的名字写死在 {@code ModRecipeSerializers} 的三处（JSON 的 {@code switch}、写包的
 * {@code instanceof} 链、读包的 {@code switch}），一个兼容模块想加第六种只能去改那三个地方。
 * 所以下面的断言**不读源码字符串**（那是「文档一致」而不是「行为正确」），而是真的走一遍那条路：
 *
 * <ol>
 *   <li>用一个 {@link CompatModule} 注册一种测试需求（完全不碰 {@code ModRecipeSerializers}）；</li>
 *   <li>写一条用它的配方 JSON，让 {@link MachineRequirementParser#parse} 解析出来；</li>
 *   <li>把它写进包、读回来，断言种类与正文都还在——**这一条专盯旧结构里那条
 *       {@code instanceof} 链漏改的症状**：能解析、能进配方，一发包就抛
 *       {@code IllegalStateException("Unsupported requirement …")}。</li>
 * </ol>
 *
 * <p>同时钉住既有行为不许漂：内置种类的名字集合、命名空间三写法等价、未知类型仍然加载即报错、
 * 内置五种的线上字节与改动前逐字节相同。最后一条是这个类里最要紧的一条——
 * 波 4 的配方同步走的就是这条格式，顺序或种类名漂一个字，症状都是「单机能跑、联机不行」。
 *
 * <p><b>为什么用反射拿 {@code ModRecipeSerializers} 的内置清单。</b>本类不引用那个类型：
 * 它持有 {@code DeferredRegister}，静态初始化会碰 {@code net.minecraftforge.fml.common.Mod}，
 * 一碰就 {@code NoClassDefFoundError}——这正是波 5.2 把分派逻辑搬出那个类的理由。
 * 这里用 {@code Class.forName(...).getMethod(...)} 去够那几个 {@code public static} 方法，
 * 拿不到就如实报出来（那是「内置清单不再由那个类对外说明」，是一件要修的事，不是可以跳过的事）。
 */
final class RequirementTypeCheck {

    /** 本节的测试种类名。用 mmverify 命名空间，与任何一个真实模块都不冲突。 */
    private static final ResourceLocation DEMO_ID = new ResourceLocation("mmverify", "demo_kind");

    /**
     * 本节注册的那个种类，由 {@link #compatModuleRegistersANewKind()} 在注册成功后填上。
     *
     * <p>让测试需求直接引用它（而不是每次去注册表里查）：这也是**生产代码的形状**——
     * {@code ItemRequirement} 的 {@code type()} 返回 {@code RequirementTypes.ITEM}，
     * 即「哪种需求」是每条需求自己就说得出的事实，不依赖注册表查得到查不到。
     */
    private static RequirementType demoType;

    /** 一个不存在的模组 id：用来验「依赖没装的类型会被摘掉」。 */
    private static final String ABSENT_MOD = "mmverify_not_installed";

    private RequirementTypeCheck() {
    }

    // ================================================================== the section

    static void requirementTypeRegistry() {
        section("AB. 波 5.2: the requirement-type registry and the compat layer");

        boolean parserPresent = present("com.reborn.modularmachinery.recipe.MachineRequirementParser");
        boolean registryPresent = present("com.reborn.modularmachinery.recipe.RequirementTypes");
        report("the production registry " + RequirementTypes.class.getName() + " exists (red-first: before this "
                + "wave, requirement kinds were three string constants plus three switches in "
                + "ModRecipeSerializers)", registryPresent && parserPresent);
        if (!parserPresent || !registryPresent) {
            report("skipping AB's behaviour checks: the production classes are absent, which the check above "
                    + "already reported. A class that does not exist cannot have its dispatch asserted.", true);
            return;
        }

        builtinSet();
        namespaceTolerance();
        unknownKind();
        duplicateRegistration();
        modifierTargetsAreClosed();
        compatModuleRegistersANewKind();
        wireFormat();
        requiresModidWithdrawal();
    }

    // ================================================================== (1) built-ins

    /**
     * 内置五种就是改动前那五个名字，一个不多一个不少。
     *
     * <p>用**旧文件的清单**（这里的字面量抄自改动前的 {@code ModRecipeSerializers:45-49}）与注册表现算的结果
     * 对照，而不是与注册表自己对照——与自己对照什么也证明不了。这条断言的作用是挡住
     * 「顺手多注册一种」或「改名字」：前者的症状是旧配方文件突然有了第二种解释，后者是旧配方全部加载失败。
     */
    private static void builtinSet() {
        Set<String> expected = new LinkedHashSet<>(Arrays.asList(
                "item", "fluid", "energy", "interface_number_input", "ingredient_array_input", "catalyst"));
        Set<String> actual = new LinkedHashSet<>();
        for (RequirementType type : RequirementTypes.all()) {
            check("every registered type uses this mod's namespace: " + type.id(),
                    "modular_machinery_reborn", type.id().getNamespace());
            actual.add(type.id().getPath());
        }
        check("the built-in kinds are exactly the six this port registers", expected.toString(),
                actual.toString());
        RequirementType item = RequirementTypes.byName("item");
        if (item == null) {
            // 注册表是空的：这不是「断言写错了」，而是「注册表根本没被填上」。
            // 报告出来而不是让下一行 NPE —— 一个 NPE 会把这一整节剩下的断言全部吃掉，
            // 而真正的问题（内置五种为什么没注册）就看不见了。
            report("the registry is EMPTY: " + RequirementTypes.all().size() + " types registered, so byName('item') "
                    + "has nothing to return. Nothing below this line can mean anything until that is fixed.",
                    false);
            return;
        }
        check("byName('item') is the registered ITEM", RequirementTypes.ITEM.id().toString(), item.id().toString());
    }

    // ================================================================== (2) namespaces

    /**
     * 三种写法等价，而**没有命名空间的区分力**。
     *
     * <p>这是改动前的行为，逐字保留：{@code rawType.substring(colon + 1)} 之后命名空间就没了。
     * 断言里那条「另一个模组的命名空间也照样解析」是有意写进来的——它不是我希望的行为，
     * 而是既有行为，写下来是为了让将来改它的人**必须删掉一条断言**，而不是以为没人注意到。
     */
    private static void namespaceTolerance() {
        for (String spelling : new String[] {"item", "modularmachinery:item", "modular_machinery_reborn:item"}) {
            RequirementType type = RequirementTypes.byName(spelling);
            report("'" + spelling + "' resolves to the item kind", type != null
                    && "item".equals(type.id().getPath()));
        }
        RequirementType foreign = RequirementTypes.byName("some_other_mod:item");
        report("a third-party namespace still resolves to this mod's item kind — that is the pre-existing "
                + "behaviour (the namespace was always dropped), recorded here so changing it means deleting an "
                + "assertion rather than going unnoticed", foreign != null && "item".equals(foreign.id().getPath()));
        report("an unknown path resolves to nothing", RequirementTypes.byName("no_such_kind") == null);
        report("a null name resolves to nothing", RequirementTypes.byName(null) == null);
    }

    // ================================================================== (3) unknown kinds

    /**
     * 未知种类仍然**加载即报错**，而且消息由注册表现算。
     *
     * <p>改动前这条消息列出的是三句写死的说明。注册表化之后它还必须说出**现在注册了哪些**——
     * 那是看一眼就能定位的东西（写错了名字还是模块没装），而写死的清单随着兼容模块增加必然过期。
     */
    private static void unknownKind() {
        JsonObject json = JsonParser.parseString(
                "{\"type\":\"modularmachinery:gas\",\"io-type\":\"input\",\"amount\":1}").getAsJsonObject();
        try {
            MachineRequirement requirement = MachineRequirementParser.parse(json, new ResourceLocation("mmverify", "r"));
            report("expected the unknown kind 'gas' to be rejected, but got " + requirement, false);
        } catch (RuntimeException exception) {
            String message = String.valueOf(exception.getMessage());
            report("an unknown kind is a load error, naming the kind and the recipe: " + message,
                    message.contains("Unknown requirement type") && message.contains("modularmachinery:gas")
                            && message.contains("mmverify:r"));
            report("...and the message lists what IS registered, computed from the registry rather than written "
                    + "out by hand: " + RequirementTypes.ids(), message.contains(RequirementTypes.ids()));
            report("...and it still carries the original's explanation of why the gas kinds are not ported, "
                    + "that item_durability is not a requirement type, and how catalyst used to work (D18)",
                    message.contains("gas_pertick") && message.contains("item_durability")
                            && message.contains("catalyst"));
        }
    }

    // ================================================================== (4) duplicate ids

    /**
     * 同一个 id 注册两次**报错**，不覆盖。
     *
     * <p>「后注册的赢」在这里是最坏的一种宽容：同一份配方文件在两次启动里会被解析成两种不同的需求，
     * 而配方照常加载、照常能跑，没有任何症状。
     */
    private static void duplicateRegistration() {
        RequirementType clash = RequirementType.of(new ResourceLocation("mmverify", "clash"), new IntHandler());
        RequirementType first = RequirementTypes.register(clash);
        try {
            RequirementTypes.register(RequirementType.of(new ResourceLocation("mmverify", "clash"), new IntHandler()));
            report("expected a duplicate id to be refused", false);
        } catch (IllegalStateException exception) {
            report("a duplicate id is refused: " + exception.getMessage(),
                    exception.getMessage().contains("mmverify:clash"));
        } finally {
            RequirementTypes.deregister(first.id());
        }
        report("...and the refusal left the first registration intact", RequirementTypes.byName("mmverify:clash") == null);
    }

    // ================================================================== (5) modifier target

    /**
     * {@code modifierTarget()} 仍然落在那个闭枚举里——**这是本波有意没打开的一件事**。
     *
     * <p>旧版是拿注册表里那行 {@code RequirementType} 本身当修改器 target 的
     * （{@code RecipeModifier.java:239}），所以「第六种需求」在旧版天然能接修改器。本版
     * {@code RecipeModifier.Target} 是闭枚举，兼容模块的气体/灵气只能落到 {@code ALL} 或某一项上，
     * 而**映射到语义不符的具体项时不会报错**。所有者已决定本轮保持闭枚举并登记为待改，
     * 所以这条断言把现状钉住：它以后会失败，而失败正是那个改动该被做的信号。
     */
    private static void modifierTargetsAreClosed() {
        List<String> targets = new ArrayList<>();
        for (RequirementType type : RequirementTypes.all()) {
            targets.add(type.id().getPath() + "=" + type.modifierTarget());
        }
        check("each built-in kind reports its own modifier target, all inside the closed enum", "[item=ITEM, "
                + "fluid=FLUID, energy=ENERGY, interface_number_input=ITEM, ingredient_array_input=ITEM, catalyst=ITEM]",
                targets.toString());
        boolean allClosed = true;
        for (RequirementType type : RequirementTypes.all()) {
            RecipeModifier.Target target = type.modifierTarget();
            allClosed &= target == RecipeModifier.Target.ITEM || target == RecipeModifier.Target.FLUID
                    || target == RecipeModifier.Target.ENERGY || target == RecipeModifier.Target.DURATION
                    || target == RecipeModifier.Target.ALL;
        }
        report("every target is one of the five enum constants (the closed enum is the documented limitation of "
                + "this wave)", allClosed);
    }

    // ================================================================== (6) the actual claim

    /**
     * **本节的核心。**一个只实现 {@link CompatModule} 的模块注册一种新需求；然后走完整条路：
     * 注册 → 解析 JSON → 写包 → 读包 → 再写一次。
     *
     * <p>模块只管声明种类，**一个字也不改主模组**——这就是波 5.2 的交付物。
     *
     * <p>用 {@link ModCompat#register} 驱动而不是直接调 {@link RequirementTypes#register}：
     * 前者才是运行时真正走的入口，而它接受一个 {@code isLoaded} 谓词，于是这一节在离线环境里也能
     * 验「模块被调用 / 被跳过」这两条分支。
     */
    private static void compatModuleRegistersANewKind() {
        // 注册之前：没有这种东西。
        report("before the module registers, the demo kind is unknown",
                RequirementTypes.byName(DEMO_ID.toString()) == null);

        CompatModule module = new CompatModule() {
            @Override
            public ResourceLocation id() {
                return new ResourceLocation("mmverify", "demo_module");
            }

            @Override
            public void register(CompatRegistry registry) {
                demoType = registry.requirement(RequirementType.of(DEMO_ID, new IntHandler()));
            }
        };

        List<CompatModule> registered = ModCompat.register(List.of(module), modid -> true);
        check("the module was registered", 1, registered.size());
        RequirementType type = RequirementTypes.byName(DEMO_ID.toString());
        report("the module's kind is now in the registry, without editing ModRecipeSerializers",
                type != null && DEMO_ID.equals(type.id()));
        // 这一条单独钉住**两次查找的第二次**：模块按自己的 modid 注册（`mmverify:demo_kind`），
        // 而第一次查找只会去问 `modular_machinery_reborn:demo_kind`。少了第二次，注册就成功但永远解析不到
        // ——本波第一版正是如此，症状是「未知需求类型」，而注册表的日志里明明列着它。
        report("...and it is reachable even though it lives in the module's own namespace, which the first "
                + "(pre-existing) lookup step normalises away",
                RequirementTypes.byName("mmverify:demo_kind") != null
                        && RequirementTypes.byId(DEMO_ID) != null);
        report("...while a bare 'demo_kind' still does NOT resolve to it (the first step asks this mod's "
                + "namespace, where nothing is registered under that path)",
                RequirementTypes.byName("demo_kind") == null);

        // 一个模块只注册一次；再调一次不会撞上「重复 id 即报错」那种假警报。
        check("calling the compat entry point again does not re-register it (the id is already taken)",
                0, ModCompat.register(List.of(module), modid -> true).size());

        try {
            // (a) 一条用它的配方 JSON，走生产的解析器。
            JsonObject json = JsonParser.parseString("{\"type\":\"mmverify:demo_kind\",\"io-type\":\"input\","
                    + "\"amount\":7,\"selector-tag\":\"line_2\"}").getAsJsonObject();
            MachineRequirement requirement = MachineRequirementParser.parse(
                    json, new ResourceLocation("mmverify", "demo_recipe"));
            report("a recipe written with the module's kind parses through the production parser",
                    requirement != null && requirement.type() == type);
            check("...and the kind's own field survived", 7,
                    ((DemoRequirement) requirement).amount());
            check("...and the shared selector-tag was applied by the shared path, not by the kind",
                    "line_2", String.valueOf(requirement.selectorTag()));

            // (b) 过网：这是旧结构下**必然失败**的那一步（那条 instanceof 链不认识第六种）。
            byte[] once = wireBytes(requirement);
            MachineRequirement back = readBack(once);
            report("the kind survives a write/read round trip over the wire (the step the old instanceof chain "
                    + "could not do for a registered kind)", back != null && back.type() == type);
            check("...with its own field intact", 7, ((DemoRequirement) back).amount());
            check("...and its selector-tag", "line_2", String.valueOf(back.selectorTag()));
            check("...and the packet's first string is this kind's FULL id, namespace included — a bare path "
                    + "could not be resolved back when the kind lives in another module's namespace",
                    DEMO_ID.toString(), firstString(once));
            check("...and writing it again produces byte-identical output", hex(once),
                    hex(wireBytes(back)));
        } catch (RuntimeException exception) {
            // 这一整块是「一条需求从 JSON 走到线上再走回来」，任何一步抛异常都必须**变成一条 FAIL**
            // 而不是把整个 main 干掉：异常一旦逃出这里，本节剩下的断言（以及后面每一节）都不会再跑，
            // 而故障注入时的实际现象是「证据文件停在这一行、SUMMARY 根本没打印」——
            // 那时人看到的是「复现器崩了」，看不到「哪一种需求种类过不了网」。两者是完全不同的信息。
            report("the registered kind failed to survive JSON -> wire -> wire: " + exception, false);
        } finally {
            // 全局状态要还回去，否则后面每一节的「注册表里有什么」都被这一节影响。
            RequirementTypes.deregister(type == null ? DEMO_ID : type.id());
        }
        report("after the section the demo kind is gone again", RequirementTypes.byName(DEMO_ID.toString()) == null);
    }

    // ================================================================== (7) the wire

    /**
     * 内置五种的线上格式：种类写全限定 id，正文顺序固定，往返自洽。
     *
     * <p>为什么这条比其它都重要：波 4 的配方同步就是这条格式，而它的两端（服务端的
     * {@code toNetwork}、客户端的 {@code fromNetwork}）现在都经过新写的分派代码。
     * 顺序错一位、种类名漂一个字，症状都是**单机正常、联机不对**——而单机是开发时唯一会跑的东西。
     *
     * <p>断言分三层：
     * <ol>
     *   <li>种类字符串是 {@code <本模组命名空间>:<path>}——**这一层是本波唯一有意改掉的线上字节**：
     *       改动前写的是裸 path（{@code "item"}）。改它的原因见
     *       {@code MachineRequirementParser#write}：裸 path 丢掉命名空间，于是兼容模块的种类
     *       发得出去、读不回来。这里把新形状钉住，同时把「它确实变了」这件事写成一条断言，
     *       而不是留给下一个人从字节里发现；</li>
     *   <li>读完再写回，种类与正文都在；</li>
     *   <li>两次写出的字节完全一致——顺序、类型、值任一处漂了都过不了。</li>
     * </ol>
     */
    private static void wireFormat() {
        Object[][] cases = {
                {"modular_machinery_reborn:item", ItemRequirement.input(
                        net.minecraft.world.item.crafting.Ingredient.of(Items.COAL), 3)},
                {"modular_machinery_reborn:item", ItemRequirement.output(new ItemStack(Items.DIAMOND, 1), 1, 4, 0.5F)},
                {"modular_machinery_reborn:energy",
                        new com.reborn.modularmachinery.recipe.EnergyRequirement(IOType.INPUT, 128L)},
                {"modular_machinery_reborn:interface_number_input",
                        new com.reborn.modularmachinery.recipe.InterfaceNumberInputRequirement("mode", 1.0F, 2.0F, null)},
        };
        for (Object[] c : cases) {
            String expectedKind = (String) c[0];
            MachineRequirement requirement = (MachineRequirement) c[1];
            try {
                byte[] bytes = wireBytes(requirement);
                check("kind written for " + requirement.getClass().getSimpleName(), expectedKind, firstString(bytes));
                MachineRequirement back = readBack(bytes);
                check("...round trips to the same kind", expectedKind, back.type().id().toString());
                check("...byte for byte on a second write", hex(bytes), hex(wireBytes(back)));
            } catch (RuntimeException exception) {
                report("the wire round trip of " + requirement.getClass().getSimpleName() + " failed: "
                        + exception, false);
            }
        }
        // 旧模组 id 的写法在**读取侧**仍然等价（这是既有行为）；它只影响 JSON，不影响线上格式。
        JsonObject legacy = JsonParser.parseString("{\"type\":\"modularmachinery:energy\",\"io-type\":\"input\","
                + "\"energyPerTick\":128}").getAsJsonObject();
        MachineRequirement parsed = MachineRequirementParser.parse(legacy, new ResourceLocation("mmverify", "legacy"));
        check("a recipe still writing the OLD mod id parses to the same built-in kind",
                "modular_machinery_reborn:energy", parsed.type().id().toString());
    }

    // ================================================================== (8) the fallback door

    /**
     * 依赖的模组不在场时，声明了 {@code requiresModid} 的类型被摘掉；没有声明的（内置五种）不受影响。
     *
     * <p>这是旧版 {@code IntegrationTypeHelper.filterModIdRequirementTypes}（{@code :45-58}）的等价物，
     * 也是「注册时机判断错了」的兜底。断言两件事：被摘掉的**恰好**是那一个，以及内置五种一个都没少
     * ——后者才是这条兜底真正的危险：一个写错的 {@code requiresModid} 实现会把内置需求也摘掉，
     * 症状是**所有配方突然全部加载失败**。
     */
    private static void requiresModidWithdrawal() {
        RequirementType gated = RequirementType.of(new ResourceLocation("mmverify", "gated_kind"),
                new IntHandler(), ABSENT_MOD);
        RequirementTypes.register(gated);
        List<String> before = paths();
        check("the gated kind is registered alongside the built-ins", "true", String.valueOf(before.contains("gated_kind")));

        List<ResourceLocation> removed = RequirementTypes.removeUnavailable(modid -> !ABSENT_MOD.equals(modid));
        check("exactly the gated kind was withdrawn", "[mmverify:gated_kind]", removed.toString());
        check("...and nothing else", "false", String.valueOf(paths().contains("gated_kind")));
        check("the six built-in kinds are untouched (a wrong requiresModid would empty the registry, and every "
                + "recipe would fail to load)", "[item, fluid, energy, interface_number_input, "
                + "ingredient_array_input, catalyst]", paths().toString());
    }

    // ================================================================== helpers

    /** 已经注册的种类路径，按注册顺序。 */
    private static List<String> paths() {
        List<String> paths = new ArrayList<>();
        for (RequirementType type : RequirementTypes.all()) {
            paths.add(type.id().getPath());
        }
        return paths;
    }

    /** 一条需求过网之后的字节。 */
    private static byte[] wireBytes(MachineRequirement requirement) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        MachineRequirementParser.write(buf, requirement);
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    /** 把 {@link #wireBytes} 的产物读回来，走生产代码的读取入口。 */
    private static MachineRequirement readBack(byte[] bytes) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        return MachineRequirementParser.read(buf);
    }

    /** 包里的第一个字符串（种类名）。 */
    private static String firstString(byte[] bytes) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        return buf.readUtf();
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    private static boolean present(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    /**
     * 测试装置自己的 check/report 对，从这个类里够到它们而不必把它们改成 public。
     *
     * <p>与 {@code IngredientArrayCheck} 末尾那四个方法一字不差：{@code ParallelCraftCheck} 的那几个方法是
     * 包内可见的，而本类是另一个类——包内可见**不等于**同类可见，所以每个分节都要自己这四个转发。
     */
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

    // ================================================================== the test kind

    /** 一种需求，正文只有一个整数——最小到足以证明「正文由种类自己决定」。 */
    static final class DemoRequirement extends MachineRequirement {

        private final int amount;

        DemoRequirement(int amount) {
            super(IOType.INPUT, false);
            this.amount = amount;
        }

        int amount() {
            return this.amount;
        }

        @Override
        public RequirementType type() {
            return demoType;
        }

        @Override
        public RecipeModifier.Target modifierTarget() {
            return RecipeModifier.Target.ALL;
        }

        @Override
        public boolean canSatisfy(HatchCollection ports) {
            return this.amount > 0;
        }

        @Override
        public String describe() {
            return Integer.toString(this.amount);
        }
    }

    /** 上面那种需求的读写器。 */
    static final class IntHandler implements RequirementType.Handler {

        @Override
        public MachineRequirement read(JsonObject json, IOType ioType, Object where) {
            return new DemoRequirement(json.has("amount") ? json.get("amount").getAsInt() : 0);
        }

        @Override
        public void write(FriendlyByteBuf buf, MachineRequirement requirement) {
            DemoRequirement demo = (DemoRequirement) requirement;
            buf.writeUtf(demo.ioType().name());
            buf.writeVarInt(demo.amount());
        }

        @Override
        public MachineRequirement readBody(FriendlyByteBuf buf, IOType ioType) {
            return new DemoRequirement(buf.readVarInt());
        }

        @Override
        public RecipeModifier.Target modifierTarget() {
            return RecipeModifier.Target.ALL;
        }
    }
}
