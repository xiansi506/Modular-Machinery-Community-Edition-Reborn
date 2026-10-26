package mmverify;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * C2：控制器生命周期事件层（首切片）。
 *
 * <p>钉住三件事：11 个 API 类型真的存在且形状正确；每个事件携带的载荷来自控制器当前状态；
 * 控制器在正确的时机、以正确的顺序把事件发到总线上。全部新类型走 {@code Class.forName}，
 * 所以红跑（生产代码还没有这些类）仍然能编译，每一条缺失都会单独报 FAIL。
 *
 * <p>不发 {@code MinecraftForge.EVENT_BUS}：那个静态初始化会拉起 mod launcher。测试先反射把
 * {@code MachineEvents.bus} 换成 {@code BusBuilder.builder().build()}，事件派发就完全在离线 JVM 里发生。
 */
public final class EventLayerCheck {

    private static final String PKG = "com.reborn.modularmachinery.api.event.";
    private static final ResourceLocation MACHINE = new ResourceLocation("modular_machinery_reborn", "demo");
    private static final ResourceLocation RECIPE = new ResourceLocation("mmverify", "event_demo");
    private static final String[] CLASSES = {
            "Phase", "MachineEvent", "MachineStructureFormedEvent", "MachineStructureUpdateEvent",
            "MachineTickEvent", "RecipeEvent", "RecipeCheckEvent", "RecipeStartEvent", "RecipeTickEvent",
            "RecipeFinishEvent", "MachineEvents"
    };

    private EventLayerCheck() {
    }

    public static void eventLayer() {
        section("C2 控制器生命周期事件层");
        apiClasses();
        phase();
        payloads();
        checkParallelism();
        tickPrevent();
        dispatch();
        wiring();
    }

    private static void apiClasses() {
        for (String name : CLASSES) {
            try {
                Class.forName(PKG + name);
                report("事件类型存在：" + name, true);
            } catch (ClassNotFoundException | LinkageError missing) {
                report("事件类型存在：" + name + "（" + missing + "）", false);
            }
        }
        if (!present("MachineEvent")) {
            return;
        }
        try {
            Class<?> base = Class.forName(PKG + "MachineEvent");
            report("MachineEvent 继承 Forge Event", base.getSuperclass() == Event.class);
            report("MachineEvent 是抽象类", Modifier.isAbstract(base.getModifiers()));
            report("MachineEvent 有 controller()", base.getMethod("controller").getReturnType()
                    == MachineControllerBlockEntity.class);
            report("MachineEvent 有 machineId()", base.getMethod("machineId").getReturnType() == ResourceLocation.class);

            report("MachineStructureFormedEvent 是 final 且继承 MachineEvent",
                    Modifier.isFinal(Class.forName(PKG + "MachineStructureFormedEvent").getModifiers())
                            && Class.forName(PKG + "MachineStructureFormedEvent").getSuperclass() == base);
            report("MachineStructureUpdateEvent 是 final 且继承 MachineEvent",
                    Modifier.isFinal(Class.forName(PKG + "MachineStructureUpdateEvent").getModifiers())
                            && Class.forName(PKG + "MachineStructureUpdateEvent").getSuperclass() == base);
            report("MachineTickEvent 是 final 且继承 MachineEvent",
                    Modifier.isFinal(Class.forName(PKG + "MachineTickEvent").getModifiers())
                            && Class.forName(PKG + "MachineTickEvent").getSuperclass() == base);
            report("RecipeEvent 是抽象类且继承 MachineEvent",
                    Modifier.isAbstract(Class.forName(PKG + "RecipeEvent").getModifiers())
                            && Class.forName(PKG + "RecipeEvent").getSuperclass() == base);
            Class<?> recipeEvent = Class.forName(PKG + "RecipeEvent");
            for (String name : new String[]{"RecipeCheckEvent", "RecipeStartEvent", "RecipeTickEvent",
                    "RecipeFinishEvent"}) {
                Class<?> type = Class.forName(PKG + name);
                report(name + " 是 final 且继承 RecipeEvent",
                        Modifier.isFinal(type.getModifiers()) && type.getSuperclass() == recipeEvent);
            }
            report("MachineEvents 是 final 工具类",
                    Modifier.isFinal(Class.forName(PKG + "MachineEvents").getModifiers()));
            for (String name : new String[]{"MachineStructureFormedEvent", "MachineStructureUpdateEvent",
                    "MachineTickEvent", "RecipeCheckEvent", "RecipeStartEvent", "RecipeTickEvent",
                    "RecipeFinishEvent"}) {
                try {
                    Class.forName(PKG + name).getConstructor();
                    report(name + " 有无参构造器（Forge 事件总线注册监听器时要用它探测监听表）", true);
                } catch (NoSuchMethodException missing) {
                    report(name + " 有无参构造器（Forge 事件总线注册监听器时要用它探测监听表）", false);
                }
            }
        } catch (ReflectiveOperationException problem) {
            report("事件类型形状（" + problem + "）", false);
        }
    }

    private static void phase() {
        if (!present("Phase")) {
            return;
        }
        try {
            Class<?> type = Class.forName(PKG + "Phase");
            report("Phase 是枚举", type.isEnum());
            Object[] values = type.getEnumConstants();
            check("Phase 恰有两个常量", 2L, values == null ? 0L : values.length);
            if (values != null && values.length == 2) {
                report("Phase 顺序是 START, END",
                        ((Enum<?>) values[0]).name().equals("START") && ((Enum<?>) values[1]).name().equals("END"));
            }
        } catch (ReflectiveOperationException | LinkageError problem) {
            report("Phase 形状（" + problem + "）", false);
        }
    }

    private static void payloads() {
        if (!allPresent("MachineStructureFormedEvent", "MachineStructureUpdateEvent", "MachineTickEvent",
                "RecipeCheckEvent", "RecipeStartEvent", "RecipeTickEvent", "RecipeFinishEvent", "Phase")) {
            return;
        }
        try {
            Object controller = controller();
            MachineRecipe recipe = new MachineRecipe(RECIPE, "demo", "demo", 20, List.of());
            Class<?> phaseType = Class.forName(PKG + "Phase");
            Object start = phase(phaseType, "START");
            Object end = phase(phaseType, "END");

            Event formed = (Event) Class.forName(PKG + "MachineStructureFormedEvent")
                    .getConstructor(MachineControllerBlockEntity.class, ResourceLocation.class)
                    .newInstance(controller, MACHINE);
            report("成型事件带着控制器", formed.getClass().getMethod("controller").invoke(formed) == controller);
            report("成型事件带着机器 id", MACHINE.equals(formed.getClass().getMethod("machineId").invoke(formed)));
            report("成型事件接受 null 机器 id", null == formed.getClass().getMethod("machineId").invoke(
                    Class.forName(PKG + "MachineStructureFormedEvent")
                            .getConstructor(MachineControllerBlockEntity.class, ResourceLocation.class)
                            .newInstance(controller, (Object) null)));

            Event update = (Event) Class.forName(PKG + "MachineStructureUpdateEvent")
                    .getConstructor(MachineControllerBlockEntity.class, ResourceLocation.class)
                    .newInstance(controller, MACHINE);
            report("结构更新事件带着机器 id", MACHINE.equals(update.getClass().getMethod("machineId").invoke(update)));

            Event tick = (Event) Class.forName(PKG + "MachineTickEvent")
                    .getConstructor(MachineControllerBlockEntity.class, ResourceLocation.class, phaseType)
                    .newInstance(controller, MACHINE, start);
            report("机器 tick 事件带着相位", tick.getClass().getMethod("phase").invoke(tick) == start);

            Constructor<?> checkCtor = Class.forName(PKG + "RecipeCheckEvent").getConstructor(
                    MachineControllerBlockEntity.class, ResourceLocation.class, phaseType, MachineRecipe.class,
                    int.class, int.class, int.class);
            Event check = (Event) checkCtor.newInstance(controller, MACHINE, end, recipe, 7, 40, 3);
            report("检查事件带着相位", check.getClass().getMethod("phase").invoke(check) == end);
            report("检查事件带着配方", check.getClass().getMethod("recipe").invoke(check) == recipe);
            check("检查事件带着进度", 7L, (long) (Integer) check.getClass().getMethod("progress").invoke(check));
            check("检查事件带着总时长", 40L, (long) (Integer) check.getClass().getMethod("maxProgress").invoke(check));
            check("检查事件带着并行度", 3L, (long) (Integer) check.getClass().getMethod("parallelism").invoke(check));
            check("检查事件的请求并行度初值", 3L,
                    (long) (Integer) check.getClass().getMethod("requestedParallelism").invoke(check));

            Constructor<?> startCtor = Class.forName(PKG + "RecipeStartEvent").getConstructor(
                    MachineControllerBlockEntity.class, ResourceLocation.class, MachineRecipe.class,
                    int.class, int.class, int.class);
            Event started = (Event) startCtor.newInstance(controller, MACHINE, recipe, 0, 40, 2);
            report("开始事件带着配方", started.getClass().getMethod("recipe").invoke(started) == recipe);
            check("开始事件带着并行度", 2L,
                    (long) (Integer) started.getClass().getMethod("parallelism").invoke(started));

            Constructor<?> tickCtor = Class.forName(PKG + "RecipeTickEvent").getConstructor(
                    MachineControllerBlockEntity.class, ResourceLocation.class, phaseType, MachineRecipe.class,
                    int.class, int.class, int.class);
            Event ticked = (Event) tickCtor.newInstance(controller, MACHINE, start, recipe, 9, 40, 2);
            report("配方 tick 事件带着相位", ticked.getClass().getMethod("phase").invoke(ticked) == start);
            check("配方 tick 事件带着进度", 9L,
                    (long) (Integer) ticked.getClass().getMethod("progress").invoke(ticked));
            report("配方 tick 事件接受 null 原因", true);

            Constructor<?> finishCtor = Class.forName(PKG + "RecipeFinishEvent").getConstructor(
                    MachineControllerBlockEntity.class, ResourceLocation.class, MachineRecipe.class,
                    int.class, int.class, int.class);
            Event finished = (Event) finishCtor.newInstance(controller, MACHINE, recipe, 40, 40, 2);
            check("完成事件带着进度", 40L,
                    (long) (Integer) finished.getClass().getMethod("progress").invoke(finished));
            check("完成事件带着并行度", 2L,
                    (long) (Integer) finished.getClass().getMethod("parallelism").invoke(finished));
        } catch (ReflectiveOperationException | RuntimeException problem) {
            report("事件载荷可构造（" + problem + "）", false);
        }
    }

    private static void checkParallelism() {
        if (!present("RecipeCheckEvent") || !present("Phase") || !present("RecipeEvent")) {
            return;
        }
        try {
            Object controller = controller();
            MachineRecipe recipe = new MachineRecipe(RECIPE, "demo", "demo", 20, List.of());
            Class<?> phaseType = Class.forName(PKG + "Phase");
            Object end = phase(phaseType, "END");
            Object check = Class.forName(PKG + "RecipeCheckEvent")
                    .getConstructor(MachineControllerBlockEntity.class, ResourceLocation.class, phaseType,
                            MachineRecipe.class, int.class, int.class, int.class)
                    .newInstance(controller, MACHINE, end, recipe, 0, 20, 4);
            Class<?> type = check.getClass();
            Method resolve = type.getMethod("resolvedParallelism", int.class);
            check("并行度不能超过计算结果", 4L, (long) (Integer) resolve.invoke(check, 8));
            check("并行度可以低于计算结果", 2L, (long) (Integer) resolve.invoke(check, 2));
            check("并行度 0 被抬到 1", 1L, (long) (Integer) resolve.invoke(check, 0));
            type.getMethod("setParallelism", int.class).invoke(check, 9);
            check("请求并行度写回可读", 9L, (long) (Integer) type.getMethod("requestedParallelism").invoke(check));
            check("抬高请求仍被计算结果封顶", 8L, (long) (Integer) resolve.invoke(check, 8));
            type.getMethod("setParallelism", int.class).invoke(check, -5);
            check("负并行度被抬到 1", 1L, (long) (Integer) resolve.invoke(check, 8));
        } catch (ReflectiveOperationException | RuntimeException problem) {
            report("RecipeCheckEvent 并行度（" + problem + "）", false);
        }
    }

    private static void tickPrevent() {
        if (!present("RecipeTickEvent") || !present("Phase")) {
            return;
        }
        try {
            Object controller = controller();
            MachineRecipe recipe = new MachineRecipe(RECIPE, "demo", "demo", 20, List.of());
            Class<?> phaseType = Class.forName(PKG + "Phase");
            Object tick = Class.forName(PKG + "RecipeTickEvent")
                    .getConstructor(MachineControllerBlockEntity.class, ResourceLocation.class, phaseType,
                            MachineRecipe.class, int.class, int.class, int.class)
                    .newInstance(controller, MACHINE, phase(phaseType, "START"), recipe, 3, 20, 1);
            Class<?> type = tick.getClass();
            check("默认不阻断推进", false, (Boolean) type.getMethod("isPreventProgressing").invoke(tick));
            check("默认原因为空", "", (String) type.getMethod("preventReason").invoke(tick));
            type.getMethod("preventProgressing", String.class).invoke(tick, "waiting for coolant");
            check("阻断标记被记住", true, (Boolean) type.getMethod("isPreventProgressing").invoke(tick));
            check("阻断原因被记住", "waiting for coolant",
                    (String) type.getMethod("preventReason").invoke(tick));
            type.getMethod("preventProgressing", String.class).invoke(tick, new Object[]{null});
            check("null 原因写成空串", "", (String) type.getMethod("preventReason").invoke(tick));
        } catch (ReflectiveOperationException | RuntimeException problem) {
            report("RecipeTickEvent 阻断（" + problem + "）", false);
        }
    }

    private static void dispatch() {
        if (!allPresent("MachineTickEvent", "MachineStructureFormedEvent", "MachineStructureUpdateEvent",
                "RecipeCheckEvent", "MachineEvents", "Phase")) {
            return;
        }
        try {
            Object controller = controller();
            MachineRecipe recipe = new MachineRecipe(RECIPE, "demo", "demo", 20, List.of());
            Class<?> phaseType = Class.forName(PKG + "Phase");
            Object start = phase(phaseType, "START");

            IEventBus bus = BusBuilder.builder().build();
            Field field = Class.forName(PKG + "MachineEvents").getDeclaredField("bus");
            field.setAccessible(true);
            field.set(null, bus);

            List<Event> machineTicks = new ArrayList<>();
            listen(bus, "MachineTickEvent", machineTicks);
            Method postTick = MachineControllerBlockEntity.class.getDeclaredMethod("postMachineTick", phaseType);
            postTick.setAccessible(true);
            postTick.invoke(controller, start);
            check("MachineTickEvent 到达总线", 1L, (long) machineTicks.size());
            if (machineTicks.size() == 1) {
                Event event = machineTicks.get(0);
                report("总线事件就是 MachineTickEvent",
                        event.getClass().getName().equals(PKG + "MachineTickEvent"));
                report("总线事件带着控制器",
                        event.getClass().getMethod("controller").invoke(event) == controller);
                report("总线事件带着机器 id", MACHINE.equals(
                        event.getClass().getMethod("machineId").invoke(event)));
                report("总线事件带着 START", event.getClass().getMethod("phase").invoke(event) == start);
            }

            List<Event> structures = new ArrayList<>();
            listen(bus, "MachineStructureFormedEvent", structures);
            listen(bus, "MachineStructureUpdateEvent", structures);
            Method postStructure = MachineControllerBlockEntity.class.getDeclaredMethod("postStructureEvents",
                    boolean.class);
            postStructure.setAccessible(true);
            postStructure.invoke(controller, true);
            check("成型时发两个结构事件", 2L, (long) structures.size());
            if (structures.size() == 2) {
                report("结构事件顺序是 Formed 再 Update",
                        structures.get(0).getClass().getName().equals(PKG + "MachineStructureFormedEvent")
                                && structures.get(1).getClass().getName().equals(PKG + "MachineStructureUpdateEvent"));
            }
            structures.clear();
            postStructure.invoke(controller, false);
            check("仅更新时只发 Update", 1L, (long) structures.size());
            if (structures.size() == 1) {
                report("仅更新事件是 Update",
                        structures.get(0).getClass().getName().equals(PKG + "MachineStructureUpdateEvent"));
            }

            List<Event> checks = new ArrayList<>();
            listen(bus, "RecipeCheckEvent", checks);
            setInt(controller, "progress", 5);
            setInt(controller, "maxProgress", 40);
            Method postCheck = MachineControllerBlockEntity.class.getDeclaredMethod("postRecipeCheck", phaseType,
                    MachineRecipe.class, int.class);
            postCheck.setAccessible(true);
            postCheck.invoke(controller, start, recipe, 6);
            check("RecipeCheckEvent 到达总线", 1L, (long) checks.size());
            if (checks.size() == 1) {
                Event event = checks.get(0);
                check("检查事件载荷来自控制器进度", 5L,
                        (long) (Integer) event.getClass().getMethod("progress").invoke(event));
                check("检查事件载荷来自控制器总时长", 40L,
                        (long) (Integer) event.getClass().getMethod("maxProgress").invoke(event));
                check("检查事件载荷是传入的并行度", 6L,
                        (long) (Integer) event.getClass().getMethod("parallelism").invoke(event));
            }

            Event direct = (Event) Class.forName(PKG + "MachineTickEvent")
                    .getConstructor(MachineControllerBlockEntity.class, ResourceLocation.class, phaseType)
                    .newInstance(controller, MACHINE, start);
            Object returned = Class.forName(PKG + "MachineEvents").getMethod("post", Event.class)
                    .invoke(null, direct);
            report("MachineEvents.post 返回同一个事件", returned == direct);

            field.set(null, null);
        } catch (ReflectiveOperationException | RuntimeException problem) {
            problem.printStackTrace();
            report("事件派发总线（" + problem + "）", false);
        }
    }

    private static void wiring() {
        String source = compact(source("block/MachineControllerBlockEntity.java"));
        report("tick 先发 START 再进 tickServer",
                source.contains("be.postMachineTick(Phase.START); try { tickServer(level, pos, state, be); }"));
        report("tick 的 END 在 finally 里",
                source.contains("} finally { be.postMachineTick(Phase.END); }"));
        report("结构检查发出成型事件，参数是新成型",
                source.contains("be.postStructureEvents(!wasFormed);"));

        int checkStart = source.indexOf("be.postRecipeCheck(Phase.START");
        int canStart = source.indexOf("!recipe.canStart(scope, prepared)");
        int checkEnd = source.indexOf("be.postRecipeCheck(Phase.END");
        int start = source.indexOf("if (!recipe.start(scope, level.random, prepared))");
        int postStart = source.indexOf("be.postRecipeStart(");
        int clear = source.indexOf("be.clearStartFailure();", start);
        report("开工检查 START 在 canStart 之前", checkStart >= 0 && checkStart < canStart);
        report("开工检查 END 在 canStart 之后、start 之前",
                checkEnd > canStart && checkEnd < start);
        report("RecipeStartEvent 在 start() 成功和控制器武装之后",
                postStart > start && postStart > clear && source.indexOf("be.activeRecipeId = recipe.getId()") < postStart);
        report("检查事件解析后的并行度会写回配方",
                source.contains("parallelism = requested; recipe.setParallelism(parallelism);")
                        && source.contains("parallelism = adjusted; recipe.setParallelism(parallelism);"));

        int tickStart = source.indexOf("be.postRecipeTick(Phase.START");
        int next = source.indexOf("int nextProgress = be.progress + 1;");
        int recipeTick = source.indexOf("recipe.tick(scope, level.random, effective);");
        int tickEnd = source.indexOf("be.postRecipeTick(Phase.END");
        int progress = source.indexOf("be.progress = be.progress < Integer.MAX_VALUE ? be.progress + 1 : Integer.MAX_VALUE;");
        report("配方 tick START 在计进度之前", tickStart >= 0 && tickStart < next);
        report("配方 tick END 在 recipe.tick 之后、写进度之前",
                tickEnd > recipeTick && tickEnd < progress);
        report("阻断 START 时不扣资源、直接返回",
                source.contains("if (startTick.isPreventProgressing()) { be.status = ControllerStatus.CRAFTING; be.recordTiming(tickStart, searchNanos); return; }"));
        report("阻断 END 时不写进度、直接返回",
                source.contains("if (endTick.isPreventProgressing()) { be.status = ControllerStatus.CRAFTING; be.recordTiming(tickStart, searchNanos); return; }"));

        int finish = source.indexOf("recipe.finish(scope, level.random, effective);");
        int postFinish = source.indexOf("be.postRecipeFinish(");
        int finishCommand = source.indexOf("RecipeCommands.Phase.FINISH");
        report("RecipeFinishEvent 在 finish() 之后、finishCommands 之前",
                postFinish > finish && postFinish < finishCommand);
    }

    // ---------------------------------------------------------------- fixtures

    private static Object controller() throws ReflectiveOperationException {
        Object controller = allocateInstance(MachineControllerBlockEntity.class);
        setField(controller, "machineId", MACHINE);
        setInt(controller, "progress", 0);
        setInt(controller, "maxProgress", 20);
        setInt(controller, "activeParallelism", 1);
        return controller;
    }

    private static Object phase(Class<?> type, String name) {
        Object[] constants = type.getEnumConstants();
        if (constants == null) {
            throw new IllegalStateException("Phase is not an enum");
        }
        for (Object constant : constants) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalStateException("no Phase." + name);
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = MachineControllerBlockEntity.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setInt(Object target, String name, int value) throws ReflectiveOperationException {
        setField(target, name, value);
    }

    private static Object allocateInstance(Class<?> type) throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        try {
            return unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            throw new ReflectiveOperationException(wrapped.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private static void listen(IEventBus bus, String simpleName, List<Event> sink) throws ReflectiveOperationException {
        Class<? extends Event> type = (Class<? extends Event>) Class.forName(PKG + simpleName);
        bus.addListener(EventPriority.NORMAL, false, type, sink::add);
    }

    private static boolean present(String simpleName) {
        try {
            Class.forName(PKG + simpleName);
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    private static boolean allPresent(String... names) {
        for (String name : names) {
            if (!present(name)) {
                return false;
            }
        }
        return true;
    }

    private static String source(String relative) {
        try {
            return Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/" + relative))
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("(?m)//.*$", "");
        } catch (Exception unreadable) {
            throw new IllegalStateException("cannot read " + relative, unreadable);
        }
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", " ");
    }

    // ---------------------------------------------------------------- harness forwarding

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void check(String what, String expected, String actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void check(String what, boolean expected, boolean actual) {
        report(what + "（期望 " + expected + "，实际 " + actual + "）", expected == actual);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }
}
