package mmverify;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A3（勘测 §4 第 116 行）：比较器输出。
 *
 * <p>旧版四个方块——物品总线、流体仓、能量仓、控制器——都覆写了
 * {@code hasComparatorInputOverride}（{@code BlockBus.java:136-143}、{@code BlockFluidHatch.java:137-145}、
 * {@code BlockEnergyHatch.java:104-112}、{@code BlockController.java:259-271}），读数在
 * {@code util/RedstoneHelper.java:28-47}：
 *
 * <ul>
 *   <li>物品：{@code ItemHandlerHelper.calcRedstoneFromInventory}；</li>
 *   <li>流体：{@code round(15 * amount / capacity)}；</li>
 *   <li>能量：{@code round(15 * cur / max)}；</li>
 *   <li>控制器：工作中 15、已成型 1、否则 0。</li>
 * </ul>
 *
 * <p>本版此前全仓没有 {@code hasAnalogOutputSignal}，所以比较器读到的永远是 0。这一节钉两半：
 * <ul>
 *   <li><b>数值那一半</b>：{@code ComparatorLevels} 的四个公式，用真实的库存 / 储罐 / 能量缓冲驱动，
 *       不碰世界；反射入口，类不存在时红而不是编译失败。</li>
 *   <li><b>接线那一半</b>：两个方块真的覆写了 {@code hasAnalogOutputSignal}，仓口按家族分派，
 *       控制器读 {@code isWorking()} 与 {@code isFormed()}；升级总线保持旧版的「没有比较器」。</li>
 * </ul>
 *
 * <p>为什么物品公式不自己照抄：旧版调用的就是 Forge 的
 * {@code ItemHandlerHelper.calcRedstoneFromInventory}，1.20.1 仍有同一方法（javap 已核），
 * 复用它才不会在「除数是槽数还是 {@code getSlots()}」这种细节上漂移。
 *
 * <p>气体不计入：旧版没有气体仓；混合仓的 {@code getFluidAmount()} 只回答流体。这一点写进
 * {@code docs/已知限制.md}，不在这里假造一个「气体也占比较器」的行为。
 */
final class ComparatorCheck {

    private ComparatorCheck() {
    }

    static void comparators() {
        section("A3 比较器输出（0.64.1）");
        itemFormula();
        tankAndEnergyFormula();
        controllerFormula();
        wiring();
    }

    /** 物品：Forge 的填充比例公式，空仓 0、满仓 15。 */
    private static void itemFormula() {
        try {
            Method items = helper("items", IItemHandler.class);

            report("an absent item handler reads 0", (int) items.invoke(null, (Object) null) == 0);
            report("an empty item hatch reads 0",
                    (int) items.invoke(null, new ItemStackHandler(1)) == 0);

            ItemStackHandler one = new ItemStackHandler(1);
            one.setStackInSlot(0, new ItemStack(Items.STONE, 1));
            check("a single item in a one-slot hatch reads 1", 1L, (int) items.invoke(null, one));

            one.setStackInSlot(0, new ItemStack(Items.STONE, 32));
            check("half a stack reads 8 (floor(0.5*14)+1)", 8L, (int) items.invoke(null, one));

            one.setStackInSlot(0, new ItemStack(Items.STONE, 64));
            check("a full one-slot hatch reads 15", 15L, (int) items.invoke(null, one));

            ItemStackHandler two = new ItemStackHandler(2);
            two.setStackInSlot(0, new ItemStack(Items.STONE, 64));
            check("one full slot of two reads 8", 8L, (int) items.invoke(null, two));
        } catch (Throwable broken) {
            report("the item comparator formula cannot be exercised: " + broken, false);
        }
    }

    /** 流体与能量：同一比例公式；0 是 0，满仓 15，半仓 8。 */
    private static void tankAndEnergyFormula() {
        try {
            Method tank = helper("tank", FluidTank.class);
            Method energy = helper("energy", EnergyStorage.class);

            FluidTank empty = new FluidTank(8000);
            check("an empty tank reads 0", 0L, (int) tank.invoke(null, empty));

            FluidTank half = new FluidTank(8000);
            half.fill(new FluidStack(Fluids.WATER, 4000), IFluidHandler.FluidAction.EXECUTE);
            check("half a tank reads 8", 8L, (int) tank.invoke(null, half));

            FluidTank full = new FluidTank(8000);
            full.fill(new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
            check("a full tank reads 15", 15L, (int) tank.invoke(null, full));

            FluidTank trace = new FluidTank(8000);
            trace.fill(new FluidStack(Fluids.WATER, 100), IFluidHandler.FluidAction.EXECUTE);
            check("a trace amount rounds down to 0", 0L, (int) tank.invoke(null, trace));

            report("a missing tank reads 0", (int) tank.invoke(null, (Object) null) == 0);

            check("half an energy buffer reads 8", 8L,
                    (int) energy.invoke(null, new EnergyStorage(16000, 1000, 1000, 8000)));
            check("a full energy buffer reads 15", 15L,
                    (int) energy.invoke(null, new EnergyStorage(16000, 1000, 1000, 16000)));
            check("an empty energy buffer reads 0", 0L,
                    (int) energy.invoke(null, new EnergyStorage(16000, 1000, 1000, 0)));
            report("a missing energy buffer reads 0", (int) energy.invoke(null, (Object) null) == 0);
        } catch (Throwable broken) {
            report("the tank/energy comparator formula cannot be exercised: " + broken, false);
        }
    }

    /** 控制器：工作中 15、已成型 1、其它 0（{@code BlockController.java:262-263}）。 */
    private static void controllerFormula() {
        try {
            Method controller = helper("controller", boolean.class, boolean.class);
            check("a working controller reads 15", 15L, (int) controller.invoke(null, true, true));
            check("a formed but idle controller reads 1", 1L, (int) controller.invoke(null, false, true));
            check("no structure reads 0", 0L, (int) controller.invoke(null, false, false));
            check("working wins over the formed bit", 15L, (int) controller.invoke(null, true, false));
        } catch (Throwable broken) {
            report("the controller comparator formula cannot be exercised: " + broken, false);
        }
    }

    /** 接线：方块覆写、按家族分派、控制器读工作/成型位、升级总线保持没有比较器。 */
    private static void wiring() {
        Path base = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/modularmachinery");
        try {
            String hatch = withoutComments(Files.readString(base.resolve("block/MachineHatchBlock.java")));
            String hatchSignal = methodBody(hatch, "public int getAnalogOutputSignal");
            report("a hatch answers the comparator",
                    hatch.contains("boolean hasAnalogOutputSignal")
                            && !hatchSignal.isEmpty());
            report("...dispatching the signal on the three families",
                    hatchSignal.contains("case ITEM") && hatchSignal.contains("case FLUID")
                            && hatchSignal.contains("case ENERGY"));
            report("...and asking the shared helper",
                    hatchSignal.contains("ComparatorLevels.items") && hatchSignal.contains("ComparatorLevels.tank")
                            && hatchSignal.contains("ComparatorLevels.energy"));

            String controller = withoutComments(Files.readString(base.resolve("block/MachineControllerBlock.java")));
            String controllerSignal = methodBody(controller, "public int getAnalogOutputSignal");
            report("a controller answers the comparator",
                    controller.contains("boolean hasAnalogOutputSignal")
                            && !controllerSignal.isEmpty());
            report("...reading the working and formed bits",
                    controllerSignal.contains("controller.isWorking()")
                            && controllerSignal.contains("controller.isFormed()"));

            String entity = withoutComments(Files.readString(base.resolve("block/MachineControllerBlockEntity.java")));
            String working = methodBody(entity, "public boolean isWorking()");
            report("the controller's working bit honours redstone",
                    working.contains("redstoneStopped") && working.contains("return false"));
            report("...and covers both the single craft and the factory",
                    working.contains("hasActiveCraft()") && working.contains("factoryEngine.isWorking()"));

            String bus = withoutComments(Files.readString(base.resolve("block/UpgradeBusBlock.java")));
            report("the upgrade bus still has no comparator, like the original",
                    !bus.contains("hasAnalogOutputSignal"));

            String helperText = withoutComments(Files.readString(base.resolve("block/ComparatorLevels.java")));
            report("items reuse Forge's own inventory formula",
                    helperText.contains("ItemHandlerHelper.calcRedstoneFromInventory"));
            report("tank and energy both use the original 15-level ratio",
                    helperText.contains("15F") && helperText.contains("Math.min(15"));
        } catch (Throwable unreadable) {
            report("reading the comparator sources failed: " + unreadable, false);
        }
    }

    private static Method helper(String name, Class<?>... params) throws Exception {
        Class<?> type = Class.forName("com.reborn.modularmachinery.block.ComparatorLevels");
        Method method = type.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
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
