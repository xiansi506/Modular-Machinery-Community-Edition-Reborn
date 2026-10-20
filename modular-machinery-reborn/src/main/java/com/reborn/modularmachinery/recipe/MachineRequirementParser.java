package com.reborn.modularmachinery.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 读一条需求、把它写进包、再从包里读回来——配方生命周期里唯一与「需求种类」打交道的地方。
 *
 * <h2>为什么它不留在 {@link ModRecipeSerializers} 里</h2>
 *
 * <p>它原本就在那里，是 {@code MachineSerializer} 的 {@code private static} 方法。搬出来只有一个理由，
 * 而那个理由已经被本项目记过两次：{@code ModRecipeSerializers} 持有
 * {@link net.minecraftforge.registries.DeferredRegister}，它的静态初始化会碰
 * {@code net.minecraftforge.fml.common.Mod}——一个只在 FML 自己类加载器里存在的类。于是离线复现器
 * <b>够不到那个类里的任何东西</b>（{@code InterfaceNumberInputParser:10-22}、{@code IngredientArrayParser:18-24}
 * 的类注释写的就是这件事，它们当初被拆出去就是这个原因）。
 *
 * <p>波 5.2 要证明的核心命题是「**不改主模组的源码也能加一种需求**」。这条命题只有靠
 * 「在复现器里注册一种类型，然后真的解析一条用它的配方」才能证，所以分派逻辑必须住在**能被驱动**的地方。
 * 这里没有任何注册表持有对象、没有 world、没有 client：纯 {@code JsonObject} / {@code FriendlyByteBuf}
 * 进，{@link MachineRequirement} 出，所以复现器驱动的是**生产代码本身**，不是它的副本。
 *
 * <h2>三处调用点与它们的顺序契约</h2>
 *
 * <p>{@link #parse} 是 JSON 入口；{@link #write} / {@link #read} 是线上格式的两端，必须**字段顺序一一对应**，
 * 而它们的顺序由 {@link RequirementType#write} / {@link RequirementType#readBody} 各自决定。
 *
 * <p>{@code selector-tag} 在两头都写在**最外层**（写：整条需求的末尾；读：正文之后），因为它对每种需求含义完全相同——
 * 旧版也把它放在 {@code ComponentRequirement} 上而不是十来个类型里（{@code ComponentRequirement.java:39}）。
 * 把同一条规则写进每个种类，就会有一份迟早分叉的副本。
 */
public final class MachineRequirementParser {

    private MachineRequirementParser() {
    }

    // ------------------------------------------------------------------ JSON

    /**
     * 读一条需求。
     *
     * @param requirementJson {@code requirements} 数组里的一个对象
     * @param recipeId        这条需求属于哪条配方，只出现在报错消息里
     */
    public static MachineRequirement parse(JsonObject requirementJson, ResourceLocation recipeId) {
        String where = "a requirement of " + recipeId;
        String rawType = string(requirementJson, "type", where);
        IOType ioType = IOType.byName(string(requirementJson, "io-type", where));
        RequirementType type = RequirementTypes.byName(rawType);
        if (type == null) {
            throw unknownType(rawType, where);
        }
        MachineRequirement requirement = type.read(requirementJson, ioType, where);
        // `selector-tag`（旧版的同一字段，`MachineRecipe.java:474`）：把这条需求绑定到结构里某个打了标签的
        // 部件，于是它只看得到那个部件上的端口。解析放在这里而不是各类型的 read 里，是因为它对每种需求类型
        // 含义完全相同——把同一条规则写进十来个 parse 分支，就会有一份迟早分叉的副本。
        requirement.setSelectorTag(readSelectorTag(requirementJson, recipeId));
        return requirement;
    }

    /**
     * 配方文件写了一个没有注册过的种类。
     *
     * <p><b>加载即报错，绝不静默跳过</b>——这一点是既有行为，注释里写得很清楚（改动前
     * {@code ModRecipeSerializers:195-199}）：一条需求没被认出来，这条配方就不该加载，
     * 而不是少一条需求照样跑。
     *
     * <p>消息保留原来的三段内容，逐字不改：哪些种类是「旧版自己就失效、不是缺口」、
     * 哪些是「本版没移植因为需要气体 API」。新增的只有开头那句「已注册的有哪些」——
     * 注册表化之后这个清单**会随兼容模块变化**，所以它必须由 {@link RequirementTypes#ids()} 现算，
     * 写成字面量就迟早与注册表不一致。
     */
    private static JsonParseException unknownType(String rawType, Object where) {
        return new JsonParseException("Unknown requirement type '" + rawType + "' in " + where
                + "'. M2 implements item, fluid and energy; M6d-b adds interface_number_input; 0.26.0 adds "
                + "ingredient_array_input; 0.65.0 adds catalyst. The original's gas, gas_pertick and fluid_pertick kinds are not "
                + "ported because they need a gas API; its item_durability kind is not "
                + "a requirement type at all and is not a gap (D18). The original registered catalyst but its JSON reader returned null, so only CraftTweaker could create one; this port accepts it from JSON. Registered right now: "
                + RequirementTypes.ids() + ". A compat module adds its own kinds here when it is installed.");
    }

    /**
     * 一条需求的 {@code selector-tag}，没写时为 {@code null}。
     *
     * <p>旧版在这里只确认它是字符串（{@code MachineRecipe.java:470-476}）。这里沿用本项目对「人手写字段」的惯例，
     * 把该写什么一并说清；空串被归一成「没有标签」，与 {@link MachineRequirement#setSelectorTag} 一致。
     */
    @javax.annotation.Nullable
    public static String readSelectorTag(JsonObject requirement, ResourceLocation id) {
        if (!requirement.has("selector-tag")) {
            return null;
        }
        JsonElement element = requirement.get("selector-tag");
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException("'selector-tag' of a requirement in " + id + " must be a string, but it "
                    + "is " + element + ". Write the name a part of the machine's structure declares, for example "
                    + "\"selector-tag\": \"input\".");
        }
        String tag = element.getAsString();
        return tag.isEmpty() ? null : tag;
    }

    /**
     * 一个必填的字符串字段。
     *
     * <p>{@code public} 而不是 {@code private}：它是**六条 {@link RequirementType#read} 实现共用的**读取器，
     * 就像各类型自己也共用了 {@link #chance}。{@code where} 的写法照旧版（{@code ModRecipeSerializers:203-208}）。
     */
    public static String string(JsonObject json, String key, Object where) {
        if (!json.has(key)) {
            throw new JsonParseException("Missing '" + key + "' in " + where);
        }
        return json.get(key).getAsString();
    }

    /**
     * 需求上的 {@code chance}，没写时为 {@code 1.0}。
     *
     * <p>读法照旧版：不校验类型，也不校验范围（改动前 {@code ModRecipeSerializers:152} 就是这一行）。
     * 「`chance` 在输入侧被静默忽略、`fluid` 输入的 `modifiedChance` 恒为 1」是**已登记的既有缺陷**，
     * 不在波 5.2 的范围里，所以这里不顺手改——改了它，配方文件的行为就会在本波里悄悄变一次。
     */
    public static float chance(JsonObject json) {
        return json.has("chance") ? json.get("chance").getAsFloat() : 1.0F;
    }

    // --------------------------------------------------------------- network

    /**
     * 把一条需求写进包：先写种类，再让种类自己写正文，最后写 {@code selector-tag}。
     *
     * <p><b>种类字符串来自需求自己</b>（{@link MachineRequirement#type()}），而不是一条
     * {@code instanceof} 链。改动前正是那条链（{@code ModRecipeSerializers:212-264}），
     * 它让「一种需求的种类」在三个地方各有一份答案，而其中一处漏改的症状是**发包时抛异常**：
     * 解析正常、单机正常、一联机就炸。
     *
     * <p><b>写全限定 id（{@code id().toString()}），不写 path。</b>这一条是波 5.2 里唯一一处**有意改变线上格式**，
     * 理由写在下面，因为它曾经被写成 path（想「与改动前逐字节相同」），而那个写法是错的：
     *
     * <ul>
     *   <li>写 path 会**丢掉命名空间**。读取端拿到的只有一个 {@code "demo_kind"}，而
     *       {@link RequirementTypes#byName} 的第一次查找是按**本模组**的命名空间找的——
     *       一个按自己 modid 注册的兼容种类于是**发得出去、读不回来**。症状是最坏的那一类：
     *       单机（同一个进程、注册表里有）完全正常，一台专用服务器上客户端直接报「未知需求类型」。</li>
     *   <li>内置五种不受这一改动影响：它们的 id 是 {@code modular_machinery_reborn:item}，
     *       读取端先按本模组命名空间找，命中的正是同一个键，所以那五个的往返与改动前一样。</li>
     * </ul>
     *
     * <p>代价说清楚：这一处与改动前的**字节**确实不同（多了一个命名空间前缀）。改动前这个字段必然是
     * {@code "item"} 这类 path，因为那时只有本模组内置的五种、命名空间无从谈起；而本波之后它必须能标识
     * 任何一个模块注册的种类。这是「格式必须能表达新东西」的那类改动，而不是顺手美化——
     * 所以它被登记在迁移日志里，而不是当成没发生。
     */
    public static void write(FriendlyByteBuf buf, MachineRequirement requirement) {
        buf.writeUtf(requirement.type().id().toString());
        requirement.type().write(buf, requirement);
        // 空串代表「没有标签」，而不是写一个可空值：`FriendlyByteBuf#writeUtf` 不接受 null，
        // 而「没有标签」是这个字段的常态。
        buf.writeUtf(requirement.selectorTag() == null ? "" : requirement.selectorTag());
    }

    /**
     * 从包里读一条需求：读种类 → 交给那个种类读正文 → 挂上标签。
     *
     * <p>拆成三步而不是在每个种类里各设一次：{@code selector-tag} 对每种需求类型含义完全相同，所以它就该只有一处。
     *
     * <p><b>未知种类在这里是 {@link IllegalStateException} 而不是 {@link JsonParseException}。</b>
     * 这不是「不该响亮失败」，而是两端不一致时的真实处境：能走到这一步，说明服务端把一种客户端没有装的需求
     * 发过来了（两边兼容模块集不同）。消息里写出种类与已注册清单，是为了让人立刻看出这是「模组不一致」
     * 而不是「包坏了」。
     */
    public static MachineRequirement read(FriendlyByteBuf buf) {
        String kind = buf.readUtf();
        RequirementType type = RequirementTypes.byName(kind);
        if (type == null) {
            throw new IllegalStateException("Unknown requirement kind '" + kind + "' on the wire. The server sent a "
                    + "requirement this side has not registered — the two sides are running different sets of "
                    + "compat modules. Registered here: " + RequirementTypes.ids() + ".");
        }
        IOType ioType = IOType.valueOf(buf.readUtf());
        MachineRequirement requirement = type.readBody(buf, ioType);
        String selectorTag = buf.readUtf();
        requirement.setSelectorTag(selectorTag.isEmpty() ? null : selectorTag);
        return requirement;
    }
}
