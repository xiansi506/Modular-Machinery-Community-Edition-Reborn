package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.machine.FailureAction;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachinePattern;
import com.reborn.modularmachinery.machine.MachineRootFields;
import com.reborn.modularmachinery.machine.MachineSchema;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * E7: the {@code prefix} root field.
 *
 * <p>钉住四件事：解析（缺省/空串/非字符串/正常）、定义上的不可变保存、schema 的端到端读取，
 * 以及预览标题按旧版的 36 + 2 + 136 分段。所有新方法都走反射，所以红跑（生产代码还没有
 * prefix）仍能编译，每一条缺失都会单独报 FAIL。
 *
 * <p>预览那一段是<strong>源码断言</strong>，不是渲染证据：GUI 只能在客户端跑，离线复现器不打开窗口。
 * 真正的画面仍要在游戏里看；这里只能证明代码选择/布局没有退回「整行居中机器名」。
 */
final class PrefixCheck {

    private static final String PARTS =
            "\"parts\":[{\"x\":0,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                    + "{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"}]";

    private PrefixCheck() {
    }

    static void prefixField() {
        ParallelCraftCheck.section("E7：prefix 根字段（0.80.0）");
        parser();
        definition();
        schema();
        builder();
        preview();
    }

    // ---------------------------------------------------------------- parser

    private static void parser() {
        Method prefix = method(MachineRootFields.class, "prefix", JsonObject.class, String.class);
        if (prefix == null) {
            report("MachineRootFields.prefix(JsonObject, String) 存在", false);
            return;
        }
        report("MachineRootFields.prefix(JsonObject, String) 存在", true);

        expectNull("prefix 缺省 -> null（没有前缀是正常状态）", prefix, "{}");
        expectText("prefix 正常字符串被原样读回", prefix, "{\"prefix\":\"彩虹\"}", "彩虹");
        expectFailure("prefix 空串按旧版原文拒绝", prefix, "{\"prefix\":\"\"}",
                "Invalid/Missing 'prefix' !");
        expectFailureContaining("prefix 数字被拒绝（本版有意只接受 JSON 字符串）",
                prefix, "{\"prefix\":123}", "must be a string");
        expectFailureContaining("prefix 布尔被拒绝", prefix, "{\"prefix\":true}", "must be a string");
        expectFailureContaining("prefix null 被拒绝", prefix, "{\"prefix\":null}", "must be a string");
    }

    // ---------------------------------------------------------------- definition

    private static void definition() {
        Method getPrefix = method(MachineDefinition.class, "prefix");
        Method getKey = method(MachineDefinition.class, "prefixTranslationKey");
        Method withPrefix = method(MachineDefinition.class, "withPrefix", String.class);
        if (getPrefix == null || getKey == null || withPrefix == null) {
            report("MachineDefinition 有 prefix()/prefixTranslationKey()/withPrefix(String)", false);
            return;
        }
        report("MachineDefinition 有 prefix()/prefixTranslationKey()/withPrefix(String)", true);

        MachineDefinition base = new MachineDefinition(new ResourceLocation("mmverify", "e7_demo"), "E7 Demo",
                MachinePattern.builder().add(new BlockPos(1, 0, 0),
                        List.of(com.reborn.modularmachinery.machine.BlockMatcher.parse("minecraft:stone"))).build(),
                FailureAction.RESET, false);
        report("旧构造器造出的定义默认没有前缀", getPrefix(getPrefix, base) == null);
        check("prefixTranslationKey 是 <namespace>.<path>.prefix", "mmverify.e7_demo.prefix",
                getKey(getKey, base));

        MachineDefinition withColor = base.withColor(0x123456).withHideComponentsWhenFormed(true);
        MachineDefinition prefixed = invokeDefinition(withPrefix, withColor, "装配");
        if (prefixed == null) {
            return;
        }
        check("withPrefix 返回的副本保留前缀", "装配", String.valueOf(getPrefix(getPrefix, prefixed)));
        report("withPrefix 不改原定义（定义不可变）", getPrefix(getPrefix, withColor) == null);
        check("withPrefix 保留染色", 0x123456L, prefixed.color());
        report("withPrefix 保留 hide-components-when-formed", prefixed.hideComponentsWhenFormed());
        check("withPrefix 保留 registry name", "mmverify:e7_demo", prefixed.id().toString());

        MachineDefinition cleared = invokeDefinition(withPrefix, prefixed, null);
        report("withPrefix(null) 清掉前缀", cleared != null && getPrefix(getPrefix, cleared) == null);

        try {
            withPrefix.invoke(prefixed, "");
            report("withPrefix(空串) 被拒绝（不让无效定义从公开 API 漏进来）", false);
        } catch (InvocationTargetException expected) {
            Throwable cause = expected.getCause();
            report("withPrefix(空串) 被拒绝（" + cause + "）", cause instanceof IllegalArgumentException);
        } catch (ReflectiveOperationException problem) {
            report("withPrefix(空串) 被拒绝（反射失败：" + problem + "）", false);
        }
    }

    // ---------------------------------------------------------------- schema

    private static void schema() {
        Method getPrefix = method(MachineDefinition.class, "prefix");
        if (getPrefix == null) {
            report("schema 端到端：MachineDefinition.prefix() 不存在，无法读回", false);
            return;
        }

        String withPrefix = "{\"registryname\":\"e7_prefix\",\"localizedname\":\"E7\"," + PARTS
                + ",\"prefix\":\"彩虹\"}";
        MachineDefinition read = read(withPrefix, true);
        report("strict 路径接受 prefix 字段，并读回同一个字符串",
                read != null && "彩虹".equals(getPrefix(getPrefix, read)));

        MachineDefinition plain = read("{\"registryname\":\"e7_plain\",\"localizedname\":\"E7\","
                + PARTS + "}", true);
        report("没有 prefix 字段时读成 null（不是空串）",
                plain != null && getPrefix(getPrefix, plain) == null);

        expectSchemaFailure("schema 拒绝空串 prefix", "{\"registryname\":\"e7_empty\",\"localizedname\":\"E7\","
                + PARTS + ",\"prefix\":\"\"}", "Invalid/Missing 'prefix' !");
        expectSchemaFailure("schema 拒绝非字符串 prefix",
                "{\"registryname\":\"e7_number\",\"localizedname\":\"E7\"," + PARTS
                        + ",\"prefix\":123}", "must be a string");

        String[] unimplemented = unimplementedRootFields();
        if (unimplemented == null) {
            report("读不到 UNIMPLEMENTED_ROOT_FIELDS，无法证明 prefix 已离开未实现名单", false);
        } else {
            report("prefix 已离开 UNIMPLEMENTED_ROOT_FIELDS（不再报「accepted but not evaluated」）",
                    !List.of(unimplemented).contains("prefix"));
            // E6（0.81.0）把最后一个字段也求值了，名单因此变空。原来的对照「controller-bounding-box
            // 仍在这里」随之失效；改成反向断言：如果它回来了，说明 E6 的回退没有被发现。
            report("controller-bounding-box 已离开 UNIMPLEMENTED_ROOT_FIELDS（0.81.0 E6 求值）",
                    !List.of(unimplemented).contains("controller-bounding-box"));
        }
        report("prefix 仍在 KNOWN_ROOT_FIELDS 里（strict 拼写检查认得它）",
                MachineSchema.KNOWN_ROOT_FIELDS.contains("prefix"));
    }

    // ---------------------------------------------------------------- script builder

    private static void builder() {
        // Not Class.forName: MachineBuilderJS extends KubeJS's EventJS, and the KubeJS runtime is deliberately
        // absent from this harness's classpath (see KubeJSMachineCheck's class comment). Reading the class file
        // asks the same question without loading anything, and that is how Z0/Z8b check every other builder
        // method. The write itself is covered end-to-end by the JSON fixture through MachineSchema.read; the
        // script's own addProperty call is only observable in-game.
        String builder = classFile("com/reborn/modularmachinery/kubejs/MachineBuilderJS");
        String descriptor = "(Ljava/lang/Object;)Lcom/reborn/modularmachinery/kubejs/MachineBuilderJS;";
        report("MachineBuilderJS 声明 prefix(Object)（不加载类，只读 class 文件）",
                builder != null && builder.contains("prefix")
                        && builder.contains(descriptor));
        report("...and its constant pool carries the root key 'prefix'", builder != null
                && builder.contains("prefix"));
    }

    // ---------------------------------------------------------------- preview

    private static void preview() {
        String source = source("client/preview/PreviewPanel.java");
        String compact = source.replaceAll("\\s+", " ");
        report("预览标题读取 prefixTranslationKey()（不再假定没有 prefix）",
                source.contains("prefixTranslationKey()"));
        report("预览标题用 Language.getInstance().has(...) 判断语言键",
                source.contains("Language.getInstance().has("));
        report("预览标题保留旧版 36 + 2 + 136 的分段布局",
                compact.contains("TITLE_X + 36") && compact.contains("136 / 2"));
        report("不再保留「We have no prefix field」那条过时注释",
                !source.contains("no prefix field in MachineDefinition"));
    }

    // ---------------------------------------------------------------- helpers

    private static String source(String relative) {
        try {
            return Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/" + relative));
        } catch (Exception unreadable) {
            throw new IllegalStateException("cannot read " + relative, unreadable);
        }
    }

    private static MachineDefinition read(String json, boolean strict) {
        return MachineSchema.read(JsonParser.parseString(json).getAsJsonObject(), "mmverify prefix fixture",
                Map.of(), strict);
    }

    private static void expectSchemaFailure(String what, String json, String mention) {
        try {
            read(json, true);
            report(what + " [NOT refused]", false);
        } catch (RuntimeException expected) {
            report(what + " —— " + expected.getMessage(),
                    String.valueOf(expected.getMessage()).contains(mention));
        }
    }

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

    private static void expectNull(String what, Method method, String json) {
        try {
            Object value = method.invoke(null, JsonParser.parseString(json).getAsJsonObject(), "fixture");
            report(what, value == null);
        } catch (ReflectiveOperationException problem) {
            report(what + "（反射失败：" + problem + "）", false);
        }
    }

    private static void expectText(String what, Method method, String json, String expected) {
        try {
            Object value = method.invoke(null, JsonParser.parseString(json).getAsJsonObject(), "fixture");
            report(what, expected.equals(value));
        } catch (ReflectiveOperationException problem) {
            report(what + "（反射失败：" + problem + "）", false);
        }
    }

    private static void expectFailure(String what, Method method, String json, String expectedMessage) {
        try {
            method.invoke(null, JsonParser.parseString(json).getAsJsonObject(), "fixture");
            report(what + " [NOT refused]", false);
        } catch (InvocationTargetException expected) {
            Throwable cause = expected.getCause();
            report(what + " —— " + cause, cause instanceof JsonParseException
                    && expectedMessage.equals(cause.getMessage()));
        } catch (ReflectiveOperationException problem) {
            report(what + "（反射失败：" + problem + "）", false);
        }
    }

    private static void expectFailureContaining(String what, Method method, String json, String mention) {
        try {
            method.invoke(null, JsonParser.parseString(json).getAsJsonObject(), "fixture");
            report(what + " [NOT refused]", false);
        } catch (InvocationTargetException expected) {
            Throwable cause = expected.getCause();
            report(what + " —— " + cause, cause instanceof JsonParseException
                    && String.valueOf(cause.getMessage()).contains(mention));
        } catch (ReflectiveOperationException problem) {
            report(what + "（反射失败：" + problem + "）", false);
        }
    }

    private static MachineDefinition invokeDefinition(Method withPrefix, MachineDefinition base, String prefix) {
        try {
            Object value = withPrefix.invoke(base, prefix);
            return value instanceof MachineDefinition definition ? definition : null;
        } catch (InvocationTargetException problem) {
            report("withPrefix(" + prefix + ") 抛了 " + problem.getCause(), false);
            return null;
        } catch (ReflectiveOperationException problem) {
            report("withPrefix(" + prefix + ") 反射失败：" + problem, false);
            return null;
        }
    }

    private static Object getPrefix(Method method, MachineDefinition definition) {
        try {
            return method.invoke(definition);
        } catch (ReflectiveOperationException problem) {
            return null;
        }
    }

    private static String getKey(Method method, MachineDefinition definition) {
        try {
            Object value = method.invoke(definition);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException problem) {
            return null;
        }
    }

    private static String classFile(String internalName) {
        try (InputStream in = PrefixCheck.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException unreadable) {
            return null;
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException missing) {
            return null;
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
}