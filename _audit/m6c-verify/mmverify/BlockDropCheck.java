package mmverify;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.items.ItemStackHandler;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A9：破坏方块时把槽里的东西吐出来（0.63.8）。
 *
 * <p>旧版三个方块都在 {@code breakBlock} 里遍历库存、{@code spawnAsEntity}、再把槽清空：
 * {@code BlockBus.java:50-63}、{@code BlockUpgradeBus.java:57-70}、{@code BlockController.java:178-191}。
 * 本版升级总线从 0.30.1 起已有 {@code playerDestroy}，但物品仓和控制器一直只掉方块本身，槽里的物品
 * 随方块实体一起消失——这是数据损失，不是显示问题。
 *
 * <p>这里钉两半：
 * <ul>
 *   <li><b>能离线跑的那一半</b>：{@code ItemStackDrops.clearInto} 真的按槽序把非空栈交给 sink，并清空
 *       原槽；空槽不产生事件；{@code pop(null)} 是 no-op（流体/能量仓没有物品库存）。</li>
 *   <li><b>接线那一半</b>：物品仓与控制器都在 {@code onRemove}（而不是只在玩家破坏时）调用这个出口，
 *       控制器的染色清理仍在同一条路径上。真实掉落物的实体只有游戏里能看。</li>
 * </ul>
 *
 * <p>为什么用 {@code onRemove}：旧版 {@code breakBlock} 对爆炸/替换等任何移除都会走到；本版升级总线
 * 用的是 {@code playerDestroy}，但 A9 的对象是「方块没了，内容物也不能凭空消失」。{@code onRemove}
 * 在方块状态确实改变时调用，且控制器现有染色清理已经依赖它拿得到方块实体。{@code pop} 里再挡一次
 * 客户端，避免客户端也生成一份掉落物。
 */
final class BlockDropCheck {

    private BlockDropCheck() {
    }

    static void blockDrops() {
        section("A9 方块破坏时掉内容物（0.63.8）");
        semantics();
        wiring();
    }

    /**
     * 离线可跑的部分：{@code clearInto} 是真正搬运内容的那个函数，不依赖 Level。
     */
    private static void semantics() {
        ItemStackHandler handler = new ItemStackHandler(4);
        handler.setStackInSlot(0, new ItemStack(Items.DIAMOND, 3));
        handler.setStackInSlot(2, new ItemStack(Items.COAL, 7));
        List<ItemStack> dropped = new ArrayList<>();
        Consumer<ItemStack> sink = dropped::add;

        try {
            Class<?> helper = Class.forName("com.reborn.modularmachinery.block.ItemStackDrops");
            Method clear = helper.getDeclaredMethod("clearInto", ItemStackHandler.class, Consumer.class);
            clear.setAccessible(true);
            int moved = (int) clear.invoke(null, handler, sink);

            check("two non-empty slots are handed to the sink", 2L, moved);
            check("...and the sink saw both stacks", 2L, dropped.size());
            report("...in slot order: slot 0 (diamond x3) before slot 2 (coal x7)",
                    dropped.size() == 2
                            && dropped.get(0).getItem() == Items.DIAMOND
                            && dropped.get(0).getCount() == 3
                            && dropped.get(1).getItem() == Items.COAL
                            && dropped.get(1).getCount() == 7);
            report("...and every slot is empty afterwards, including the one that was already empty",
                    handler.getStackInSlot(0).isEmpty()
                            && handler.getStackInSlot(1).isEmpty()
                            && handler.getStackInSlot(2).isEmpty()
                            && handler.getStackInSlot(3).isEmpty());

            Method pop = helper.getDeclaredMethod("pop", net.minecraft.world.level.Level.class,
                    net.minecraft.core.BlockPos.class, ItemStackHandler.class);
            pop.setAccessible(true);
            pop.invoke(null, null, null, null);
            report("pop with no item handler (a fluid or energy hatch) is a no-op", true);
        } catch (Throwable broken) {
            report("the item-drop helper cannot be exercised: " + broken, false);
        }
    }

    /**
     * 接线：读源码，但先去掉注释，避免「把调用注释掉」也能过。
     */
    private static void wiring() {
        Path hatch = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                + "modularmachinery/block/MachineHatchBlock.java");
        Path controller = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                + "modularmachinery/block/MachineControllerBlock.java");
        Path bus = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                + "modularmachinery/block/UpgradeBusBlock.java");
        Path helper = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                + "modularmachinery/block/ItemStackDrops.java");
        try {
            String hatchText = withoutComments(Files.readString(hatch));
            String hatchRemove = methodBody(hatchText, "public void onRemove");
            report("an item hatch drops its slots when the block is removed",
                    hatchRemove.contains("ItemStackDrops.pop(level, pos, hatch.itemHandler())"));
            report("...without dropping the fluid tank or the energy buffer (the helper only takes item stacks)",
                    !hatchText.contains("ItemStackDrops.pop(level, pos, hatch.fluidTank())")
                            && !hatchText.contains("ItemStackDrops.pop(level, pos, hatch.energyStorage())"));

            String controllerText = withoutComments(Files.readString(controller));
            String controllerRemove = methodBody(controllerText, "public void onRemove");
            report("a controller drops its blueprint slot when the block is removed",
                    controllerRemove.contains("ItemStackDrops.pop(level, pos, controller.items())"));
            report("...and still clears the distributed machine colour on the same path",
                    controllerRemove.contains("clearDistributedMachineColor(state.getValue(FACING))"));

            String busText = withoutComments(Files.readString(bus));
            String busDestroy = methodBody(busText, "public void playerDestroy");
            report("the upgrade bus keeps its existing playerDestroy drop, now through the same helper",
                    busDestroy.contains("ItemStackDrops.pop(level, pos, bus.items())"));

            String helperText = withoutComments(Files.readString(helper));
            String popBody = methodBody(helperText, "static void pop");
            report("the shared drop helper refuses the client side and a missing item handler",
                    popBody.contains("items == null") && popBody.contains("level.isClientSide"));
            String clearBody = methodBody(helperText, "static int clearInto");
            report("...and the clear loop copies each stack before clearing the slot",
                    clearBody.contains("stack.copy()")
                            && clearBody.contains("setStackInSlot(slot, ItemStack.EMPTY)"));
        } catch (Exception unreadable) {
            report("reading the block sources failed: " + unreadable, false);
        }
    }

    private static void check(String what, long expected, long actual) {
        ParallelCraftCheck.check(what, expected, actual);
    }

    private static void report(String line, boolean ok) {
        ParallelCraftCheck.report(line, ok);
    }

    private static void section(String title) {
        ParallelCraftCheck.section(title);
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
                continue;
            }
            if (c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char s = source.charAt(i);
                    out.append(s);
                    i++;
                    if (s == '\\' && i < n) {
                        out.append(source.charAt(i));
                        i++;
                    } else if (s == '\'') {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
