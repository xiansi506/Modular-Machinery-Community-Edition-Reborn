package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineSchema;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 勘测 X1：<b>「认得但不求值」名单与「已知字段」名单自相矛盾</b>。
 *
 * <h2>缺陷</h2>
 *
 * <p>{@code prefix} / {@code hide-components-when-formed} / {@code controller-bounding-box} 在
 * {@code MachineSchema.UNIMPLEMENTED_ROOT_FIELDS} 里，却不在 {@code KNOWN_ROOT_FIELDS} 里。于是同一份
 * <b>合法</b>定义会收到两条互相打脸的告警：一条说「accepted but not evaluated」（真话，因为作者确实写了、
 * 本项目确实还没做），另一条说「this loader does not know …」（假话——加载器明明认得）。更糟的是脚本面：
 * {@code MachineBuilderJS.register()} 走 {@code MachineSchema.read(..., strict = true)}，那条假告警在那里
 * 是<strong>抛异常</strong>，整份定义被拒。缺陷的成因不是某个字段写错，而是<b>两份名单之间没有约束</b>。
 *
 * <h2>为什么这里要反射，而 ColorCheck 说「不要为断言放宽可见性」</h2>
 *
 * <p>立场不变：<b>不加宽 private</b>——那份清单是加载器的内部结构，放开它等于把内部结构变成接口。反射读的
 * 只是「加载器自己认得的字段名」，不改变任何可见性，也不进入生产调用路径。这里的取舍是：只靠行为断言能抓住
 * <b>眼下这三个名字</b>，但抓不住<b>下一次</b>「又往未实现名单里加了个字段、忘了加进已知名单」——而那正是这次
 * 缺陷的成因。所以本节同时钉两件事：
 *
 * <ol>
 *   <li><b>结构</b>：{@code UNIMPLEMENTED_ROOT_FIELDS} 的每一项都在 {@code KNOWN_ROOT_FIELDS} 里；</li>
 *   <li><b>行为</b>：一份同时写着这三个字段的定义，在 strict 与非 strict 两条路径上都能被读出来。</li>
 * </ol>
 *
 * <p>还有一条<b>阴性对照</b>：拼错的字段（{@code prefx}）在 strict 下仍然被拒绝。放宽这三个字段之后，
 * 拼写检查不能变成「什么都收」——否则这条修复就等于把 strict 拆了。
 *
 * <p>0.81.0（E6）之后 {@code controller-bounding-box} 已由 {@link ControllerBoundingBoxCheck} 求值，
 * 未实现名单因此为空；本节对「空」的断言从失败翻成通过，其余结构/行为断言保持不变。
 */
final class RootFieldListCheck {

    /**
     * 一份最小的合法定义体（与 {@link ColorCheck} 同一个形状）。
     *
     * <p>两个部件是必需的：加载器拒绝「除控制器之外没有任何部件」的定义。
     */
    private static final String PARTS =
            "\"parts\":[{\"x\":0,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                    + "{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"}]";

    /**
     * 三个曾经「认得但不求值」的字段，值按旧版 {@code DynamicMachine} 的形状写。
     *
     * <p>0.81.0 起 {@code controller-bounding-box} 也求值了，名单因而清空；这份定义保留作历史夹具，
     * 用来继续证明这三个字段在 strict/非 strict 两条路径上都被接受。
     */
    private static final String UNIMPLEMENTED_FIELDS =
            "\"prefix\":\"彩虹 \""
                    + ",\"hide-components-when-formed\":true"
                    + ",\"controller-bounding-box\":[0.0,0.0,0.0,1.0,1.0,1.0]";

    private RootFieldListCheck() {
    }

    static void rootFieldLists() {
        ParallelCraftCheck.section("X1：两份根字段名单是同一个契约（0.63.0）");

        // ---- 0) 已知字段名单本身的自洽 ------------------------------------------------------------
        Set<String> distinct = new HashSet<>(MachineSchema.KNOWN_ROOT_FIELDS);
        ParallelCraftCheck.check("KNOWN_ROOT_FIELDS 没有重复项（重复项会让拒绝消息里同一个字段出现两次）",
                MachineSchema.KNOWN_ROOT_FIELDS.size(), distinct.size());

        // ---- 1) 结构：未实现名单 ⊆ 已知名单 ------------------------------------------------------
        String[] unimplemented = unimplementedRootFields();
        if (unimplemented == null) {
            ParallelCraftCheck.report("读不到 MachineSchema.UNIMPLEMENTED_ROOT_FIELDS（反射被拒），"
                    + "这份结构断言拿不到证据", false);
            return;
        }
        // 0.63.0 时这条断言写的是「名单必须非空」：那时名单里还有三个字段，空掉说明加载器把字段丢了。
        // 0.81.0 的 E6 做完最后一个字段后，空名单才是正确状态——断言随之翻面，而不是删掉：
        // 将来谁再往名单里塞字段，这里会立刻失败，提醒他同时补上「确实求值」的断言。
        ParallelCraftCheck.check("0.81.0 起「认得但不求值」名单为空（E6 完成最后一个根字段）",
                0, unimplemented.length);

        for (String field : unimplemented) {
            ParallelCraftCheck.report("'" + field + "' 在「认得但不求值」名单里，就必须在 KNOWN_ROOT_FIELDS 里"
                            + "（否则加载器会补一句假的「this loader does not know」，"
                            + "脚本面还会据此拒收整份定义）",
                    MachineSchema.KNOWN_ROOT_FIELDS.contains(field));
        }

        // ---- 2) 行为：同一份定义在两条加载路径上都能读出来 ----------------------------------------
        String allThree = "{\"registryname\":\"x1_all_three\",\"localizedname\":\"X1\"," + PARTS
                + "," + UNIMPLEMENTED_FIELDS + "}";
        readAccepted("脚本面（strict）：同时写三个「认得但不求值」字段的定义被接受", allThree, true);
        readAccepted("数据包（非 strict）：同一份定义同样被接受",
                allThree.replace("\"x1_all_three\"", "\"x1_all_three_datapack\""), false);

        // 逐个字段再验一遍：合并写法可能只是「恰好没触发」，逐个写才是作者真正的写法。
        for (String declaration : new String[] {
                "\"prefix\":\"单个字段\"",
                "\"hide-components-when-formed\":false",
                "\"controller-bounding-box\":[0.0,0.0,0.0,2.0,1.5,1.0]"}) {
            readAccepted("脚本面（strict）：只写 " + declaration.substring(0, declaration.indexOf(':'))
                            + " 的定义被接受",
                    "{\"registryname\":\"x1_single\",\"localizedname\":\"X1\"," + PARTS + ","
                            + declaration + "}", true);
        }

        // ---- 3) 阴性对照：拼写检查没有被放宽 ------------------------------------------------------
        String typo = "{\"registryname\":\"x1_typo\",\"localizedname\":\"X1\"," + PARTS + ",\"prefx\":\"x\"}";
        readRefused("脚本面（strict）：拼错的 'prefx' 仍被拒绝（放宽上面三个字段不等于放宽拼写检查）",
                typo, "prefx");
        readAccepted("数据包（非 strict）：同一个拼错字段只被报告、不被拒绝",
                typo.replace("\"x1_typo\"", "\"x1_typo_datapack\""), false);
    }

    /**
     * 反射读那份 private 的名单。返回 {@code null} 表示读不到——调用方必须把这件事报成失败，
     * 而不是当成「名单是空的」继续跑（那会让本节变成一段永远通过的代码）。
     */
    private static String[] unimplementedRootFields() {
        try {
            Field field = MachineSchema.class.getDeclaredField("UNIMPLEMENTED_ROOT_FIELDS");
            field.setAccessible(true);
            Object value = field.get(null);
            return value instanceof String[] names ? names : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            System.out.println("  -- 反射读 MachineSchema.UNIMPLEMENTED_ROOT_FIELDS 失败：" + unavailable);
            return null;
        }
    }

    /** 走加载器自己的入口，与数据包/脚本两侧同路。 */
    private static MachineDefinition read(String json, boolean strict) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return MachineSchema.read(root, "mmverify root-field fixture", Map.of(), strict);
    }

    private static void readAccepted(String what, String json, boolean strict) {
        try {
            MachineDefinition definition = read(json, strict);
            ParallelCraftCheck.report(what + "（读出来的是 " + definition.id()
                    + (strict ? "，strict" : "，非 strict") + "）", true);
        } catch (RuntimeException refused) {
            ParallelCraftCheck.report(what + " —— 但被拒绝了：" + refused.getMessage(), false);
        }
    }

    private static void readRefused(String what, String json, String mention) {
        try {
            MachineDefinition definition = read(json, true);
            ParallelCraftCheck.report(what + " [NOT refused; read " + definition.id() + "]", false);
        } catch (RuntimeException expected) {
            boolean mentions = String.valueOf(expected.getMessage()).contains(mention);
            ParallelCraftCheck.report(what + " —— " + expected.getMessage(), mentions);
        }
    }
}
