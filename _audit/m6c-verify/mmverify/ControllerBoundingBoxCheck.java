package mmverify;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.reborn.modularmachinery.block.MachineControllerBlock;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineRegistry;
import com.reborn.modularmachinery.machine.MachineSchema;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E6：{@code controller-bounding-box}（控制器碰撞箱/选中箱）。
 *
 * <h2>旧版语义（勘测已核对原文）</h2>
 *
 * <p>{@code DynamicMachine.java:81} 用 {@code Block.FULL_BLOCK_AABB} 起步，:595-607 从根字段读六个 double
 * 构造 {@code AxisAlignedBB}，:660-663 只在字段存在时覆盖。消费者是 {@code BlockController.java:309-328}：
 * {@code getBoundingBox} 返回 {@code parentMachine} 的盒子；{@code isFullBlock}/{@code isFullCube} 还要求
 * 这个盒子等于整块。于是三条边界必须一起复制：
 *
 * <ol>
 *   <li>字段缺省 = 整块；</li>
 *   <li>只有<strong>按机器注册</strong>的控制器读这台机器的盒子；generic 控制器（旧版无参构造、
 *       {@code parentMachine == null}）永远整块；</li>
 *   <li>旧版不检查六个数的范围，也不检查 min/max 顺序——它只是把六个数塞进 AABB。这里同样不夹取，
 *       否则一份旧数据包会静默变成另一个盒子。</li>
 * </ol>
 *
 * <h2>为什么红跑要先于生产代码</h2>
 *
 * <p>本节所有新 API 都走反射（{@code MachineRootFields.controllerBoundingBox}、
 * {@code MachineDefinition.controllerBoundingBox/withControllerBoundingBox}），所以旧源码仍能编译，
 * 而失败会逐条说明缺的是哪一件。方块形状本身用现有 {@code getShape} 调用，不依赖新方法名。
 */
final class ControllerBoundingBoxCheck {

    private static final String PARTS =
            "\"parts\":[{\"x\":0,\"y\":0,\"z\":0,\"elements\":\"minecraft:stone\"},"
                    + "{\"x\":1,\"y\":0,\"z\":0,\"elements\":\"minecraft:iron_block\"}]";

    private static final AABB FULL = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);
    private static final AABB SMALL = new AABB(0.25, 0.0, 0.25, 0.75, 0.5, 0.75);

    private ControllerBoundingBoxCheck() {
    }

    static void controllerBoundingBox() {
        ParallelCraftCheck.section("E6：controller-bounding-box 现在有消费者（0.81.0）");

        // ---- 1) 名单契约：它必须仍是已知字段，并且已经从「认得但不求值」里离开 ----------------
        ParallelCraftCheck.report("KNOWN_ROOT_FIELDS 仍认得 'controller-bounding-box'（离开未实现名单不等于离开 schema）",
                MachineSchema.KNOWN_ROOT_FIELDS.contains("controller-bounding-box"));
        String[] unimplemented = unimplementedRootFields();
        if (unimplemented == null) {
            ParallelCraftCheck.report("读不到 UNIMPLEMENTED_ROOT_FIELDS（反射被拒）——这份结构断言没有证据", false);
        } else {
            ParallelCraftCheck.check("E6 之后「认得但不求值」名单为空（还有字段就说明这一节该重写）",
                    0, unimplemented.length);
            for (String field : unimplemented) {
                ParallelCraftCheck.report("'" + field + "' 仍在未实现名单里，就必须同时在 KNOWN_ROOT_FIELDS 里",
                        MachineSchema.KNOWN_ROOT_FIELDS.contains(field));
            }
        }

        // ---- 2) 缺省：没有字段 = 整块 ----------------------------------------------------------
        MachineDefinition defaultDefinition = readAccepted(
                "没有 controller-bounding-box 的定义仍能读出来", definition("e6_default", ""));
        if (defaultDefinition != null) {
            reportBox("缺省时控制器盒子是整块（旧版从 Block.FULL_BLOCK_AABB 起步）",
                    boxOf(defaultDefinition, "缺省定义的 getter"), FULL);
        }

        // ---- 3) 合法六值：逐位保留，strict 与非 strict 都接受 ------------------------------------
        MachineDefinition valid = readAccepted("六个数的 controller-bounding-box 定义被接受",
                definition("e6_valid", "\"controller-bounding-box\":[0.25,0.0,0.25,0.75,0.5,0.75]"));
        if (valid != null) {
            reportBox("六个坐标逐位读进定义", boxOf(valid, "合法定义的 getter"), SMALL);
        }
        MachineDefinition strict = readAccepted("strict（脚本面）路径也接受这个字段",
                definition("e6_strict", "\"controller-bounding-box\":[0.25,0.0,0.25,0.75,0.5,0.75]"), true);
        if (strict != null) {
            reportBox("strict 路径读出的盒子与数据包路径相同",
                    boxOf(strict, "strict 定义的 getter"), SMALL);
        }

        // ---- 4) 旧版接受数字字符串（JsonArray#getAsDouble 的既有行为）----------------------------
        MachineDefinition numericStrings = readAccepted(
                "数字字符串（\"0.25\" 等）仍被接受，与旧版 getAsDouble 一致",
                definition("e6_strings", "\"controller-bounding-box\":[\"0.25\",\"0\",\"0.25\",\"0.75\",\"0.5\",\"0.75\"]"));
        if (numericStrings != null) {
            reportBox("数字字符串最终仍是同样的六个数",
                    boxOf(numericStrings, "数字字符串定义的 getter"), SMALL);
        }

        // ---- 5) 越界不夹取：旧版只是把数塞进 AABB ------------------------------------------------
        MachineDefinition outside = readAccepted(
                "超出 [0,1] 的六个数不被拒绝（旧版也没有范围检查）",
                definition("e6_outside", "\"controller-bounding-box\":[-1.0,-2.0,-3.0,2.0,3.0,4.0]"));
        if (outside != null) {
            reportBox("越界坐标逐位保留，没有被夹到 0..1",
                    boxOf(outside, "越界定义的 getter"), new AABB(-1.0, -2.0, -3.0, 2.0, 3.0, 4.0));
        }

        // ---- 6) 错误：长度、非数组、非数字，都必须带旧版原文开头 --------------------------------
        readRefused("五个数被拒绝（旧版 size() != 6 的分支）",
                definition("e6_five", "\"controller-bounding-box\":[0.0,0.0,0.0,1.0,1.0]"),
                "Invalid 'controllerBoundingBox'!");
        readRefused("七个数也被拒绝",
                definition("e6_seven", "\"controller-bounding-box\":[0.0,0.0,0.0,1.0,1.0,1.0,1.0]"),
                "Invalid 'controllerBoundingBox'!");
        readRefused("不是数组时被拒绝（旧版会在 getAsJsonArray 处失败）",
                definition("e6_not_array", "\"controller-bounding-box\":0.5"),
                "Invalid 'controllerBoundingBox'!");
        readRefused("数组里有布尔值时被拒绝（旧版 getAsDouble 会抛 NumberFormatException）",
                definition("e6_bool", "\"controller-bounding-box\":[0.0,0.0,0.0,1.0,1.0,true]"),
                "Invalid 'controllerBoundingBox'!");
        readRefused("数组里有对象/数组时被拒绝",
                definition("e6_object", "\"controller-bounding-box\":[0.0,0.0,0.0,1.0,1.0,{}]"),
                "Invalid 'controllerBoundingBox'!");
        readRefused("数组里有 null 时被拒绝，而不是变成默认整块",
                definition("e6_null", "\"controller-bounding-box\":[0.0,0.0,0.0,1.0,1.0,null]"),
                "Invalid 'controllerBoundingBox'!");

        // ---- 7) with* 的每个复制点都要带上新字段 ------------------------------------------------
        if (valid != null) {
            reportBox("withColor 副本保留控制器盒子",
                    boxOf(valid.withColor(0x123456), "withColor 的 getter"), SMALL);
            reportBox("withGeoModel 副本保留控制器盒子",
                    boxOf(valid.withGeoModel(null), "withGeoModel 的 getter"), SMALL);
            reportBox("withMultiBlockModifiers 副本保留控制器盒子",
                    boxOf(valid.withMultiBlockModifiers(List.of()), "withMultiBlockModifiers 的 getter"), SMALL);
            reportBox("withHideComponentsWhenFormed 副本保留控制器盒子",
                    boxOf(valid.withHideComponentsWhenFormed(false), "withHideComponentsWhenFormed 的 getter"), SMALL);
            reportBox("withPrefix 副本保留控制器盒子",
                    boxOf(valid.withPrefix("E6"), "withPrefix 的 getter"), SMALL);

            Method withBox = method(MachineDefinition.class, "withControllerBoundingBox", AABB.class);
            if (withBox == null) {
                ParallelCraftCheck.report("MachineDefinition 没有 withControllerBoundingBox(AABB) 入口", false);
            } else {
                try {
                    MachineDefinition changed = (MachineDefinition) withBox.invoke(valid,
                            new AABB(0.0, 0.0, 0.0, 0.5, 0.5, 0.5));
                    reportBox("withControllerBoundingBox 返回的副本换成新盒子",
                            boxOf(changed, "withControllerBoundingBox 的 getter"),
                            new AABB(0.0, 0.0, 0.0, 0.5, 0.5, 0.5));
                    reportBox("...并且原定义没有被改动（不可变定义）",
                            boxOf(valid, "原定义的 getter"), SMALL);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    ParallelCraftCheck.report("调用 withControllerBoundingBox 失败：" + failure, false);
                }
            }
        }

        // ---- 8) 方块形状：只对 bound 控制器生效，generic 永远整块 -------------------------------
        if (valid != null) {
            shapeTests(valid);
        }
    }

    /**
     * 方块形状那段。注册表是全局的，所以先把现有内容与源 JSON 一起存档，结束时原样放回；
     * 否则后面任何依赖注册表为空的断言都会无缘无故地失败。
     */
    private static void shapeTests(MachineDefinition definition) {
        Map<ResourceLocation, MachineDefinition> saved = new LinkedHashMap<>();
        for (MachineDefinition machine : MachineRegistry.clientAll()) {
            saved.put(machine.id(), machine);
        }
        try {
            MachineRegistry.replaceClient(Map.of(definition.id(), definition));
            MachineControllerBlock bound = newBlock(definition.id());
            MachineControllerBlock generic = newBlock(null);
            MachineControllerBlock missing = newBlock(new ResourceLocation("mmverify", "e6_no_such_machine"));

            reportBox("bound 控制器的选中箱来自定义", shapeOf(bound).bounds(), SMALL);
            reportBox("bound 控制器的碰撞箱同样来自定义", collisionShapeOf(bound).bounds(), SMALL);
            reportBox("generic 控制器（旧版 parentMachine == null）始终整块",
                    shapeOf(generic).bounds(), FULL);
            reportBox("bound 了但定义不在注册表里时退回整块，而不是空形状或崩溃",
                    shapeOf(missing).bounds(), FULL);

            MachineDefinition fullDefinition = readAccepted(
                    "盒子显式写成整块的定义被接受",
                    definition("e6_full", "\"controller-bounding-box\":[0.0,0.0,0.0,1.0,1.0,1.0]"));
            if (fullDefinition != null) {
                MachineRegistry.replaceClient(Map.of(fullDefinition.id(), fullDefinition));
                MachineControllerBlock fullBlock = newBlock(fullDefinition.id());
                reportBox("显式整块的定义得到的仍是整块形状", shapeOf(fullBlock).bounds(), FULL);
            }
        } catch (RuntimeException failure) {
            ParallelCraftCheck.report("方块形状测试抛出异常：" + failure, false);
        } finally {
            MachineRegistry.replaceClient(saved);
        }
    }

    private static String definition(String id, String extra) {
        String suffix = extra.isEmpty() ? "" : "," + extra;
        return "{\"registryname\":\"" + id + "\",\"localizedname\":\"E6\"," + PARTS + suffix + "}";
    }

    private static MachineDefinition read(String json, boolean strict) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return MachineSchema.read(root, "mmverify E6 fixture", Map.of(), strict);
    }

    private static MachineDefinition readAccepted(String what, String json) {
        return readAccepted(what, json, false);
    }

    private static MachineDefinition readAccepted(String what, String json, boolean strict) {
        try {
            MachineDefinition definition = read(json, strict);
            ParallelCraftCheck.report(what + "（读出来的是 " + definition.id() + "）", true);
            return definition;
        } catch (RuntimeException refused) {
            ParallelCraftCheck.report(what + " —— 但被拒绝了：" + refused.getMessage(), false);
            return null;
        }
    }

    private static void readRefused(String what, String json, String mention) {
        try {
            MachineDefinition definition = read(json, false);
            ParallelCraftCheck.report(what + " [NOT refused; read " + definition.id() + "]", false);
        } catch (RuntimeException expected) {
            String message = String.valueOf(expected.getMessage());
            ParallelCraftCheck.report(what + " —— " + message, message.contains(mention));
        }
    }

    private static AABB boxOf(MachineDefinition definition, String label) {
        Method getter = method(MachineDefinition.class, "controllerBoundingBox");
        if (getter == null) {
            ParallelCraftCheck.report(label + "：MachineDefinition 没有 controllerBoundingBox()", false);
            return null;
        }
        try {
            Object value = getter.invoke(definition);
            if (!(value instanceof AABB box)) {
                ParallelCraftCheck.report(label + "：controllerBoundingBox() 返回的不是 AABB，而是 " + value, false);
                return null;
            }
            return box;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            ParallelCraftCheck.report(label + "：读取 controllerBoundingBox() 失败：" + failure, false);
            return null;
        }
    }

    private static void reportBox(String what, AABB actual, AABB expected) {
        boolean ok = actual != null
                && same(actual.minX, expected.minX) && same(actual.minY, expected.minY)
                && same(actual.minZ, expected.minZ) && same(actual.maxX, expected.maxX)
                && same(actual.maxY, expected.maxY) && same(actual.maxZ, expected.maxZ);
        ParallelCraftCheck.report(what + "（实际 " + describe(actual) + "，期望 " + describe(expected) + "）", ok);
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) < 1.0E-9;
    }

    private static String describe(AABB box) {
        if (box == null) {
            return "无（缺少 getter）";
        }
        return "[" + box.minX + ", " + box.minY + ", " + box.minZ + ", "
                + box.maxX + ", " + box.maxY + ", " + box.maxZ + "]";
    }

    private static VoxelShape shapeOf(MachineControllerBlock block) {
        // state 只用于未覆盖 getShape 的旧实现的默认路径（它会转发到 state.getShape），
        // 新实现忽略 state；用石头状态保证默认路径不会 NPE。
        BlockState state = Blocks.STONE.defaultBlockState();
        return block.getShape(state, null, BlockPos.ZERO, CollisionContext.empty());
    }

    private static VoxelShape collisionShapeOf(MachineControllerBlock block) {
        BlockState state = Blocks.STONE.defaultBlockState();
        return block.getCollisionShape(state, null, BlockPos.ZERO, CollisionContext.empty());
    }

    /**
     * 方块注册表在 Bootstrap 之后已经冻结，真实构造会抛 {@code IllegalStateException: Registry is already frozen}。
     * 这里用 Unsafe 分配实例、填好形状实现真正会读的两个字段（{@code boundMachine} 与
     * {@code BlockBehaviour.hasCollision}），避开构造器里的 {@code registerDefaultState}。
     *
     * <p>这不是在测构造器——构造器只赋值并注册默认状态，形状逻辑不经过它。
     */
    private static MachineControllerBlock newBlock(ResourceLocation boundMachine) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            MachineControllerBlock block = (MachineControllerBlock) allocateInstance.invoke(unsafe, MachineControllerBlock.class);

            Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
            Method putBoolean = unsafeClass.getMethod("putBoolean", Object.class, long.class, boolean.class);

            long boundOffset = (long) objectFieldOffset.invoke(unsafe,
                    MachineControllerBlock.class.getDeclaredField("boundMachine"));
            putObject.invoke(unsafe, block, boundOffset, boundMachine);

            long collisionOffset = (long) objectFieldOffset.invoke(unsafe,
                    net.minecraft.world.level.block.state.BlockBehaviour.class.getDeclaredField("hasCollision"));
            putBoolean.invoke(unsafe, block, collisionOffset, true);
            return block;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new IllegalStateException("无法用 Unsafe 造出控制器实例：" + failure, failure);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return null;
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
}
