package mmverify;

import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.ItemStackHandler;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A2：输入仓的「组 ID」隔离（0.64.0）。
 *
 * <p>旧版的语义（勘测 {@code docs/勘测-1.12.2-vs-0.62.9.md:115}）：玩家给物品输入总线与流体输入仓
 * 分配组号，同组端口独立做配方检查；没分组的端口（组号 {@code -1}）对每个组都可见，但不是候选组；
 * 候选组只来自非负组号，一个都没有时只有组 0。旧版可分组仓只有物品输入与流体输入
 * （{@code TileItemInputBus.java:141-144}、{@code base/TileFluidTank.java:144-147} 且仅输入），
 * 输出仓与能量仓永远通用（{@code TileColorableMachineComponent.java:105-108}）。
 *
 * <p>这里钉三半：
 * <ul>
 *   <li><b>能离线跑的那一半</b>：{@code HatchKind.canGroupInput} 只对两个输入家族为真；端口集合的
 *       组号归一（输出仓传 99 也记成通用）、{@code groupIds()} 只收非负组号且升序、{@code withGroup}
 *       同时留下该组与通用端口。</li>
 *   <li><b>接线</b>：控制器在开工时锁定一组、运行期只用这一组、存档记得住；工厂按组试配方。</li>
 *   <li><b>玩家入口</b>：菜单/界面/网络包/右键条件是连在一起的，缺一环玩家就改不了组号。</li>
 * </ul>
 *
 * <p>全用反射：0.63.9 的生产源码上这些方法与类都还不存在，让它们变成红断言，而不是让复现器编译失败。
 */
final class HatchGroupCheck {

    private static final String SRC = "C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery/";

    private HatchGroupCheck() {
    }

    static void hatchGroups() {
        section("A2 输入仓组 ID 隔离（0.64.0）");
        kindRules();
        collectionScopes();
        hallEntityWiring();
        controllerWiring();
        factoryWiring();
        playerEntry();
    }

    // ------------------------------------------------------------------ 种类规则

    private static void kindRules() {
        try {
            Class<?> kindClass = Class.forName("com.reborn.modularmachinery.block.HatchKind");
            Method canGroup = kindClass.getMethod("canGroupInput");
            report("only an item input hatch can be put in a group",
                    (boolean) canGroup.invoke(kindClass.getField("ITEM_INPUT").get(null)));
            report("...and a fluid input hatch can too",
                    (boolean) canGroup.invoke(kindClass.getField("FLUID_INPUT").get(null)));
            for (String other : List.of("ITEM_OUTPUT", "FLUID_OUTPUT", "ENERGY_INPUT", "ENERGY_OUTPUT")) {
                report("...but " + other + " cannot (the original answers -1 for it)",
                        !(boolean) canGroup.invoke(kindClass.getField(other).get(null)));
            }
        } catch (Throwable broken) {
            report("HatchKind#canGroupInput cannot be exercised: " + broken, false);
        }
    }

    // ------------------------------------------------------------------ 端口集合

    private static void collectionScopes() {
        try {
            Class<?> kindClass = Class.forName("com.reborn.modularmachinery.block.HatchKind");
            Class<?> collectionClass = Class.forName("com.reborn.modularmachinery.machine.HatchCollection");
            Class<?> builderClass = Class.forName("com.reborn.modularmachinery.machine.HatchCollection$Builder");
            Object builder = builderClass.getConstructor().newInstance();

            Object itemInput = kindClass.getField("ITEM_INPUT").get(null);
            Object itemOutput = kindClass.getField("ITEM_OUTPUT").get(null);
            Object fluidInput = kindClass.getField("FLUID_INPUT").get(null);
            Method add = builderClass.getMethod("add", kindClass, net.minecraftforge.items.IItemHandler.class,
                    net.minecraftforge.fluids.capability.IFluidHandler.class,
                    net.minecraftforge.energy.IEnergyStorage.class, String.class, int.class);

            ItemStackHandler a = new ItemStackHandler(1);
            ItemStackHandler b = new ItemStackHandler(1);
            ItemStackHandler generalItem = new ItemStackHandler(1);
            ItemStackHandler outputItem = new ItemStackHandler(1);
            FluidTank groupTank = new FluidTank(1000);
            FluidTank generalTank = new FluidTank(1000);

            add.invoke(builder, itemInput, a, null, null, "line_0", 3);
            add.invoke(builder, itemInput, b, null, null, "line_0", 7);
            add.invoke(builder, itemInput, generalItem, null, null, null, -1);
            add.invoke(builder, fluidInput, null, groupTank, null, null, 3);
            add.invoke(builder, fluidInput, null, generalTank, null, null, -1);
            add.invoke(builder, itemOutput, outputItem, null, null, null, 99);
            Object all = builderClass.getMethod("build").invoke(builder);

            check("the candidate groups are the non-negative input groups, ascending",
                    "3,7", join(groupsOf(collectionClass, all)));
            report("a general (ungrouped) port is not itself a candidate group",
                    !groupsOf(collectionClass, all).contains(-1));

            Method withGroup = collectionClass.getMethod("withGroup", int.class);
            Object group3 = withGroup.invoke(all, 3);
            Object group7 = withGroup.invoke(all, 7);

            report("group 3 sees its own item input and the general one", size(collectionClass, group3, "itemInputs") == 2);
            report("...and does not see the port that belongs to group 7",
                    !list(collectionClass, group3, "itemInputs").contains(b));
            report("...and its own fluid input plus the general one",
                    size(collectionClass, group3, "fluidInputs") == 2);
            report("group 7 sees group 7's item input plus the general one",
                    size(collectionClass, group7, "itemInputs") == 2
                            && list(collectionClass, group7, "itemInputs").contains(b)
                            && list(collectionClass, group7, "itemInputs").contains(generalItem));
            report("...but not the fluid input of group 3",
                    size(collectionClass, group7, "fluidInputs") == 1
                            && list(collectionClass, group7, "fluidInputs").contains(generalTank));
            report("outputs are visible from every group, because they are always general",
                    size(collectionClass, group3, "itemOutputs") == 1
                            && size(collectionClass, group7, "itemOutputs") == 1);
            check("a scope still reports its own group id",
                    "3", join(groupsOf(collectionClass, group3)));
            check("...and a general-only scope has no candidate group left",
                    "0", join(groupsOf(collectionClass, withGroup.invoke(all, -1))));

            String described = String.join(" | ", (List<String>) (List<?>) collectionClass
                    .getMethod("describePorts").invoke(all));
            report("the diagnose listing names the pinned group", described.contains("item input") && described.contains("group=3"));
            report("...and says 'general' for the ungrouped and the output ports",
                    described.contains("group=general"));

            Object empty = builderClass.getConstructor().newInstance();
            Object emptyCollection = builderClass.getMethod("build").invoke(empty);
            check("a machine with no grouped hatch runs in group 0 (the original's fallback)",
                    "0", join(groupsOf(collectionClass, emptyCollection)));
        } catch (Throwable broken) {
            report("HatchCollection group scoping cannot be exercised: " + broken, false);
        }
    }

    // ------------------------------------------------------------------ 方块实体

    private static void hallEntityWiring() {
        try {
            Class<?> hatch = Class.forName("com.reborn.modularmachinery.block.MachineHatchBlockEntity");
            for (String signature : List.of("setGroup", "effectiveGroup", "isGroupInput", "groupId",
                    "canGroupInput", "groupDescription")) {
                boolean present = java.util.Arrays.stream(hatch.getMethods())
                        .anyMatch(m -> m.getName().equals(signature));
                report("MachineHatchBlockEntity#" + signature + " exists", present);
            }
            String text = withoutComments(source("block/MachineHatchBlockEntity.java"));
            report("an ungrouped hatch answers -1 (so it is visible to every group)",
                    text.contains("return this.isGroupInput() ? this.groupId : -1;"));
            report("the group id is clamped at 0, like the original GUI did",
                    text.contains("int clamped = Math.max(0, id);"));
            report("a hatch that cannot be grouped silently refuses a group",
                    text.contains("if (!this.canGroupInput()) {"));
            report("the group survives a reload under the original key names",
                    text.contains("\"groupId\"") && text.contains("\"isGroupInput\"")
                            && text.contains("tag.putInt(TAG_GROUP_ID, this.groupId)")
                            && text.contains("Math.max(0, tag.getInt(TAG_GROUP_ID))"));
        } catch (Throwable broken) {
            report("the hatch block entity's group state cannot be inspected: " + broken, false);
        }
    }

    // ------------------------------------------------------------------ 控制器

    private static void controllerWiring() {
        try {
            String text = withoutComments(source("block/MachineControllerBlockEntity.java"));
            report("the controller only starts a recipe in a group that can satisfy it",
                    text.contains("startableGroup(") && text.contains("this.hatches.groupIds()")
                            && text.contains("this.hatches.withGroup(groupId)"));
            report("...and locks the chosen group for the whole craft",
                    text.contains("be.activeGroupId = startGroup.groupId();"));
            report("...and keeps using that group while the craft runs",
                    text.contains("be.hatches.withGroup(be.activeGroupId)"));
            report("...and clears it when the craft ends",
                    text.contains("be.activeGroupId = null;"));
            report("...and the lock survives a reload",
                    text.contains("tag.putInt(\"activeGroupId\", activeGroupId)")
                            && text.contains("tag.contains(\"activeGroupId\")"));
            report("the hatch collector hands every port its effective group",
                    text.contains("hatch.effectiveGroup()") && text.contains("hatch.kind().canGroupInput()"));
            report("the diagnose path reports the last group instead of an empty port set",
                    text.contains("private HatchCollection lastGroupScope()"));
            String diagnose = withoutComments(source("command/DiagnoseCommand.java"));
            report(".../mm-diagnose lists the candidate groups", diagnose.contains("hatches.groupIds()"));
            report("...and each hatch's own group", diagnose.contains("hatch.groupDescription()"));
        } catch (Throwable broken) {
            report("the controller's group wiring cannot be read: " + broken, false);
        }
    }

    // ------------------------------------------------------------------ 工厂

    private static void factoryWiring() {
        try {
            String engine = withoutComments(source("factory/FactoryEngine.java"));
            report("the factory tries every candidate group of every runnable recipe",
                    engine.contains("for (int group : ports.groupIds())")
                            && engine.contains("recipe.canStart(ports.withGroup(group), modifiers)")
                            && engine.contains("new Candidate(recipe, group)"));
            report("...and remembers which group the candidate came from",
                    engine.contains("thread.accepts(candidate.recipe())")
                            && engine.contains("candidate.group()"));
            report("...and the running craft uses exactly that group",
                    engine.contains("this.host.ports().withGroup(craft.group())")
                            && engine.contains("this.host.ports().withGroup(again.group())"));
            report("...and a restored craft keeps its saved group",
                    engine.contains("saved.group()"));

            String thread = withoutComments(source("factory/FactoryThread.java"));
            report("a saved factory thread freezes its group id",
                    thread.contains("TAG_GROUP_ID = \"groupId\"")
                            && thread.contains("tag.putInt(TAG_GROUP_ID, this.active.group())")
                            && thread.contains("Math.max(0, tag.getInt(TAG_GROUP_ID))"));

            Class<?> craft = Class.forName("com.reborn.modularmachinery.factory.FactoryEngine$ActiveCraft");
            boolean ctor = java.util.Arrays.stream(craft.getDeclaredConstructors())
                    .anyMatch(c -> c.getParameterCount() == 3);
            report("ActiveCraft carries a group through its constructor", ctor);
            report("...and exposes it",
                    java.util.Arrays.stream(craft.getMethods()).anyMatch(m -> m.getName().equals("group")
                            && m.getReturnType() == int.class));
        } catch (Throwable broken) {
            report("the factory's group wiring cannot be read: " + broken, false);
        }
    }

    // ------------------------------------------------------------------ 玩家入口

    private static void playerEntry() {
        try {
            String menu = withoutComments(source("menu/HatchGroupMenu.java"));
            String screen = withoutComments(source("client/HatchGroupScreen.java"));
            String packet = withoutComments(source("network/HatchGroupUpdatePacket.java"));
            String block = withoutComments(source("block/MachineHatchBlock.java"));
            String modMenus = withoutComments(source("menu/ModMenus.java"));
            String clientSetup = withoutComments(source("client/ClientSetup.java"));
            String network = withoutComments(source("network/ModNetwork.java"));

            report("sneaking with empty hands on a groupable hatch opens the group screen",
                    block.contains("player.isShiftKeyDown()")
                            && block.contains("player.getMainHandItem().isEmpty()")
                            && block.contains("player.getOffhandItem().isEmpty()")
                            && block.contains("hatch.canGroupInput()"));
            report("...through a registered menu",
                    modMenus.contains("\"hatch_group\"") && menu.contains("TITLE_KEY"));
            report("...whose client screen is registered too",
                    clientSetup.contains("ModMenus.HATCH_GROUP.get()"));
            String getBody = methodBody(menu, "public int get(int index)");
            String setBody = methodBody(menu, "public void set(int index, int value)");
            report("the menu keeps a client-side mirror of the two slots",
                    menu.contains("clientSide") && menu.contains("received = new int[DATA_COUNT]")
                            && getBody.contains("if (clientSide)")
                            && setBody.contains("received[index] = value")
                            && !setBody.contains("setGroup("));
            report("...while the server side reads the hatch itself",
                    getBody.contains("hatch.isGroupInput()") && getBody.contains("hatch.groupId()"));
            report("...and publishes exactly the two slots the screen reads",
                    menu.contains("DATA_COUNT = 2") && menu.contains("return DATA_COUNT;"));
            report("the screen sends the number the player typed, clamped like the original",
                    screen.contains("Long.parseLong") && screen.contains("Integer.MAX_VALUE")
                            && screen.contains("commitValueField"));
            report("the packet is registered and applied only to the same hatch the player has open",
                    network.contains("ID_HATCH_GROUP_UPDATE = 4")
                            && packet.contains("instanceof HatchGroupMenu")
                            && packet.contains("canGroupInput()")
                            && packet.contains("setGroup("));

            String zh = Files.readString(Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/"
                    + "assets/modular_machinery_reborn/lang/zh_cn.json"));
            String en = Files.readString(Path.of("C:/mmwork/modular-machinery-reborn/src/main/resources/"
                    + "assets/modular_machinery_reborn/lang/en_us.json"));
            List<String> keys = List.of("title", "tooltip", "value", "enable", "close");
            report("all five group-screen keys exist in both languages",
                    keys.stream().allMatch(k -> zh.contains("groupinputconfig." + k))
                            && keys.stream().allMatch(k -> en.contains("groupinputconfig." + k)));
            report("...and the enable/disable wordings come from the original",
                    zh.contains("启用组") && zh.contains("禁用组"));
        } catch (Throwable broken) {
            report("the player entry path cannot be read: " + broken, false);
        }
    }

    // ------------------------------------------------------------------ helpers

    @SuppressWarnings("unchecked")
    private static List<Integer> groupsOf(Class<?> collectionClass, Object collection) throws Exception {
        return (List<Integer>) (List<?>) collectionClass.getMethod("groupIds").invoke(collection);
    }

    private static List<?> list(Class<?> collectionClass, Object collection, String accessor) throws Exception {
        return (List<?>) collectionClass.getMethod(accessor).invoke(collection);
    }

    private static int size(Class<?> collectionClass, Object collection, String accessor) throws Exception {
        return list(collectionClass, collection, accessor).size();
    }

    private static String join(List<Integer> values) {
        StringBuilder out = new StringBuilder();
        for (int value : values) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(value);
        }
        return out.toString();
    }

    /** 取一个方法的方法体（从签名到配对的右花括号）。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean opened = false;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
                opened = true;
            } else if (c == '}') {
                depth--;
                if (opened && depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        return source.substring(start);
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of(SRC + relative));
    }

    private static void check(String what, String expected, String actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
    }

    /** 去掉注释但保留字符串字面量；理由同 MekanismCompatCheck 的同名函数。 */
    private static String withoutComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '"') {
                out.append(c);
                i++;
                while (i < n) {
                    char s = source.charAt(i);
                    out.append(s);
                    i++;
                    if (s == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (s == '"') {
                        break;
                    }
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
