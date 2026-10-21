package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.client.preview.PreviewAtlas;
import com.reborn.modularmachinery.client.preview.PreviewLayout;
import com.reborn.modularmachinery.client.preview.PreviewPanel;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachinePattern;
import com.reborn.modularmachinery.machine.MachineRootFields;
import com.reborn.modularmachinery.machine.MachineSchema;
import com.reborn.modularmachinery.machine.StructurePreview;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * E5 + E4：`hide-components-when-formed` 求值，以及蓝图预览里的成型开关。
 *
 * <p>旧版这个字段做两件事：结构成型后让预览隐藏组件，并在世界上由可选的
 * {@code BlockModelHider} 隐藏方块。本版没有 Multiblocked / component_model_hider 这两个依赖，
 * 所以世界侧只保留字段语义；离线能证明的是三件事：
 *
 * <ol>
 *   <li><b>解析</b>：字段是 boolean，类型错误按旧版原文拒绝；`MachineSchema.read` 会求值而不是
 *       继续把它列在「认得但不求值」名单里。</li>
 *   <li><b>数据</b>：`StructurePreview.hidingComponents()` 只留下控制器所在的原点。</li>
 *   <li><b>预览</b>：成型开关只在机器声明了该字段时出现，打开后渲染列表只剩控制器；层模式下先隐藏再切片。</li>
 * </ol>
 *
 * <p>本节先于实现写。所有新入口都用反射找，这样在 0.70.0 源码上能编译并逐条报 FAIL，而不是在 javac
 * 就停下——红跑要能证明「这些行为今天不存在」，而不是证明「测试类写错了」。
 *
 * <p><b>为什么预览保留控制器。</b>旧版在成型隐藏时连控制器一起移除，随后只画 GeckoLib 自定义模型；
 * 本版预览还没有模型渲染路径，照抄会让没有模型的机器预览一片空白。保留控制器作为唯一锚点是有意差异，
 * 已记入 {@code docs/已知限制.md}。世界侧不做隐藏，因为旧版只有在上述两个外接模组存在时才隐藏。
 */
final class HideComponentsCheck {

    private static final String FIELD = "hide-components-when-formed";

    /** 两个部件：一个在 x，一个在 y。用来同时测「全部」「按层」「成型只剩原点」三种口径。 */
    private static final String PARTS =
            "\"parts\":[{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                    + "{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:stone\"}]";

    private static final String BASE = "{\"registryname\":\"e5_preview\",\"localizedname\":\"E5\"," + PARTS + "}";

    private HideComponentsCheck() {
    }

    static void hideComponentsWhenFormed() {
        section("E5/E4：hide-components-when-formed 求值与预览成型开关（0.71.0）");

        Method definitionFlag = method(MachineDefinition.class, "hideComponentsWhenFormed");
        report("MachineDefinition 公开 hideComponentsWhenFormed()（定义里写下的值必须能被读回来）",
                definitionFlag != null);

        Method rootField = method(MachineRootFields.class, "hideComponentsWhenFormed",
                JsonObject.class, String.class);
        report("MachineRootFields 公开 hideComponentsWhenFormed(JsonObject, String)（解析可离线驱动）",
                rootField != null);

        Method hiding = method(StructurePreview.class, "hidingComponents");
        report("StructurePreview 公开 hidingComponents()（成型隐藏的数据口径可离线断言）",
                hiding != null);

        Method select = method(PreviewPanel.class, "selectBlocks", StructurePreview.class,
                boolean.class, boolean.class, boolean.class, int.class);
        report("PreviewPanel 公开 selectBlocks(StructurePreview, boolean, boolean, boolean, int)",
                select != null);

        Method showsToggle = method(PreviewPanel.class, "showsFormedToggle", MachineDefinition.class);
        Method tooltip = method(PreviewPanel.class, "formedTooltipKey", boolean.class);
        report("PreviewPanel 公开 showsFormedToggle(MachineDefinition) 与 formedTooltipKey(boolean)",
                showsToggle != null && tooltip != null);

        // ---- 1) 解析：缺省 false、true/false、类型错误 ------------------------------------------------
        if (rootField != null) {
            report("缺省（没有该字段）时解析为 false",
                    Boolean.FALSE.equals(invokeBoolean(rootField, null, json("{}"), "where")));
            report("\"hide-components-when-formed\": true 解析为 true",
                    Boolean.TRUE.equals(invokeBoolean(rootField, null, json("{\"" + FIELD + "\":true}"), "where")));
            report("\"hide-components-when-formed\": false 解析为 false",
                    Boolean.FALSE.equals(invokeBoolean(rootField, null, json("{\"" + FIELD + "\":false}"), "where")));

            for (String bad : new String[] {"\"true\"", "1", "null", "[]", "{}"}) {
                Throwable failure = invokeFailure(rootField,
                        json("{\"" + FIELD + "\":" + bad + "}"), "where");
                String message = failure == null ? "<accepted>" : String.valueOf(failure.getMessage());
                report("类型错误 " + bad + " 被拒绝，且消息逐字是旧版原文（实际：" + message + "）",
                        failure != null
                                && "'hide-components-when-formed' has to be either 'true' or 'false'!"
                                .equals(message));
            }
        } else {
            report("缺省值 / true / false / 类型错误：入口不存在，这 8 条都没有证据", false);
        }

        // 经过 MachineSchema 的整条链：旧版把该字段当「认得但不求值」，会接受 "true" 字符串。
        expectRefused("MachineSchema.read 对 \"hide-components-when-formed\": \"true\" 拒绝（0.70.0 未求值、照单全收）",
                "{\"registryname\":\"e5_string\"," + PARTS + ",\"" + FIELD + "\":\"true\"}");
        expectRefused("MachineSchema.read 对 \"hide-components-when-formed\": 1 拒绝",
                "{\"registryname\":\"e5_number\"," + PARTS + ",\"" + FIELD + "\":1}");

        // ---- 2) 名单：字段已经求值，就不该再在「认得但不求值」里 --------------------------------
        report("KNOWN_ROOT_FIELDS 仍含 '" + FIELD + "'（已知字段的答案不能倒退）",
                MachineSchema.KNOWN_ROOT_FIELDS.contains(FIELD));
        String[] unimplemented = unimplementedRootFields();
        if (unimplemented == null) {
            report("读不到 UNIMPLEMENTED_ROOT_FIELDS，无法证明该字段已离开未实现名单", false);
        } else {
            boolean stillListed = false;
            for (String field : unimplemented) {
                if (FIELD.equals(field)) {
                    stillListed = true;
                }
            }
            report("'" + FIELD + "' 已离开 UNIMPLEMENTED_ROOT_FIELDS（它现在真的被求值）", !stillListed);
        }

        MachineDefinition withHide = read(BASE.replace("e5_preview", "e5_with_hide")
                .replace("\"localizedname\":\"E5\"", "\"localizedname\":\"E5\",\"" + FIELD + "\":true"));
        MachineDefinition withoutHide = read(BASE.replace("e5_preview", "e5_without_hide"));
        if (definitionFlag != null) {
            report("定义里写 true，getter 读回 true",
                    Boolean.TRUE.equals(invokeBoolean(definitionFlag, withHide)));
            report("没有写该字段，getter 读回 false（缺省是「不隐藏」）",
                    Boolean.FALSE.equals(invokeBoolean(definitionFlag, withoutHide)));
        }

        // ---- 3) 数据层：只留控制器 ----------------------------------------------------------------
        StructurePreview full = StructurePreview.of(read(BASE.replace("e5_preview", "e5_preview_data")).pattern(),
                Blocks.IRON_BLOCK.defaultBlockState());
        check("完整预览：两个部件 + 原点控制器 = 3 块", 3, full.blocks().size());
        check("完整预览：x 方向 2 格", 2, full.size().getX());
        check("完整预览：y 方向 2 格", 2, full.size().getY());

        if (hiding != null) {
            Object hiddenObject = invoke(hiding, full);
            report("hidingComponents() 返回 StructurePreview", hiddenObject instanceof StructurePreview);
            if (hiddenObject instanceof StructurePreview hidden) {
                check("成型隐藏：只剩 1 块（控制器）", 1, hidden.blocks().size());
                report("成型隐藏：唯一一块在原点（控制器位置）",
                        !hidden.blocks().isEmpty() && BlockPos.ZERO.equals(hidden.blocks().get(0).pos()));
                report("成型隐藏：控制器方块状态没有被替换",
                        !hidden.blocks().isEmpty()
                                && Blocks.IRON_BLOCK.defaultBlockState().equals(hidden.blocks().get(0).state()));
                check("成型隐藏：包围盒 x=1", 1, hidden.size().getX());
                check("成型隐藏：包围盒 y=1", 1, hidden.size().getY());
                check("成型隐藏：只有一层", 1, hidden.layers().size());
                Object again = invoke(hiding, hidden);
                check("成型隐藏是幂等的（再隐藏一次仍是 1 块）", 1,
                        again instanceof StructurePreview s ? s.blocks().size() : -1);
            }
        }

        StructurePreview empty = StructurePreview.of(MachinePattern.builder().build(), null);
        if (hiding != null) {
            Object emptyHidden = invoke(hiding, empty);
            report("空预览调用 hidingComponents() 不抛异常且仍为空",
                    emptyHidden instanceof StructurePreview s && s.isEmpty());
        }

        // ---- 4) 渲染列表：成型 × 层模式 ----------------------------------------------------------
        if (select != null) {
            check("未成型：3 块全部显示", 3, selectSize(select, full, false, true, false, 0));
            check("成型但机器没有声明隐藏字段：3 块仍全部显示（开关不改变未声明机器的行为）",
                    3, selectSize(select, full, true, false, false, 0));
            check("成型且声明隐藏：只剩 1 块（控制器）",
                    1, selectSize(select, full, true, true, false, 0));
            check("y=1 层未成型：只显示该层的 1 个部件",
                    1, selectSize(select, full, false, true, true, 1));
            check("y=0 层未成型：控制器 + x 方向部件 = 2 块",
                    2, selectSize(select, full, false, true, true, 0));
            check("y=1 层成型隐藏：该层没有控制器，列表为空（先隐藏再切片）",
                    0, selectSize(select, full, true, true, true, 1));
            List<?> layerZero = selectBlocks(select, full, true, true, true, 0);
            report("y=0 层成型隐藏：只剩原点控制器",
                    layerZero.size() == 1 && layerZero.get(0) instanceof StructurePreview.Block b
                            && BlockPos.ZERO.equals(b.pos()));
        }

        // ---- 5) E4 按钮：位置、是否出现、tooltip ------------------------------------------------
        try {
            int x = PreviewLayout.class.getField("FORMED_BUTTON_X").getInt(null);
            int y = PreviewLayout.class.getField("FORMED_BUTTON_Y").getInt(null);
            check("成型按钮 x = 面板宽 - 右边距 - 按钮宽 = 165", 165, x);
            check("成型按钮 y = 28（旧版 rightTopMenu 行）", 28, y);
        } catch (ReflectiveOperationException failure) {
            report("PreviewLayout 没有 FORMED_BUTTON_X/Y 常量：" + failure, false);
        }

        try {
            Field formed = PreviewAtlas.class.getField("FORMED");
            PreviewAtlas.Sprite sprite = (PreviewAtlas.Sprite) formed.get(null);
            check("成型按钮贴图 u = 184（旧版 setTexture(184,0)）", 184, sprite.u());
            check("成型按钮贴图 v = 0", 0, sprite.v());
            check("成型按钮贴图宽 13", 13, sprite.width());
            check("成型按钮贴图高 13", 13, sprite.height());
        } catch (ReflectiveOperationException | ClassCastException failure) {
            report("PreviewAtlas 没有 FORMED 按钮的四态贴图：" + failure, false);
        }

        if (showsToggle != null) {
            report("未声明 hide-components-when-formed 的机器不显示成型按钮（本版无模型渲染，按钮会是死键）",
                    Boolean.FALSE.equals(invokeBoolean(showsToggle, null, withoutHide)));
            report("声明了 hide-components-when-formed 的机器显示成型按钮",
                    Boolean.TRUE.equals(invokeBoolean(showsToggle, null, withHide)));
        }
        if (tooltip != null) {
            report("未成型时 tooltip 键是 toggle_formed.tip（提示「预览成型」）",
                    "gui.preview.button.toggle_formed.tip".equals(invokeString(tooltip, false)));
            report("已成型时 tooltip 键是 toggle_unformed.tip（提示「切回未成型」）",
                    "gui.preview.button.toggle_unformed.tip".equals(invokeString(tooltip, true)));
        }
    }

    private static int selectSize(Method select, StructurePreview preview, boolean formed, boolean hide,
                                  boolean layerMode, int layer) {
        return selectBlocks(select, preview, formed, hide, layerMode, layer).size();
    }

    @SuppressWarnings("unchecked")
    private static List<StructurePreview.Block> selectBlocks(Method select, StructurePreview preview,
                                                             boolean formed, boolean hide,
                                                             boolean layerMode, int layer) {
        Object result = invoke(select, null, preview, formed, hide, layerMode, layer);
        return result instanceof List<?> list ? (List<StructurePreview.Block>) list : List.of();
    }

    private static MachineDefinition read(String json) {
        return MachineSchema.read(JsonParser.parseString(json), "E5 fixture", java.util.Map.of(), true);
    }

    private static void expectRefused(String label, String json) {
        try {
            MachineSchema.read(JsonParser.parseString(json), "E5 fixture", java.util.Map.of(), true);
            report(label + "：实际被接受", false);
        } catch (RuntimeException failure) {
            report(label + "（实际消息：" + failure.getMessage() + "）",
                    failure.getMessage() != null && failure.getMessage().contains(FIELD));
        }
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException wrapped) {
            throw wrapped.getCause() instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException(wrapped.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Throwable invokeFailure(Method method, Object... arguments) {
        try {
            method.invoke(null, arguments);
            return null;
        } catch (InvocationTargetException wrapped) {
            return wrapped.getCause();
        } catch (ReflectiveOperationException failure) {
            return failure;
        }
    }

    private static Boolean invokeBoolean(Method method, Object target, Object... arguments) {
        Object value = invoke(method, target, arguments);
        return value instanceof Boolean flag ? flag : null;
    }

    private static String invokeString(Method method, Object... arguments) {
        Object value = invoke(method, null, arguments);
        return value instanceof String text ? text : null;
    }

    private static String[] unimplementedRootFields() {
        try {
            Field field = MachineSchema.class.getDeclaredField("UNIMPLEMENTED_ROOT_FIELDS");
            field.setAccessible(true);
            return (String[]) field.get(null);
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }
}
