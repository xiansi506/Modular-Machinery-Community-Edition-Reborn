package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineSchema;

import java.util.Map;

/**
 * 波 6 第 2 项（{@code color}）的第一小步：**解析与保存**。
 *
 * <h2>为什么先做这一半</h2>
 *
 * <p>波 6 的纪律是「一次一小步、做完留给实机确认」。{@code color} 全量做下来包含三件事：
 * ① 解析（十六进制字符串 → 颜色值）② 染色落地（模型 tint 层 + 方块颜色处理器）③ 外观。
 * 其中 ① 是**纯函数**、可以离线钉死；②③ 是渲染、必须用眼睛。
 * 所以这一节只钉 ①：定义里写什么颜色，读出来就是什么；写错了，报的是哪一句话。
 *
 * <h2>断言走的是真实路径</h2>
 *
 * <p>不是直接调 {@code MachineDefinition.parseColor}，而是把一份**完整的定义 JSON** 喂给
 * {@code MachineSchema.read}——与数据包加载走的是同一条路。这样「解析器对了但没接进 loader」
 * 这种错误也会被抓到（那正是本项目反复强调的「接线也要断言」）。
 */
final class ColorCheck {

    /**
     * 一份**最小的合法**定义体。
     *
     * <p>两个部件是必需的，不是随手写的：加载器要求「除控制器之外至少还有一个部件」
     * （第一次写只放了一个，复现器当场报 `has no parts besides the controller`）。
     * 控制器的位置由加载器自己决定，所以这里两个都是普通方块。
     */
    private static final String PARTS =
            "\"parts\":[{\"x\":0,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                    + "{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"}]";

    private ColorCheck() {
    }

    static void colorParsing() {
        ParallelCraftCheck.section("color 解析（波 6 · 0.60.0）");

        // ---- 成功路径 ------------------------------------------------------------------------------
        check("a definition without 'color' keeps the default (white = no tint)",
                String.valueOf(MachineDefinition.DEFAULT_COLOR),
                String.valueOf(read("{\"registryname\":\"color_default\"," + PARTS + "}").color()));
        check("'color' is read as a hex string", String.valueOf(0xFF00FF),
                String.valueOf(read("{\"registryname\":\"color_magenta\"," + PARTS
                        + ",\"color\":\"FF00FF\"}").color()));
        check("...lower case hex is accepted too", String.valueOf(0x00FF00),
                String.valueOf(read("{\"registryname\":\"color_green\"," + PARTS
                        + ",\"color\":\"00ff00\"}").color()));
        check("...leading zeros are significant ('0000FF' is blue, not a short form)",
                String.valueOf(0x0000FF),
                String.valueOf(read("{\"registryname\":\"color_blue\"," + PARTS
                        + ",\"color\":\"0000FF\"}").color()));
        check("...white is a colour like any other", String.valueOf(0xFFFFFF),
                String.valueOf(read("{\"registryname\":\"color_white\"," + PARTS
                        + ",\"color\":\"FFFFFF\"}").color()));

        // ---- 失败路径：两条与旧版对应的话 ------------------------------------------------------------
        //
        // 旧版 DynamicMachinePreDeserializer#getColor 的两条错误是「不是字符串原语」与「解析失败」，
        // 而且第二条把原因包起来。本版把两条都归到一句「必须恰好六位十六进制」，
        // 因为那才是作者真正需要知道的事（旧版那句「应该是十六进制整数」在 'GGGGGG' 与 12345 两种情况下
        // 都没说清该怎么写）。
        reportRefuses("a non-string 'color' is refused by name",
                "{\"registryname\":\"color_bad_type\"," + PARTS + ",\"color\":12345}",
                "color");
        reportRefuses("a non-hex 'color' is refused by name",
                "{\"registryname\":\"color_bad_hex\"," + PARTS + ",\"color\":\"GGGGGG\"}",
                "color");
        reportRefuses("a three-digit 'color' is refused rather than read as 0x000FFF",
                "{\"registryname\":\"color_short\"," + PARTS + ",\"color\":\"FFF\"}",
                "color");
        reportRefuses("an eight-digit 'color' is refused rather than overflowing into a negative int",
                "{\"registryname\":\"color_long\"," + PARTS + ",\"color\":\"FFFFFFFF\"}",
                "color");

        // ---- 接线：字段名必须被加载器**认识** --------------------------------------------------------
        //
        // 这一条是复现器输出里的 WARN 逼出来的，不是看代码看出来的：做完解析之后断言全绿，
        // 日志里却在说「has root field(s) this loader does not know: color」——因为 `color` 此前在
        // UNIMPLEMENTED_ROOT_FIELDS 里，从来不需要出现在 KNOWN_ROOT_FIELDS 中。
        // 那条警告会让包作者以为字段名写错了，所以它必须不出现——**「没有警告」也要被钉住**。
        // 与 `dynamic-patterns` 离开那份名单时的断言同形（见 ParallelCraftCheck）。
        ParallelCraftCheck.report("color 已被加载器认识（否则作者会看到一条与事实相反的 "
                        + "「does not know: color」警告）",
                MachineSchema.KNOWN_ROOT_FIELDS.contains("color"));
        // 不直接读那份「未实现」清单（它是 private，而为了断言去放宽可见性会把内部结构变成接口）。
        // 改从**行为**上验：`color` 现在读得出值，说明它不再是「认得但不求值」的字段。
        // 这与上面那条断言是不同的失败模式：上面抓「字段名不被认识」，这条抓「字段被认识但被当成摆设」。
        ParallelCraftCheck.report("...而且它确实被求值了（不是「认得但不求值」那种摆设）",
                read("{\"registryname\":\"color_evaluated\"," + PARTS
                        + ",\"color\":\"123456\"}").color() == 0x123456);
    }

    /** 用真实加载路径读一份定义。 */
    private static MachineDefinition read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return MachineSchema.read(root, "mmverify color fixture", Map.of());
    }

    /** 断言这份定义被**拒绝**，且理由里提到 {@code mention}。 */
    private static void reportRefuses(String what, String json, String mention) {
        try {
            MachineDefinition definition = read(json);
            ParallelCraftCheck.report(what + " [NOT refused; got color=" + definition.color() + "]", false);
        } catch (RuntimeException expected) {
            boolean mentions = String.valueOf(expected.getMessage()).contains(mention);
            ParallelCraftCheck.report(what + " — " + expected.getMessage(), mentions);
        }
    }

    private static void check(String what, String expected, String actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }
}
