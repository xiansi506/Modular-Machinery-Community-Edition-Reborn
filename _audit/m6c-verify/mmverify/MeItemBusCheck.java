package mmverify;

import com.reborn.modularmachinery.block.HatchKind;
import com.reborn.modularmachinery.block.HatchTier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraftforge.items.ItemStackHandler;

import java.nio.file.Files;
import java.nio.file.Path;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * C4（0.73.0）：ME 物品输入/输出总线。
 *
 * <p>旧版的总线由 AE2 的网络代理 + {@code IOInventory} 组成；本版把「往网络里塞 / 从网络里拿」的
 * 搬运算法抽成不依赖 AE2 类型的 {@code MeItemBusLogic}，AE2 只在适配器里出现。这样离线复现器
 * （classpath 里没有 AE2）也能钉住输入总线的四种数量关系、空配置回送、失败计数，以及输出总线的
 * 整叠推出与部分接收。
 *
 * <p>这些入口今天都还不存在，所以全部用反射找：红跑应当逐条报 FAIL，而不是在 javac 就停下。
 */
final class MeItemBusCheck {

    private static final String LOGIC = "com.reborn.modularmachinery.block.MeItemBusLogic";
    private static final String MENU = "com.reborn.modularmachinery.menu.MeItemBusMenu";

    private static Class<?> storageInterface;

    private MeItemBusCheck() {
    }

    static void meItemBuses() {
        section("C4：ME 物品输入/输出总线（0.73.0）");

        HatchKind input = kind("ME_ITEM_INPUT");
        HatchKind output = kind("ME_ITEM_OUTPUT");
        HatchTier inputTier = tier("ME_INPUT");
        HatchTier outputTier = tier("ME_OUTPUT");

        report("HatchKind 有 ME_ITEM_INPUT", input != null);
        report("HatchKind 有 ME_ITEM_OUTPUT", output != null);
        report("HatchTier 有 ME_INPUT（16 槽，对应旧版输入总线）", inputTier != null && inputTier.itemSlots() == 16);
        report("HatchTier 有 ME_OUTPUT（36 槽，对应旧版输出总线）", outputTier != null && outputTier.itemSlots() == 36);

        if (input != null && inputTier != null) {
            report("ME 输入总线属于 ITEM 家族", input.family() == HatchKind.Family.ITEM);
            report("ME 输入总线 isInput", input.isInput());
            report("ME 输入总线可被分到输入组（旧版 MEItemInputBus 返回 true）", input.canGroupInput());
            report("ME 输入总线的物品 id 是 me_item_input_bus（不再拼 tier 后缀）",
                    "me_item_input_bus".equals(input.itemId(inputTier)));
            report("ME 输入总线的 lang key 指向 me_item_input_bus",
                    "block.modular_machinery_reborn.me_item_input_bus".equals(input.itemTranslationKey(inputTier)));
        }
        if (output != null && outputTier != null) {
            report("ME 输出总线属于 ITEM 家族", output.family() == HatchKind.Family.ITEM);
            report("ME 输出总线不是输入", !output.isInput());
            report("ME 输出总线不可分组", !output.canGroupInput());
            report("ME 输出总线的物品 id 是 me_item_output_bus",
                    "me_item_output_bus".equals(output.itemId(outputTier)));
        }
        report("ME 的两个 tier 不混进 ITEM_LADDER（否则普通物品仓会多出两个 tier）",
                inputTier != null && outputTier != null
                        && !HatchTier.ITEM_LADDER.contains(inputTier)
                        && !HatchTier.ITEM_LADDER.contains(outputTier));

        meBlockProperties(input, output, inputTier, outputTier);
        registrationIsLazy();
        clientUpdateTagDoesNotLoadNode();

        Class<?> logic = classOrNull(LOGIC);
        storageInterface = classOrNull(LOGIC + "$Storage");
        report("存在不依赖 AE2 的 MeItemBusLogic", logic != null);
        report("MeItemBusLogic 有 Storage 接口（insert/extract 的抽象）", storageInterface != null);

        Method tickInputMethod = method(logic, "tickInput", ItemStackHandler.class, ItemStackHandler.class,
                storageInterface, int[].class, boolean.class);
        Method tickOutputMethod = method(logic, "tickOutput", ItemStackHandler.class, storageInterface,
                int[].class, boolean.class);
        report("MeItemBusLogic 有 tickInput(ItemStackHandler, ItemStackHandler, Storage, int[], boolean)",
                tickInputMethod != null);
        report("MeItemBusLogic 有 tickOutput(ItemStackHandler, Storage, int[], boolean)", tickOutputMethod != null);

        report("存在 MeItemBusMenu", classOrNull(MENU) != null);

        if (tickInputMethod == null || tickOutputMethod == null || storageInterface == null) {
            return;
        }

        // 1) 配置为空：输入总线把库存送回网络，不会主动拉取。
        ItemStackHandler inv = new ItemStackHandler(16);
        ItemStackHandler config = new ItemStackHandler(16);
        inv.setStackInSlot(0, new ItemStack(Items.STONE, 5));
        FakeStorage net = new FakeStorage(1_000);
        boolean moved = tickInput(tickInputMethod, inv, config, net, true);
        report("输入：空配置把槽里的 5 个石头送进网络", moved && net.count(Items.STONE) == 5);
        report("输入：空配置那格被清空", inv.getStackInSlot(0).isEmpty());

        // 2) 配置 8、槽空：从网络抽 8。
        inv = new ItemStackHandler(16);
        config = new ItemStackHandler(16);
        config.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        net = new FakeStorage(1_000);
        net.add(Items.STONE, 20);
        moved = tickInput(tickInputMethod, inv, config, net, true);
        report("输入：配置 8、槽空时从网络抽 8", moved && inv.getStackInSlot(0).getCount() == 8
                && net.count(Items.STONE) == 12);

        // 3) 配置 8、槽里已有 3：只补 5。
        inv = new ItemStackHandler(16);
        inv.setStackInSlot(0, new ItemStack(Items.STONE, 3));
        config = new ItemStackHandler(16);
        config.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        net = new FakeStorage(1_000);
        net.add(Items.STONE, 20);
        moved = tickInput(tickInputMethod, inv, config, net, true);
        report("输入：已有 3 时只补到 8", moved && inv.getStackInSlot(0).getCount() == 8
                && net.count(Items.STONE) == 15);

        // 4) 配置 8、槽里已有 12：把多出的 4 送回网络。
        inv = new ItemStackHandler(16);
        inv.setStackInSlot(0, new ItemStack(Items.STONE, 12));
        config = new ItemStackHandler(16);
        config.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        net = new FakeStorage(1_000);
        net.add(Items.STONE, 20);
        moved = tickInput(tickInputMethod, inv, config, net, true);
        report("输入：已有 12 时把多出的 4 送回网络", moved && inv.getStackInSlot(0).getCount() == 8
                && net.count(Items.STONE) == 24);

        // 5) 槽里是别的东西：先送回网络，再按配置抽。
        inv = new ItemStackHandler(16);
        inv.setStackInSlot(0, new ItemStack(Items.DIRT, 5));
        config = new ItemStackHandler(16);
        config.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        net = new FakeStorage(1_000);
        net.add(Items.STONE, 20);
        moved = tickInput(tickInputMethod, inv, config, net, true);
        report("输入：错物先送回网络，再抽配置物", moved
                && net.count(Items.DIRT) == 5 && net.count(Items.STONE) == 12
                && inv.getStackInSlot(0).is(Items.STONE) && inv.getStackInSlot(0).getCount() == 8);

        // 6) 网络不够：抽到多少算多少，并记一次失败。
        inv = new ItemStackHandler(16);
        config = new ItemStackHandler(16);
        config.setStackInSlot(0, new ItemStack(Items.STONE, 8));
        net = new FakeStorage(1_000);
        net.add(Items.STONE, 3);
        int[] failures = new int[16];
        moved = tickInput(tickInputMethod, inv, config, net, failures, true);
        report("输入：网络只有 3 时抽 3，并记失败", moved && inv.getStackInSlot(0).getCount() == 3
                && failures[0] > 0);

        // 7) 失败过的槽在非全量检查 tick 被跳过。
        inv = new ItemStackHandler(16);
        inv.setStackInSlot(0, new ItemStack(Items.STONE, 10));
        net = new FakeStorage(1_000);
        failures = new int[16];
        failures[0] = 1;
        moved = tickOutput(tickOutputMethod, inv, net, failures, false);
        report("输出：失败过的槽在非全量检查 tick 不重试", !moved && inv.getStackInSlot(0).getCount() == 10);
        moved = tickOutput(tickOutputMethod, inv, net, failures, true);
        report("输出：全量检查 tick 忽略失败计数并重试", moved && net.count(Items.STONE) == 10);

        // 8) 输出：整叠推出。
        inv = new ItemStackHandler(16);
        inv.setStackInSlot(0, new ItemStack(Items.STONE, 10));
        net = new FakeStorage(1_000);
        moved = tickOutput(tickOutputMethod, inv, net, true);
        report("输出：整叠 10 个进网络，槽清空", moved && net.count(Items.STONE) == 10
                && inv.getStackInSlot(0).isEmpty());

        // 9) 输出：网络只收 4 个，剩下 6 个留在槽里并记失败。
        inv = new ItemStackHandler(16);
        inv.setStackInSlot(0, new ItemStack(Items.STONE, 10));
        net = new FakeStorage(4);
        failures = new int[16];
        moved = tickOutput(tickOutputMethod, inv, net, failures, true);
        report("输出：网络只收 4 个时槽里剩 6 个，并记失败", moved
                && net.count(Items.STONE) == 4 && inv.getStackInSlot(0).getCount() == 6
                && failures[0] > 0);

        // 10) 没有可搬的东西时返回 false（避免每 tick 都标脏）。
        inv = new ItemStackHandler(16);
        net = new FakeStorage(1_000);
        report("输出：空仓返回 false", !tickOutput(tickOutputMethod, inv, net, new int[16], true));
    }

    // ------------------------------------------------------- 反射与假网络

    private static boolean tickInput(Method method, ItemStackHandler inv, ItemStackHandler config,
                                     FakeStorage net, boolean fullCheck) {
        return tickInput(method, inv, config, net, new int[16], fullCheck);
    }

    private static boolean tickInput(Method method, ItemStackHandler inv, ItemStackHandler config,
                                     FakeStorage net, int[] failures, boolean fullCheck) {
        Object result = invoke(method, new Object[] {inv, config, net.proxy(), failures, fullCheck});
        return result instanceof Boolean flag && flag;
    }

    private static boolean tickOutput(Method method, ItemStackHandler inv, FakeStorage net, boolean fullCheck) {
        return tickOutput(method, inv, net, new int[16], fullCheck);
    }

    private static boolean tickOutput(Method method, ItemStackHandler inv, FakeStorage net,
                                      int[] failures, boolean fullCheck) {
        Object result = invoke(method, new Object[] {inv, net.proxy(), failures, fullCheck});
        return result instanceof Boolean flag && flag;
    }

    private static Object invoke(Method method, Object[] args) {
        try {
            return method.invoke(null, args);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            report("调用 " + method.getName() + " 失败：" + failure, false);
            return null;
        }
    }


    /**
     * 输入与输出必须各自持有自己的 size 属性，而且每个属性必须至少有两个可能值。
     *
     * <p>这条来自两次真实运行时错误。0.73.0 的第一次是：两个 ME 家族共用一个静态 SIZE 时，
     * 输出方块在注册期 setValue 会因属性只允许 ME_INPUT 而抛 IllegalArgumentException。
     * 0.73.1 的第二次是：把属性按家族拆开后，每个属性只剩一个可能值，Minecraft 的
     * {@code StateDefinition.Builder#add} 直接拒绝，启动时抛
     * {@code IllegalArgumentException: Block{...} attempted use property size with <= 1 possible values}。
     * 编译期两次都看不出来，所以这里钉住「属性实例」「至少两个值」「包含两个 ME tier」三件事。
     *
     * <p>默认状态本身没法在离线复现器里断言：{@code new Block(...)} 会给方块建 intrusive holder，
     * 而复现器跑到这里时 Forge 的注册表已经冻结（{@code IllegalStateException: Registry is already
     * frozen}）。默认值只能靠构造器里的 {@code setValue(SIZE, …)}、这条属性同一性，以及
     * {@code MeItemBusBlock#tier} 对状态值的忽略来保证。
     */
    private static void meBlockProperties(HatchKind input, HatchKind output, HatchTier inputTier, HatchTier outputTier) {
        Class<?> inputBlock = classOrNull("com.reborn.modularmachinery.block.MeItemBusBlock$Input");
        Class<?> outputBlock = classOrNull("com.reborn.modularmachinery.block.MeItemBusBlock$Output");
        report("存在 MeItemBusBlock.Input", inputBlock != null);
        report("存在 MeItemBusBlock.Output", outputBlock != null);
        if (inputBlock == null || outputBlock == null || input == null || output == null
                || inputTier == null || outputTier == null) {
            return;
        }

        EnumProperty<?> inputSize = sizeProperty(inputBlock);
        EnumProperty<?> outputSize = sizeProperty(outputBlock);
        report("输入方块的 SIZE 就是 HatchKind.ME_ITEM_INPUT.sizeProperty()（同一实例）",
                inputSize != null && inputSize == input.sizeProperty());
        report("输出方块的 SIZE 就是 HatchKind.ME_ITEM_OUTPUT.sizeProperty()（同一实例）",
                outputSize != null && outputSize == output.sizeProperty());
        report("输入 SIZE 至少有两个可能值（单值属性会被 StateDefinition 拒绝）", inputSize != null
                && inputSize.getPossibleValues().size() > 1);
        report("输出 SIZE 至少有两个可能值（单值属性会被 StateDefinition 拒绝）", outputSize != null
                && outputSize.getPossibleValues().size() > 1);
        report("输入 SIZE 允许 ME_INPUT 与 ME_OUTPUT", inputSize != null
                && Set.copyOf(inputSize.getPossibleValues()).equals(Set.of(inputTier, outputTier)));
        report("输出 SIZE 允许 ME_INPUT 与 ME_OUTPUT", outputSize != null
                && Set.copyOf(outputSize.getPossibleValues()).equals(Set.of(inputTier, outputTier)));
    }

    /**
     * 0.73.1 回归：ME 总线的方块实体注册不能在静态初始化时解引用方块。
     *
     * <p>0.73.0 在 {@code ModBlocks} 的静态初始化里把 {@code ME_ITEM_INPUT_BLOCK.get()} 传给
     * {@code Ae2ItemBusRegistry}，而那时方块注册事件还没跑，Forge 抛
     * {@code NullPointerException: Registry Object not present: ...:me_item_input_bus}，整个模组构造失败。
     *
     * <p>运行时无法在离线复现器里造一个未注册的 {@code RegistryObject}：它的构造器会触发
     * {@code ObjectHolderRegistry}，而这个类在这里缺少 {@code net.minecraftforge.fml.common.Mod}。
     * 所以这里钉两件事：入口的参数类型必须是 {@code RegistryObject}，且 {@code get()} 在源码里
     * 只出现在 supplier 之内。真正「调用时不触碰方块」由这两条共同保证。
     */
    private static void registrationIsLazy() {
        section("C4 回归：ME 总线注册必须延迟解引用方块（0.73.1）");
        Class<?> registry = classOrNull("com.reborn.modularmachinery.compat.ae2.Ae2ItemBusRegistry");
        report("存在 Ae2ItemBusRegistry", registry != null);

        Method register = method(registry, "register", net.minecraftforge.registries.DeferredRegister.class,
                net.minecraftforge.registries.RegistryObject.class, net.minecraftforge.registries.RegistryObject.class);
        report("Ae2ItemBusRegistry.register 接收 RegistryObject<Block> 而不是已解引用的 Block", register != null);

        try {
            String registrySource = withoutComments(Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                            + "compat/ae2/Ae2ItemBusRegistry.java")));
            int bounded = registrySource.indexOf("register.register(");
            int firstGet = registrySource.indexOf(".get()");
            report("Ae2ItemBusRegistry：第一个 .get() 出现在 register.register(...) 的参数里，而不是之前",
                    bounded >= 0 && firstGet > bounded);

            String blocksSource = withoutComments(Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                            + "block/ModBlocks.java")));
            int entityField = blocksSource.indexOf("ME_ITEM_BUS_ENTITY =");
            int call = blocksSource.indexOf("Ae2ItemBusRegistry.register(", entityField);
            int semicolon = call < 0 ? -1 : blocksSource.indexOf(';', call);
            String callText = entityField < 0 || semicolon < 0
                    ? "" : blocksSource.substring(entityField, semicolon + 1);
            report("ModBlocks 把两个方块 RegistryObject 原样传给注册入口", callText.contains("ME_ITEM_INPUT_BLOCK")
                    && callText.contains("ME_ITEM_OUTPUT_BLOCK"));
            report("ModBlocks 的 ME_ITEM_BUS_ENTITY 初始化里没有 .get()（0.73.0 崩溃的那一行）",
                    !callText.isEmpty() && !callText.contains(".get()"));
        } catch (java.io.IOException failure) {
            report("读源码失败：" + failure, false);
        }
    }

    /**
     * 0.73.3 回归：客户端收到 chunk update tag 时不能再次初始化 AE2 节点。
     *
     * <p>0.73.2 的 {@code MeItemBusBlockEntity.load(CompoundTag)} 无条件调
     * {@code mainNode.loadFromNBT(tag)}。客户端 chunk 包先走 {@code BlockEntity.onLoad()} 创建节点，
     * 再走 {@code handleUpdateTag -> load}，AE2 抛
     * {@code IllegalStateException: The node has already been initialized. Initialization data cannot be changed anymore.}；
     * 这个异常从 {@code LevelChunk} 冒出来，整个 chunk 包处理中断，表现就是区块里所有东西不可见。
     *
     * <p>AE2 类型不在离线复现器的 classpath 里，没法在这里 new 一个真实节点；所以钉源码：
     * {@code load} 里必须先判断 {@code isClientSide}，再决定是否调用 {@code loadFromNBT}。
     */
    private static void clientUpdateTagDoesNotLoadNode() {
        section("C4 回归：客户端 chunk update tag 不得重复初始化 AE2 节点（0.73.3）");
        try {
            String source = withoutComments(Files.readString(Path.of(
                    "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/"
                            + "compat/ae2/MeItemBusBlockEntity.java")));
            int start = source.indexOf("public void load(CompoundTag tag)");
            int end = start < 0 ? -1 : source.indexOf("@Override", start + 1);
            String body = start < 0 || end <= start ? "" : source.substring(start, end);
            report("找到 MeItemBusBlockEntity.load(CompoundTag)", !body.isEmpty());
            report("load() 在 mainNode.loadFromNBT 之前检查客户端",
                    body.contains("isClientSide") && body.indexOf("isClientSide") < body.indexOf("mainNode.loadFromNBT"));
            report("load() 仍会在服务端恢复节点数据", body.contains("mainNode.loadFromNBT"));
        } catch (java.io.IOException failure) {
            report("读 MeItemBusBlockEntity 源码失败：" + failure, false);
        }
    }

    private static String withoutComments(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static EnumProperty<?> sizeProperty(Class<?> blockClass) {
        try {
            Field field = blockClass.getField("SIZE");
            Object value = field.get(null);
            return value instanceof EnumProperty<?> property ? property : null;
        } catch (ReflectiveOperationException absent) {
            return null;
        }
    }


    private static HatchKind kind(String name) {
        try {
            return HatchKind.valueOf(name);
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    private static HatchTier tier(String name) {
        try {
            return HatchTier.valueOf(name);
        } catch (IllegalArgumentException absent) {
            return null;
        }
    }

    private static Class<?> classOrNull(String name) {
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

    /**
     * 一个不依赖 AE2 的假网络：按物品计数，容量是总物品数；insert 返回实际收下的数量，
     * extract 返回实际给出的数量，与 AE2 {@code MEStorage} 的返回值口径一致。
     */
    private static final class FakeStorage {
        private final int capacity;
        private final Map<Item, Integer> counts = new HashMap<>();

        FakeStorage(int capacity) {
            this.capacity = capacity;
        }

        void add(Item item, int amount) {
            this.counts.merge(item, amount, Integer::sum);
        }

        int count(Item item) {
            return this.counts.getOrDefault(item, 0);
        }

        private int total() {
            int total = 0;
            for (int value : this.counts.values()) {
                total += value;
            }
            return total;
        }

        private int insert(ItemStack stack) {
            if (stack.isEmpty()) {
                return 0;
            }
            int accepted = Math.min(stack.getCount(), Math.max(0, this.capacity - total()));
            if (accepted > 0) {
                add(stack.getItem(), accepted);
            }
            return accepted;
        }

        private int extract(ItemStack prototype, int amount) {
            int available = count(prototype.getItem());
            int moved = Math.min(amount, available);
            if (moved > 0) {
                this.counts.put(prototype.getItem(), available - moved);
            }
            return moved;
        }

        Object proxy() {
            return Proxy.newProxyInstance(storageInterface.getClassLoader(),
                    new Class<?>[] {storageInterface},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> insert((ItemStack) args[0]);
                        case "extract" -> extract((ItemStack) args[0], ((Number) args[1]).intValue());
                        case "toString" -> "FakeStorage";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }
}
