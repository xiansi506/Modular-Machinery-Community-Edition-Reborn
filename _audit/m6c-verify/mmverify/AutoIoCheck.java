package mmverify;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A1：仓口自动与相邻容器交换（0.63.9）。
 *
 * <p>旧版物品输入总线、物品输出总线和能量输出仓各自有一个 ticker：
 * {@code TileItemInputBus.java:50-75}、{@code TileItemOutputBus.java:50-80}、
 * {@code TileEnergyOutputHatch.java:68-179}。本版的 {@code MachineHatchBlock} 没有
 * {@code getTicker}，方块实体也没有 tick 方法，所以仓口只是被动存储。
 *
 * <p>这里钉两半：
 * <ul>
 *   <li><b>能离线跑的那一半</b>：{@code HatchAutoIo} 的抽取、推出、能量推送和 5–60 tick
 *       自适应节流。它们只碰 {@code IItemHandler} / {@code IEnergyStorage}，不需要 {@code Level}。</li>
 *   <li><b>接线那一半</b>：方块真的向 Forge 要 ticker，ticker 真的走服务端
 *       {@code serverTick}，三种仓分别调用三个出口；输出仓的库存变化会置「立即重试」标记。</li>
 * </ul>
 *
 * <p>为什么节流按 tick 0 起算：旧版 {@code TileEntityRestrictedTick.update()} 先调用
 * {@code doRestrictedTick()}，再把 {@code ticksExisted} 加一，所以第一个世界 tick 就是 0。
 * 这一点不照抄会让「刚放下的总线要等 60 tick 才第一次抽」。
 */
final class AutoIoCheck {

    private AutoIoCheck() {
    }

    static void autoIo() {
        section("A1 仓口自动 IO（0.63.9）");
        throttle();
        pull();
        push();
        energy();
        wiring();
    }

    /**
     * 自适应节流：成功越多间隔越短，失败回到 60 tick；库存变化立即重试。
     */
    private static void throttle() {
        try {
            Method should = helper("shouldWork", int.class, int.class, boolean.class);
            Method success = helper("onSuccess", int.class);
            Method failure = helper("onFailure", int.class);

            report("tick 0 with no recent success: the bus works",
                    (boolean) should.invoke(null, 0, 0, false));
            report("tick 1 with no recent success: it waits for tick 60",
                    !(boolean) should.invoke(null, 1, 0, false));
            report("tick 60 with no recent success: it works again",
                    (boolean) should.invoke(null, 60, 0, false));
            report("a changed inventory works immediately, whatever the tick",
                    (boolean) should.invoke(null, 37, 0, true));
            report("one success shortens the delay to 55 ticks",
                    !(boolean) should.invoke(null, 54, 1, false)
                            && (boolean) should.invoke(null, 55, 1, false));
            report("eleven successes reach the 5-tick floor",
                    (boolean) should.invoke(null, 5, 11, false)
                            && !(boolean) should.invoke(null, 4, 11, false));
            check("a success increments the counter", 1L, (int) success.invoke(null, 0));
            check("the counter stops at 11", 11L, (int) success.invoke(null, 11));
            check("a failure decrements but never goes below 0", 0L, (int) failure.invoke(null, 0));
            check("...and 3 becomes 2", 2L, (int) failure.invoke(null, 3));
        } catch (Throwable broken) {
            report("the adaptive throttle cannot be exercised: " + broken, false);
        }
    }

    /**
     * 输入：从相邻容器抽到自己的槽，空槽放整栈，同类未满只补差额。
     */
    private static void pull() {
        try {
            Method pull = helper("pull", IItemHandler.class, ItemStackHandler.class);

            ItemStackHandler external = new ItemStackHandler(2);
            external.setStackInSlot(0, new ItemStack(Items.STONE, 16));
            ItemStackHandler internal = new ItemStackHandler(2);
            internal.setStackInSlot(0, new ItemStack(Items.STONE, 32));
            boolean moved = (boolean) pull.invoke(null, external, internal);
            report("pull fills a partially filled matching slot",
                    moved && internal.getStackInSlot(0).getCount() == 48
                            && external.getStackInSlot(0).isEmpty());

            external = new ItemStackHandler(1);
            external.setStackInSlot(0, new ItemStack(Items.STONE, 4));
            internal = new ItemStackHandler(2);
            internal.setStackInSlot(0, new ItemStack(Items.DIRT, 3));
            moved = (boolean) pull.invoke(null, external, internal);
            report("pull puts a different item into an empty slot",
                    moved && internal.getStackInSlot(0).getCount() == 3
                            && internal.getStackInSlot(1).getCount() == 4
                            && internal.getStackInSlot(1).getItem() == Items.STONE
                            && external.getStackInSlot(0).isEmpty());

            external = new ItemStackHandler(1);
            internal = new ItemStackHandler(1);
            internal.setStackInSlot(0, new ItemStack(Items.STONE, 1));
            report("an empty external container moves nothing",
                    !(boolean) pull.invoke(null, external, internal)
                            && internal.getStackInSlot(0).getCount() == 1);

            external = new ItemStackHandler(1);
            external.setStackInSlot(0, new ItemStack(Items.STONE, 4));
            internal = new ItemStackHandler(1);
            internal.setStackInSlot(0, new ItemStack(Items.STONE, 64));
            report("a full internal slot is left alone",
                    !(boolean) pull.invoke(null, external, internal)
                            && internal.getStackInSlot(0).getCount() == 64);
        } catch (Throwable broken) {
            report("the pull path cannot be exercised: " + broken, false);
        }
    }

    /**
     * 输出：把内部槽推到相邻容器；空槽可放任意物品，同类只补到对方上限。
     */
    private static void push() {
        try {
            Method push = helper("push", ItemStackHandler.class, IItemHandler.class);

            ItemStackHandler internal = new ItemStackHandler(2);
            internal.setStackInSlot(0, new ItemStack(Items.DIAMOND, 16));
            ItemStackHandler external = new ItemStackHandler(2);
            boolean moved = (boolean) push.invoke(null, internal, external);
            report("push moves a whole stack into an empty external slot",
                    moved && internal.getStackInSlot(0).isEmpty()
                            && external.getStackInSlot(0).getCount() == 16
                            && external.getStackInSlot(0).getItem() == Items.DIAMOND);

            internal = new ItemStackHandler(1);
            internal.setStackInSlot(0, new ItemStack(Items.STONE, 16));
            external = new ItemStackHandler(1);
            external.setStackInSlot(0, new ItemStack(Items.STONE, 60));
            moved = (boolean) push.invoke(null, internal, external);
            report("push tops up a matching external stack and leaves the remainder",
                    moved && external.getStackInSlot(0).getCount() == 64
                            && internal.getStackInSlot(0).getCount() == 12);

            internal = new ItemStackHandler(1);
            internal.setStackInSlot(0, new ItemStack(Items.STONE, 16));
            external = new ItemStackHandler(1);
            external.setStackInSlot(0, new ItemStack(Items.STONE, 64));
            report("a full external slot is skipped",
                    !(boolean) push.invoke(null, internal, external)
                            && internal.getStackInSlot(0).getCount() == 16);

            internal = new ItemStackHandler(1);
            internal.setStackInSlot(0, new ItemStack(Items.STONE, 16));
            external = new ItemStackHandler(1);
            external.setStackInSlot(0, new ItemStack(Items.DIRT, 1));
            report("a different item in the external slot blocks the move",
                    !(boolean) push.invoke(null, internal, external)
                            && internal.getStackInSlot(0).getCount() == 16);
        } catch (Throwable broken) {
            report("the push path cannot be exercised: " + broken, false);
        }
    }

    /**
     * 能量：每 tick 最多推 tier 的 transfer 数，目标拒收时一点不动。
     */
    private static void energy() {
        try {
            Method push = helper("pushEnergy", EnergyStorage.class, IEnergyStorage.class, int.class);

            EnergyStorage source = new EnergyStorage(1000, 128, 128, 1000);
            FakeEnergy target = new FakeEnergy(1000, true);
            int moved = (int) push.invoke(null, source, target, 128);
            check("pushEnergy moves the requested amount", 128L, moved);
            check("...out of the source", 872L, source.getEnergyStored());
            check("...into the target", 128L, target.stored);

            source = new EnergyStorage(1000, 128, 128, 1000);
            target = new FakeEnergy(1000, false);
            moved = (int) push.invoke(null, source, target, 128);
            check("a target that refuses receives nothing", 0L, moved);
            check("...and the source keeps its energy", 1000L, source.getEnergyStored());

            source = new EnergyStorage(1000, 128, 128, 50);
            target = new FakeEnergy(1000, true);
            check("the transfer is capped by the stored energy", 50L,
                    (int) push.invoke(null, source, target, 128));
            check("...and the source is empty afterwards", 0L, source.getEnergyStored());

            source = new EnergyStorage(1000, 128, 128, 1000);
            target = new FakeEnergy(10, true);
            check("the transfer is capped by the target's room", 10L,
                    (int) push.invoke(null, source, target, 128));
            check("...and only the accepted amount leaves the source", 990L, source.getEnergyStored());
        } catch (Throwable broken) {
            report("the energy path cannot be exercised: " + broken, false);
        }
    }

    /**
     * 接线：读源码，但先去掉注释，避免「把调用注释掉」也能过。
     */
    private static void wiring() {
        Path block = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                + "modularmachinery/block/MachineHatchBlock.java");
        Path entity = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                + "modularmachinery/block/MachineHatchBlockEntity.java");
        Path helper = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                + "modularmachinery/block/HatchAutoIo.java");
        try {
            String blockText = withoutComments(Files.readString(block));
            report("the hatch block asks Forge for a ticker", blockText.contains("getTicker"));
            String ticker = methodBody(blockText, "getTicker");
            report("...the ticker drives the hatch entity's serverTick",
                    ticker.contains("MachineHatchBlockEntity") && ticker.contains("serverTick"));
            report("...and it returns nothing on the client", ticker.contains("level.isClientSide"));

            String entityText = withoutComments(Files.readString(entity));
            String tick = methodBody(entityText, "void serverTick");
            report("the hatch entity ticks only on the server",
                    tick.contains("level.isClientSide") && tick.contains("level.getGameTime()"));
            report("...and it is guarded against a second call in the same game tick",
                    tick.contains("lastTick") && tick.contains("gameTime == this.lastTick"));

            String input = methodBody(entityText, "private void tickItemInput");
            String output = methodBody(entityText, "private void tickItemOutput");
            String energy = methodBody(entityText, "private void tickEnergyOutput");
            report("an item input hatch pulls through HatchAutoIo.pull", input.contains("HatchAutoIo.pull"));
            report("an item output hatch pushes through HatchAutoIo.push", output.contains("HatchAutoIo.push"));
            report("an energy output hatch pushes through HatchAutoIo.pushEnergy",
                    energy.contains("HatchAutoIo.pushEnergy"));
            report("...and the server tick dispatches on the three hatch kinds",
                    tick.contains("case ITEM_INPUT") && tick.contains("case ITEM_OUTPUT")
                            && tick.contains("case ENERGY_OUTPUT"));

            String changed = methodBody(entityText, "protected void onContentsChanged(int slot)");
            report("an output bus remembers that its contents changed, so the next tick retries",
                    changed.contains("inventoryChanged = true")
                            && changed.contains("HatchKind.ITEM_OUTPUT"));

            String auto = methodBody(entityText, "public String autoIoDescription");
            report("the throttle state can be asked for",
                    auto.contains("successCounter") && auto.contains("lastTick"));
            Path diagnose = Path.of("C:/mmwork/modular-machinery-reborn/src/main/java/com/reborn/"
                    + "modularmachinery/command/DiagnoseCommand.java");
            report("...and /mm-diagnose prints it for every hatch",
                    withoutComments(Files.readString(diagnose)).contains("hatch.autoIoDescription()"));

            String helperText = withoutComments(Files.readString(helper));
            report("the adaptive window is the original 5..60 ticks",
                    helperText.contains("MIN_WORK_DELAY = 5")
                            && helperText.contains("MAX_WORK_DELAY = 60"));
        } catch (Throwable unreadable) {
            report("reading the hatch sources failed: " + unreadable, false);
        }
    }

    private static Method helper(String name, Class<?>... params) throws Exception {
        Class<?> type = Class.forName("com.reborn.modularmachinery.block.HatchAutoIo");
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

    /** 只实现测试需要的能量目标；行为与 Forge 的 {@code IEnergyStorage} 契约一致。 */
    private static final class FakeEnergy implements IEnergyStorage {

        private final int capacity;
        private final boolean canReceive;
        private int stored;

        private FakeEnergy(int capacity, boolean canReceive) {
            this.capacity = capacity;
            this.canReceive = canReceive;
        }

        @Override public int receiveEnergy(int maxReceive, boolean simulate) {
            if (!this.canReceive) {
                return 0;
            }
            int accepted = Math.min(Math.min(maxReceive, this.capacity - this.stored), this.capacity);
            if (!simulate) {
                this.stored += accepted;
            }
            return accepted;
        }

        @Override public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override public int getEnergyStored() {
            return this.stored;
        }

        @Override public int getMaxEnergyStored() {
            return this.capacity;
        }

        @Override public boolean canExtract() {
            return false;
        }

        @Override public boolean canReceive() {
            return this.canReceive;
        }
    }
}
