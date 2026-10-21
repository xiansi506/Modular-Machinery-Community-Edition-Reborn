package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineSchema;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * C7：GeckoLib 自定义控制器模型。
 *
 * <p>本节只钉数据层与接线是否真的存在：机器定义新增 {@code geo-model} 根字段，字段被 strict 加载器接受，
 * 五个资源各自能被读回，非法值被拒；{@code MachineDefinition} 能携带它、{@code withColor} 不会把它丢掉。
 * 渲染本身（GeckoLib 是否真的把模型画出来、朝向、动画切换）不在离线复现器的能力范围内，必须实机验证。
 *
 * <p>全部走反射，因为 0.69.0 的源码没有这些成员：测试必须能编译并在旧源码上跑出红，而不是靠编译失败当红。
 */
final class GeoModelCheck {

    private static final String PARTS =
            "\"parts\":[{\"x\":0,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                    + "{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"}]";

    private static final String SPEC_CLASS = "com.reborn.modularmachinery.machine.GeoModelSpec";

    private static int sequence;

    private GeoModelCheck() {
    }

    static void geoModels() {
        ParallelCraftCheck.section("C7：GeckoLib 控制器模型（0.70.0）");

        try {
            ParallelCraftCheck.report("MachineSchema.KNOWN_ROOT_FIELDS 认识 'geo-model'"
                            + "（不在名单里，strict 加载器会把合法定义当拼写错误拒收）",
                    MachineSchema.KNOWN_ROOT_FIELDS.contains("geo-model"));
        } catch (RuntimeException unavailable) {
            ParallelCraftCheck.report("读 MachineSchema.KNOWN_ROOT_FIELDS 失败：" + unavailable, false);
        }

        MachineDefinition full = accepted("strict 加载器接受带 geo-model 的定义", fullGeo(), true);
        boolean hasGeoModel = hasMethod(MachineDefinition.class, "geoModel");
        ParallelCraftCheck.report("MachineDefinition 有 public geoModel()", hasGeoModel);

        if (full != null && hasGeoModel) {
            Object spec = geoModel(full, "完整定义");
            checkText(spec, "model", "example:geo/machine.geo.json", "完整定义的 model 被读回");
            checkText(spec, "texture", "example:textures/geo/machine.png", "完整定义的 texture 被读回");
            checkText(spec, "animation", "example:animations/machine.animation.json", "完整定义的 animation 被读回");
            checkText(spec, "idleAnimation", "idle_loop", "完整定义的 idle 被读回");
            checkText(spec, "workingAnimation", "working_loop", "完整定义的 working 被读回");
        } else {
            skip("完整定义的五个资源", "定义没有被读出来，或 geoModel() 不存在");
        }

        MachineDefinition minimal = accepted("geo-model 只写 model/texture 也被接受", minimalGeo(), true);
        if (minimal != null && hasGeoModel) {
            Object spec = geoModel(minimal, "最小定义");
            checkText(spec, "model", "example:geo/min.geo.json", "最小定义的 model 被读回");
            checkNull(spec, "animation", "最小定义的 animation 为 null");
            checkNull(spec, "idleAnimation", "最小定义的 idle 为 null");
            checkNull(spec, "workingAnimation", "最小定义的 working 为 null");
        } else {
            skip("最小定义的可选字段", "定义没有被读出来，或 geoModel() 不存在");
        }

        MachineDefinition namespaced = accepted("没有命名空间的路径按旧版规则补 modularmachinery:",
                namespacedGeo(), true);
        if (namespaced != null && hasGeoModel) {
            Object spec = geoModel(namespaced, "命名空间改写");
            checkText(spec, "model", "modularmachinery:geo/machine.geo.json",
                    "无命名空间的 model 被改写成 modularmachinery:（旧版 GeoMachineModel 的规则）");
            checkText(spec, "texture", "modularmachinery:textures/geo/machine.png",
                    "无命名空间的 texture 被改写成 modularmachinery:");
            checkText(spec, "animation", "modularmachinery:animations/machine.animation.json",
                    "无命名空间的 animation 被改写成 modularmachinery:");
        } else {
            skip("命名空间改写", "定义没有被读出来，或 geoModel() 不存在");
        }

        MachineDefinition explicitMinecraft = accepted("显式 minecraft: 命名空间也按旧版规则改写",
                explicitMinecraftGeo(), true);
        if (explicitMinecraft != null && hasGeoModel) {
            Object spec = geoModel(explicitMinecraft, "minecraft 命名空间改写");
            checkText(spec, "model", "modularmachinery:geo/mc.geo.json",
                    "显式 minecraft: 的 model 被改写成 modularmachinery:（旧版同样的重写）");
        } else {
            skip("显式 minecraft: 改写", "定义没有被读出来，或 geoModel() 不存在");
        }

        MachineDefinition plain = accepted("没有 geo-model 的旧定义照常读", noGeo(), true);
        if (plain != null && hasGeoModel) {
            Object spec = geoModel(plain, "没有 geo-model 的旧定义");
            ParallelCraftCheck.report("没有 geo-model 的旧定义读到 null，而不是一个空对象", spec == null);
        } else {
            skip("没有 geo-model 时默认为 null", "旧定义没有被读出来，或 geoModel() 不存在");
        }

        if (full != null && hasGeoModel) {
            MachineDefinition tinted = full.withColor(0x123456);
            Object tintedSpec = geoModel(tinted, "withColor 之后");
            Object fullSpec = geoModel(full, "withColor 之前");
            ParallelCraftCheck.report("withColor() 保留 geoModel（换颜色不能把模型丢掉）",
                    sameText(fullSpec, tintedSpec, "model"));
            ParallelCraftCheck.report("withColor() 仍然改颜色", tinted.color() == 0x123456);
        } else {
            skip("withColor 保留 geoModel", "完整定义没有被读出来，或 geoModel() 不存在");
        }

        if (plain != null && full != null && hasGeoModel) {
            try {
                Class<?> specClass = Class.forName(SPEC_CLASS);
                Method withGeoModel = MachineDefinition.class.getMethod("withGeoModel", specClass);
                Object spec = geoModel(full, "withGeoModel 的来源");
                MachineDefinition replaced = (MachineDefinition) withGeoModel.invoke(plain.withColor(0x654321), spec);
                ParallelCraftCheck.report("withGeoModel() 返回带模型的新定义",
                        sameText(spec, geoModel(replaced, "withGeoModel 之后"), "model"));
                ParallelCraftCheck.report("withGeoModel() 保留颜色", replaced.color() == 0x654321);
            } catch (ReflectiveOperationException missing) {
                ParallelCraftCheck.report("withGeoModel(GeoModelSpec) 存在且可用 —— " + missing, false);
            }
        } else {
            skip("withGeoModel", "定义没有被读出来，或 geoModel() 不存在");
        }

        refused("geo-model 不是对象时拒绝", "\"geo-model\":\"example:geo/x.geo.json\"", "geo-model");
        refused("缺少 model 时拒绝", "\"geo-model\":{\"texture\":\"example:textures/x.png\"}", "model");
        refused("缺少 texture 时拒绝", "\"geo-model\":{\"model\":\"example:geo/x.geo.json\"}", "texture");
        refused("model 不是字符串时拒绝",
                "\"geo-model\":{\"model\":3,\"texture\":\"example:textures/x.png\"}", "model");
        refused("model 不是合法 ResourceLocation 时拒绝",
                "\"geo-model\":{\"model\":\"not a valid id\",\"texture\":\"example:textures/x.png\"}", "model");
        refused("geo-model 里的拼错键被拒绝",
                "\"geo-model\":{\"model\":\"example:geo/x.geo.json\",\"texture\":\"example:textures/x.png\","
                        + "\"animaton\":\"example:animations/x.animation.json\"}", "animaton");
        refused("idle 是空字符串时拒绝",
                "\"geo-model\":{\"model\":\"example:geo/x.geo.json\",\"texture\":\"example:textures/x.png\","
                        + "\"idle\":\"  \"}", "idle");
        refused("animation 不是字符串时拒绝",
                "\"geo-model\":{\"model\":\"example:geo/x.geo.json\",\"texture\":\"example:textures/x.png\","
                        + "\"animation\":7}", "animation");

        String builder = classFile("com/reborn/modularmachinery/kubejs/MachineBuilderJS");
        String descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;"
                + "Ljava/lang/Object;)Lcom/reborn/modularmachinery/kubejs/MachineBuilderJS;";
        ParallelCraftCheck.report("KubeJS 脚本面声明 geoModel(Object,Object,Object,Object,Object)"
                        + "（不加载类，只读 class 文件的常量池）",
                builder != null && builder.contains("geoModel") && builder.contains(descriptor));

        String renderer = classFile("com/reborn/modularmachinery/client/geo/MachineControllerGeoRenderer");
        ParallelCraftCheck.report("客户端渲染器 GeoObjectRenderer 已随包编译",
                renderer != null && renderer.contains("software/bernie/geckolib/renderer/GeoObjectRenderer"));
        String setup = classFile("com/reborn/modularmachinery/client/ClientSetup");
        ParallelCraftCheck.report("ClientSetup 声明 onRegisterRenderers 入口（实际注册要 geckolib 存在才执行）",
                setup != null && setup.contains("onRegisterRenderers"));
    }

    private static String fullGeo() {
        return "\"geo-model\":{\"model\":\"example:geo/machine.geo.json\","
                + "\"texture\":\"example:textures/geo/machine.png\","
                + "\"animation\":\"example:animations/machine.animation.json\","
                + "\"idle\":\"idle_loop\",\"working\":\"working_loop\"}";
    }

    private static String minimalGeo() {
        return "\"geo-model\":{\"model\":\"example:geo/min.geo.json\","
                + "\"texture\":\"example:textures/min.png\"}";
    }

    private static String namespacedGeo() {
        return "\"geo-model\":{\"model\":\"geo/machine.geo.json\","
                + "\"texture\":\"textures/geo/machine.png\","
                + "\"animation\":\"animations/machine.animation.json\"}";
    }

    private static String explicitMinecraftGeo() {
        return "\"geo-model\":{\"model\":\"minecraft:geo/mc.geo.json\","
                + "\"texture\":\"minecraft:textures/mc.png\"}";
    }

    private static String noGeo() {
        return "";
    }

    private static String machine(String extra) {
        String id = "c7_probe_" + (++sequence);
        String body = extra == null || extra.isBlank() ? "" : "," + extra;
        return "{\"registryname\":\"" + id + "\",\"localizedname\":\"C7\"," + PARTS + body + "}";
    }

    private static MachineDefinition accepted(String what, String extra, boolean strict) {
        try {
            MachineDefinition definition = read(machine(extra), strict);
            ParallelCraftCheck.report(what + "（读出来的是 " + definition.id() + "）", true);
            return definition;
        } catch (RuntimeException refused) {
            ParallelCraftCheck.report(what + " —— 但被拒绝了：" + refused.getMessage(), false);
            return null;
        }
    }

    private static void refused(String what, String extra, String mention) {
        try {
            MachineDefinition definition = read(machine(extra), true);
            ParallelCraftCheck.report(what + " [NOT refused; read " + definition.id() + "]", false);
        } catch (RuntimeException expected) {
            String message = String.valueOf(expected.getMessage());
            // 0.69.0 还不认识 geo-model：strict 会因为「根字段未知」整份拒收，那条消息里也含有 model/geo-model
            // 之类的字样。只按 contains 判会把「根字段没实现」误当成「内部校验抓到了」。
            boolean rootUnknown = message.contains("root field(s) this schema does not know");
            ParallelCraftCheck.report(what + " —— " + message,
                    !rootUnknown && message.contains(mention));
        }
    }

    private static MachineDefinition read(String json, boolean strict) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return MachineSchema.read(root, "mmverify geo-model fixture", Map.of(), strict);
    }

    private static Object geoModel(MachineDefinition definition, String what) {
        try {
            return MachineDefinition.class.getMethod("geoModel").invoke(definition);
        } catch (ReflectiveOperationException missing) {
            ParallelCraftCheck.report(what + "：读 geoModel() 失败 —— " + missing, false);
            return null;
        }
    }

    private static void checkText(Object spec, String method, String expected, String what) {
        if (spec == null) {
            ParallelCraftCheck.report(what, false);
            return;
        }
        Object actual = call(spec, method, what);
        ParallelCraftCheck.report(what + "（" + actual + "）", expected.equals(String.valueOf(actual)));
    }

    private static void checkNull(Object spec, String method, String what) {
        if (spec == null) {
            ParallelCraftCheck.report(what, false);
            return;
        }
        Object actual = call(spec, method, what);
        ParallelCraftCheck.report(what + "（" + actual + "）", actual == null);
    }

    private static Object call(Object target, String method, String what) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException missing) {
            ParallelCraftCheck.report(what + "：调用 " + method + "() 失败 —— " + missing, false);
            return null;
        }
    }

    private static boolean sameText(Object left, Object right, String method) {
        if (left == null || right == null) {
            return false;
        }
        return String.valueOf(call(left, method, "left")).equals(String.valueOf(call(right, method, "right")));
    }

    private static boolean hasMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException missing) {
            return false;
        }
    }

    private static void skip(String what, String why) {
        ParallelCraftCheck.report(what + " [SKIPPED: " + why + "]", false);
    }

    private static String classFile(String internalName) {
        try (InputStream in = GeoModelCheck.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
        } catch (IOException unreadable) {
            return null;
        }
    }
}
