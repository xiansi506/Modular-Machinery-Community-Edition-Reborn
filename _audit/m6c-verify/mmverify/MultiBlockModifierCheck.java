package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.machine.BlockMatcher;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineModifier;
import com.reborn.modularmachinery.machine.MachinePattern;
import com.reborn.modularmachinery.machine.MachineSchema;
import com.reborn.modularmachinery.recipe.IOType;
import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RecipeModifiers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * C9：`multi-block-modifiers` —— 额外一段多块结构条件，条件全部命中时给配方贡献 RecipeModifier。
 *
 * <p>旧版的名字 `MultiBlockModifierReplacement` 容易读成「替换结构」，但真实语义不是：`BlockArray.matches`
 * 只检查另一段结构是否还在，控制器把它命中的 modifier 按名字放进同一张表。它不改基座匹配、不替换方块。
 * 新架构没有按名字去重的单块 modifier，所以本版的口径是<strong>全部追加</strong>，不模拟旧版同名覆盖。
 *
 * <p>本节先于实现写。新类型、新方法都走反射，这样在 0.71.0 源码上仍能编译，并把「今天不存在」
 * 逐条报成 FAIL，而不是让 javac 停在第一行。
 */
final class MultiBlockModifierCheck {

    private static final String TYPE = "com.reborn.modularmachinery.machine.MachineMultiBlockModifier";
    private static final String ROOT = "multi-block-modifiers";
    private static final String MODIFIER = "{\"target\":\"item\",\"io\":\"output\",\"operation\":1,"
            + "\"multiplier\":2.0}";

    private static final String BASE = "{\"registryname\":\"c9_machine\",\"localizedname\":\"C9\","
            + "\"parts\":[{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"}]";

    private static final String TWO_PARTS = "\"parts\":["
            + "{\"x\":0,\"y\":1,\"z\":0,\"elements\":\"minecraft:iron_block\"},"
            + "{\"x\":0,\"y\":0,\"z\":1,\"elements\":[\"minecraft:gold_block\",\"minecraft:iron_block\"]}]";

    private static final BlockPos CONTROLLER = new BlockPos(10, 64, 10);

    private MultiBlockModifierCheck() {
    }

    static void multiBlockModifiers() {
        section("C9：multi-block-modifiers（额外多块条件 + 命中贡献 RecipeModifier，0.72.0）");

        Class<?> type = type(TYPE);
        report("存在 MachineMultiBlockModifier 类型（旧版 MultiBlockModifierReplacement 的对应物）", type != null);

        Method pattern = method(type, "pattern");
        Method modifiers = method(type, "modifiers");
        Method description = method(type, "description");
        Method matches = method(type, "matches", BlockPos.class, int.class, BiPredicate.class);
        Method active = method(type, "active", List.class, BlockPos.class, int.class, BiPredicate.class);
        report("MachineMultiBlockModifier 暴露 pattern()/modifiers()/description()（数据可离线读回）",
                pattern != null && modifiers != null && description != null);
        report("MachineMultiBlockModifier.matches(BlockPos, int, BiPredicate)（四个朝向可离线匹配）",
                matches != null);
        report("MachineMultiBlockModifier.active(List, BlockPos, int, BiPredicate) 是静态筛选入口",
                active != null && Modifier.isStatic(active.getModifiers()));

        Method rotated = method(MachinePattern.class, "rotated", int.class);
        Method patternMatches = method(MachinePattern.class, "matches", BlockPos.class, BiPredicate.class);
        report("MachinePattern.rotated(int)（四步归一，含负数）", rotated != null);
        report("MachinePattern.matches(BlockPos, BiPredicate)（不依赖真 Level 的匹配入口）",
                patternMatches != null);

        Method multiGetter = method(MachineDefinition.class, "multiBlockModifiers");
        Method withMulti = method(MachineDefinition.class, "withMultiBlockModifiers", List.class);
        Method flatten = method(MachineDefinition.class, "flatten", List.class, List.class);
        report("MachineDefinition.multiBlockModifiers()", multiGetter != null);
        report("MachineDefinition.withMultiBlockModifiers(List)", withMulti != null);
        report("MachineDefinition.flatten(List<MachineModifier>, List<MachineMultiBlockModifier>) 重载",
                flatten != null);

        Object known = rootField("KNOWN_ROOT_FIELDS");
        Object unimplemented = rootField("UNIMPLEMENTED_ROOT_FIELDS");
        report("KNOWN_ROOT_FIELDS 含 'multi-block-modifiers'", contains(known, ROOT));
        report("UNIMPLEMENTED_ROOT_FIELDS 不含 'multi-block-modifiers'", !contains(unimplemented, ROOT));

        // ---- 1) 解析：两项 modifier、description、多坐标 parts ------------------------------------------
        String withField = BASE + ",\"" + ROOT + "\":[{" + TWO_PARTS + ",\"modifier\":" + MODIFIER
                + ",\"description\":\"extra structure\"}]}";
        MachineDefinition definition = readOrNull(withField);
        report("strict 模式接受带 multi-block-modifiers 的定义", definition != null);
        if (multiGetter == null || definition == null) {
            report("multi-block-modifiers 解析（入口缺失或定义被拒收，无法读回）", false);
        } else {
            List<?> parsed = list(invoke(multiGetter, definition));
            report("根字段解析出一个多块条件", parsed.size() == 1);
            if (parsed.size() == 1) {
                Object entry = parsed.get(0);
                MachinePattern shape = pattern == null ? null : asPattern(invoke(pattern, entry));
                List<?> recipeModifiers = modifiers == null ? List.of() : list(invoke(modifiers, entry));
                String text = description == null ? null : (String) invoke(description, entry);
                report("Condition.parts 解析为两个位置（数组 elements 也被接受）",
                        shape != null && shape.partCount() == 2);
                report("Condition 的单个 modifier 被解析", recipeModifiers.size() == 1);
                if (recipeModifiers.size() == 1) {
                    report("modifier 内容原样（item/output/multiply/2.0）",
                            recipeModifiers.get(0) instanceof RecipeModifier m
                                    && m.target() == RecipeModifier.Target.ITEM
                                    && m.ioTarget() == IOType.OUTPUT
                                    && m.operation() == RecipeModifier.Operation.MULTIPLY
                                    && m.value() == 2.0F);
                } else {
                    report("modifier 内容因数量不符无法验证", false);
                }
                report("description 原样", "extra structure".equals(text));
            } else {
                report("解析出的条件内容因数量不符无法验证", false);
            }

            MachineDefinition plain = read(BASE + "}");
            report("没有该根字段时列表为空（不是 null）",
                    list(invoke(multiGetter, plain)).isEmpty());

            MachineDefinition colored = definition.withColor(0x123456);
            report("withColor 保留 multiBlockModifiers",
                    list(invoke(multiGetter, colored)).size() == 1);
            report("withColor 同时保留颜色", colored.color() == 0x123456);

            MachineDefinition hidden = definition.withHideComponentsWhenFormed(true);
            report("withHideComponentsWhenFormed 保留 multiBlockModifiers",
                    list(invoke(multiGetter, hidden)).size() == 1);
            report("withHideComponentsWhenFormed 同时保留标志", hidden.hideComponentsWhenFormed());
        }

        // ---- 2) 解析：modifiers 数组、原点允许、错误文案 ------------------------------------------------
        if (multiGetter == null) {
            report("multi-block-modifiers 的 modifiers 数组、原点与错误文案（入口缺失）", false);
        } else {
            String arrayField = BASE + ",\"" + ROOT + "\":[{" + TWO_PARTS + ",\"modifiers\":["
                    + MODIFIER + "," + MODIFIER + "]}]}";
            MachineDefinition arrayDefinition = readOrNull(arrayField);
            List<?> arrayParsed = arrayDefinition == null ? List.of() : list(invoke(multiGetter, arrayDefinition));
            report("modifiers 数组写法被接受且两项都解析", arrayParsed.size() == 1
                    && modifiers != null && list(invoke(modifiers, arrayParsed.get(0))).size() == 2);

            String originField = BASE + ",\"" + ROOT + "\":[{\"parts\":["
                    + "{\"x\":0,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"}],"
                    + "\"modifier\":" + MODIFIER + "}]}";
            MachineDefinition originDefinition = readOrNull(originField);
            List<?> originParsed = originDefinition == null ? List.of() : list(invoke(multiGetter, originDefinition));
            MachinePattern originPattern = originParsed.size() == 1 && pattern != null
                    ? asPattern(invoke(pattern, originParsed.get(0))) : null;
            report("多块条件允许 (0,0,0)（旧 BlockArray 无坐标限制；单块 modifier 会跳过原点）",
                    originPattern != null && originPattern.positions().containsKey(BlockPos.ZERO));

            expectRefused("根字段不是数组", ROOT, BASE + ",\"" + ROOT + "\":{}}");
            expectRefused("entry 不是对象", ROOT,
                    BASE + ",\"" + ROOT + "\":[\"minecraft:stone\"]}");
            expectRefused("缺 parts", "parts", BASE + ",\"" + ROOT + "\":[{\"modifier\":" + MODIFIER + "}]}");
            expectRefused("parts 为空数组", "parts",
                    BASE + ",\"" + ROOT + "\":[{\"parts\":[],\"modifier\":" + MODIFIER + "}]}");
            expectRefused("parts 不是数组", "parts",
                    BASE + ",\"" + ROOT + "\":[{\"parts\":{},\"modifier\":" + MODIFIER + "}]}");
            expectRefused("缺 modifier 与 modifiers", "modifier",
                    BASE + ",\"" + ROOT + "\":[{" + TWO_PARTS + "}]}");
            expectRefused("modifiers 为空数组", "empty",
                    BASE + ",\"" + ROOT + "\":[{" + TWO_PARTS + ",\"modifiers\":[]}]}");
        }

        // ---- 3) 旋转与匹配：四个朝向、缺块、谓词收到的绝对坐标 ------------------------------------------
        MachinePattern shape = MachinePattern.builder()
                .add(new BlockPos(1, 0, 0), List.of(BlockMatcher.parse("minecraft:stone")))
                .add(new BlockPos(0, 1, 0), List.of(BlockMatcher.parse("minecraft:iron_block")))
                .tag(new BlockPos(1, 0, 0), "c9_tag")
                .build();
        if (rotated == null) {
            report("MachinePattern.rotated 的四步/负数归一与标签随转（入口缺失）", false);
        } else {
            MachinePattern one = asPattern(invoke(rotated, shape, 1));
            report("rotated(1) 把 (1,0,0) 变成 (0,0,-1)",
                    one != null && one.positions().containsKey(new BlockPos(0, 0, -1))
                            && !one.positions().containsKey(new BlockPos(1, 0, 0)));
            report("rotated(1) 把 selector-tag 一起转走",
                    one != null && "c9_tag".equals(one.tags().get(new BlockPos(0, 0, -1))));
            MachinePattern four = asPattern(invoke(rotated, shape, 4));
            report("rotated(4) 回到原位", four != null && four.positions().keySet().equals(shape.positions().keySet()));
            MachinePattern minusOne = asPattern(invoke(rotated, shape, -1));
            MachinePattern three = asPattern(invoke(rotated, shape, 3));
            report("rotated(-1) 与 rotated(3) 相同",
                    minusOne != null && three != null
                            && minusOne.positions().keySet().equals(three.positions().keySet()));
        }

        Map<BlockPos, BlockState> world = new HashMap<>();
        world.put(new BlockPos(11, 64, 10), Blocks.STONE.defaultBlockState());
        world.put(new BlockPos(10, 65, 10), Blocks.IRON_BLOCK.defaultBlockState());
        world.put(new BlockPos(10, 64, 9), Blocks.STONE.defaultBlockState());
        world.put(new BlockPos(9, 64, 10), Blocks.STONE.defaultBlockState());
        world.put(new BlockPos(10, 64, 11), Blocks.STONE.defaultBlockState());
        BiPredicate<BlockPos, List<BlockMatcher>> at = worldPredicate(world);

        if (patternMatches == null || rotated == null) {
            report("MachinePattern 的谓词匹配（入口缺失）", false);
        } else {
            for (int steps = 0; steps < 4; steps++) {
                MachinePattern rotatedShape = steps == 0 ? shape : asPattern(invoke(rotated, shape, steps));
                boolean hit = rotatedShape != null
                        && (Boolean) invoke(patternMatches, rotatedShape, CONTROLLER, at);
                report("谓词匹配：旋转 " + steps + " 步命中", hit);
            }
            Map<BlockPos, BlockState> missing = new HashMap<>(world);
            missing.remove(new BlockPos(10, 65, 10));
            report("谓词匹配：缺一块就不命中",
                    !(Boolean) invoke(patternMatches, shape, CONTROLLER, worldPredicate(missing)));
        }

        if (type == null || matches == null) {
            report("MachineMultiBlockModifier.matches 的四朝向行为（类型或方法缺失）", false);
        } else {
            Object condition = newMulti(type, shape, List.of(RecipeModifier.multiply(
                    RecipeModifier.Target.ITEM, 2.0F)), "c9");
            for (int steps = 0; steps < 4; steps++) {
                boolean hit = (Boolean) invoke(matches, condition, CONTROLLER, steps, at);
                report("Condition.matches：旋转 " + steps + " 步命中", hit);
            }
            Map<BlockPos, BlockState> missing = new HashMap<>(world);
            missing.remove(new BlockPos(11, 64, 10));
            report("Condition.matches：缺一块就不命中",
                    !(Boolean) invoke(matches, condition, CONTROLLER, 0, worldPredicate(missing)));
        }

        // ---- 4) active 筛选与 flatten 合并 ------------------------------------------------------------
        if (active == null || type == null) {
            report("active 只返回命中的条件（入口缺失）", false);
        } else {
            MachinePattern stoneOnly = MachinePattern.builder()
                    .add(new BlockPos(1, 0, 0), List.of(BlockMatcher.parse("minecraft:stone")))
                    .build();
            MachinePattern ironOnly = MachinePattern.builder()
                    .add(new BlockPos(0, 0, 1), List.of(BlockMatcher.parse("minecraft:iron_block")))
                    .build();
            Object stoneCondition = newMulti(type, stoneOnly, List.of(), "stone");
            Object ironCondition = newMulti(type, ironOnly, List.of(), "iron");
            List<?> one = list(invoke(active, null, List.of(stoneCondition, ironCondition), CONTROLLER, 0, at));
            report("active：只命中石头条件时返回 1 项", one.size() == 1);
            report("active：保留声明顺序（第一项）", one.size() == 1 && one.get(0) == stoneCondition);
            List<?> none = list(invoke(active, null, List.of(ironCondition), CONTROLLER, 2, at));
            report("active：旋转 2 步后铁条件不再命中", none.isEmpty());
            List<?> empty = list(invoke(active, null, List.of(), CONTROLLER, 0, at));
            report("active：空列表返回空列表", empty.isEmpty());
        }

        if (flatten == null || type == null) {
            report("flatten 把单块与多块 modifier 拼在一起（入口缺失）", false);
        } else {
            MachineModifier single = new MachineModifier(BlockPos.ZERO,
                    List.of(BlockMatcher.parse("minecraft:stone")),
                    List.of(RecipeModifier.add(RecipeModifier.Target.ITEM, 1.0F)), "single");
            Object multi = newMulti(type, shape,
                    List.of(RecipeModifier.multiply(RecipeModifier.Target.ITEM, 2.0F)), "multi");
            RecipeModifiers merged = (RecipeModifiers) invoke(flatten, null, List.of(single), List.of(multi));
            report("flatten 合并后有两项 modifier", merged.modifiers().size() == 2);
            RecipeModifiers onlySingle = (RecipeModifiers) invoke(flatten, null, List.of(single), List.of());
            report("flatten 的多块列表为空时只保留单块", onlySingle.modifiers().size() == 1);
        }
    }

    private static Object newMulti(Class<?> type, MachinePattern shape, List<RecipeModifier> modifiers,
                                   String description) {
        try {
            Constructor<?> constructor = type.getConstructor(MachinePattern.class, List.class, String.class);
            return constructor.newInstance(shape, modifiers, description);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static BiPredicate<BlockPos, List<BlockMatcher>> worldPredicate(Map<BlockPos, BlockState> world) {
        return (at, accepted) -> {
            BlockState state = world.get(at);
            if (state == null) {
                return false;
            }
            for (BlockMatcher matcher : accepted) {
                if (matcher.matches(state)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static MachineDefinition read(String json) {
        return MachineSchema.read(JsonParser.parseString(json), "C9 fixture", java.util.Map.of(), true);
    }

    private static MachineDefinition readOrNull(String json) {
        try {
            return read(json);
        } catch (RuntimeException refused) {
            return null;
        }
    }

    private static void expectRefused(String label, String fragment, String json) {
        try {
            MachineSchema.read(JsonParser.parseString(json), "C9 fixture", java.util.Map.of(), true);
            report(label + "：实际被接受", false);
        } catch (RuntimeException failure) {
            String message = failure.getMessage();
            report(label + "（实际消息：" + message + "）",
                    message != null && message.toLowerCase(java.util.Locale.ROOT)
                            .contains(fragment.toLowerCase(java.util.Locale.ROOT)));
        }
    }

    private static Object rootField(String name) {
        try {
            Field field = MachineSchema.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException failure) {
            return null;
        }
    }

    private static boolean contains(Object values, String wanted) {
        if (values instanceof String[] array) {
            for (String value : array) {
                if (wanted.equals(value)) {
                    return true;
                }
            }
            return false;
        }
        return values instanceof List<?> list && list.contains(wanted);
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        if (owner == null) {
            return null;
        }
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

    private static MachinePattern asPattern(Object value) {
        return value instanceof MachinePattern pattern ? pattern : null;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
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
