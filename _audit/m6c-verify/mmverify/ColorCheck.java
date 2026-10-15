package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 波 6 第 2 项（{@code color}）：解析与保存，以及染色的模型资产。
 *
 * <h2>为什么先做这两半</h2>
 *
 * <p>波 6 的纪律是「一次一小步、做完留给实机确认」。{@code color} 全量包含三件事：
 * ① 解析与保存 ② 模型 tint 层与颜色处理器 ③ 外观。其中 ① 与 ② 的**资产部分**是离线可证的；
 * 真正把颜色画到方块上（方块实体带走颜色 + 处理器按位置读回）是下一小步，必须用眼睛看。
 *
 * <h2>断言走的是真实路径</h2>
 *
 * <p>解析的断言不直接调 {@code MachineDefinition.parseColor}，而是把一份**完整的定义 JSON** 喂给
 * {@code MachineSchema.read}——与数据包加载走同一条路。这样「解析器对了但没接进 loader」也会被抓到。
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

    /** 模型资产的目录。 */
    private static final Path MODELS =
            Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/assets/"
                    + "modular_machinery_reborn/models/block");

    private ColorCheck() {
    }

    static void colorParsing() {
        ParallelCraftCheck.section("color（波 6 · 0.60.0 解析 / 0.61.0 模型 tint 层）");

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
        // 旧版 DynamicMachinePreDeserializer#getColor 的两条错误是「不是字符串原语」与「解析失败」。
        // 本版把两条都归到一句「必须恰好六位十六进制」，因为那才是作者真正需要知道的事
        // （旧版那句「应该是十六进制整数」在 'GGGGGG' 与 12345 两种情况下都没说清该怎么写）。
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
        ParallelCraftCheck.report("color 已被加载器认识（否则作者会看到一条与事实相反的 "
                        + "「does not know: color」警告）",
                MachineSchema.KNOWN_ROOT_FIELDS.contains("color"));
        // 不直接读那份「未实现」清单（它是 private，为了断言去放宽可见性会把内部结构变成接口）。
        // 改从**行为**上验：`color` 现在读得出值，说明它不再是「认得但不求值」的字段。
        ParallelCraftCheck.report("...而且它确实被求值了（不是「认得但不求值」那种摆设）",
                read("{\"registryname\":\"color_evaluated\"," + PARTS
                        + ",\"color\":\"123456\"}").color() == 0x123456);

        // ---- 染色资产：外壳模型的 tint 层 ------------------------------------------------------------
        checkTintAssets();

        // ---- 染色链路（0.62.0）-----------------------------------------------------------------------
        checkColorChain();
    }

    /**
     * 染色链路的**接线**：模型有 tint 层、处理器存在并注册给本模组的方块、颜色存在方块实体上并随存档同步、
     * 控制器在成型时按 pattern 下发。
     *
     * <p>这些断言只能证明**接线**，证明不了「看起来是什么颜色」——外观必须用眼睛看，这一步的说明里写明了。
     * 之所以仍然值得钉：接线错掉时游戏里的表现是「什么都不发生」，与「颜色配得不好」几乎无法区分。
     */
    private static void checkColorChain() {
        try {
            // 1) 模型：只有背景层参与染色。
            Path overlay = MODELS.resolve("overlay_base.json");
            String overlayText = Files.readString(overlay);
            int bgTinted = countOf(overlayText, "\"texture\":\"#bg_");
            int tintedFaces = countOf(overlayText, "\"tintindex\":1");
            ParallelCraftCheck.report("overlay_base 的背景层六面都带 tint（实际 " + tintedFaces
                            + " 面 / bg_* 引用 " + bgTinted + " 个）——与旧版 blockmodel_overlay 一致",
                    tintedFaces == 6 && bgTinted == 6);
            ParallelCraftCheck.report("...而覆盖层（ov_*）**不**带 tint，机器自己的图案保持原色",
                    countOf(overlayText, "\"texture\":\"#ov_") == 6
                            && !overlayText.contains("\"texture\":\"#ov_down\",\"tintindex\""));

            // 2/3/4) 源码接线。用 methodBody/contains 检查，因为它们跨文件。
            Path colors = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                    + "modularmachinery/client/MachinePartColors.java");
            String colorsText = Files.readString(colors).replace(" ", "");
            ParallelCraftCheck.report("客户端注册了方块颜色处理器（RegisterColorHandlersEvent.Block）",
                    colorsText.contains("RegisterColorHandlersEvent.Block")
                            && colorsText.contains("event.register("));
            ParallelCraftCheck.report("...并且注册给本模组的控制器与全部仓族（不是 Block.class："
                            + "全量注册会把别的模组方块卷进来，Jade 那次已经吃过一回）",
                    colorsText.contains("MACHINE_CONTROLLER.get()")
                            && colorsText.contains("ModBlocks.hatchBlock(kind).get()")
                            // 判据是「注册**参数**里没有 Block.class」，不是「文件里没出现这几个字」——
                            // 类注释里正好在解释「为什么不用 Block.class」，按字面判会误伤注释。
                            // （与那两次「断言太宽/太窄」是同一类问题：判据要对着**行为**写。）
                            && !colorsText.contains(",Block.class)"));

            Path hatch = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                    + "modularmachinery/block/MachineHatchBlockEntity.java");
            String hatchText = Files.readString(hatch);
            ParallelCraftCheck.report("部件把颜色存进存档（随既有的方块实体同步到客户端）",
                    hatchText.contains("TAG_COLOR = \"machineColor\"")
                            && hatchText.contains("tag.putInt(TAG_COLOR, this.machineColor)")
                            && hatchText.contains("tag.contains(TAG_COLOR)"));

            Path controller = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                    + "modularmachinery/block/MachineControllerBlockEntity.java");
            String controllerText = Files.readString(controller);
            ParallelCraftCheck.report("控制器在成型时按**结构的 pattern**把颜色推给部件（不发明搜索半径）",
                    controllerText.contains("be.distributeMachineColor(")
                            && controllerText.contains("pattern.positions().keySet()")
                            && controllerText.contains("hatch.setMachineColor(color)"));
            ParallelCraftCheck.report("...而且控制器自己的颜色也进存档（否则它自己不会被染色）",
                    controllerText.contains("tag.putInt(\"machineColor\", machineColor)"));
        } catch (IOException unreadable) {
            ParallelCraftCheck.report("读取染色链路的源码失败: " + unreadable, false);
        }
    }

    /**
     * 外壳模型的 tint 层。
     *
     * <p>用途是**防止新增外壳模型时漏掉继承**：vanilla 的 {@code block/cube_all} 没有 tint 层，
     * 所以任何一个仍然直接继承它的模型都会「不参与染色」——而那种漏法在游戏里只表现为
     * 「这一种外壳不跟着变色」，很难注意到。断言把「每一种外壳都继承了带 tint 的父模型」钉住。
     */
    private static void checkTintAssets() {
        try {
            Path tintedParent = MODELS.resolve("cube_all_tinted.json");
            if (!Files.exists(tintedParent)) {
                ParallelCraftCheck.report("带 tint 的父模型存在（cube_all_tinted.json）", false);
                return;
            }
            int tintFaces = countOf(Files.readString(tintedParent), "\"tintindex\": 1");
            ParallelCraftCheck.report("父模型的六个面都带 tintindex 1（实际 " + tintFaces + " 个）",
                    tintFaces == 6);

            List<Path> casings;
            try (Stream<Path> stream = Files.list(MODELS)) {
                casings = stream
                        .filter(path -> path.getFileName().toString().startsWith("blockcasing"))
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .toList();
            }
            ParallelCraftCheck.report("外壳模型都被找到了（" + casings.size() + " 个）", casings.size() >= 6);
            for (Path casing : casings) {
                ParallelCraftCheck.report("...而且 " + casing.getFileName() + " 继承了带 tint 的父模型"
                                + "（仍继承 vanilla cube_all 的话，这一种外壳不会跟着染色）",
                        Files.readString(casing).contains("cube_all_tinted"));
            }
        } catch (IOException unreadable) {
            ParallelCraftCheck.report("读取染色的模型资产失败: " + unreadable, false);
        }
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

    /** 子串出现次数。 */
    private static int countOf(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }

    private static void check(String what, String expected, String actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }
}
