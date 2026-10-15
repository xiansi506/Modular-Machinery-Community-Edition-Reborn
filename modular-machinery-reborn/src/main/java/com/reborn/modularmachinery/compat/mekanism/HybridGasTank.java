package com.reborn.modularmachinery.compat.mekanism;

import com.reborn.modularmachinery.block.MachineHatchBlockEntity;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.templates.FluidTank;

import javax.annotation.Nullable;

/**
 * 一个流体仓的**可选气体那一半**：旧版 {@code HybridGasTank extends HybridTank}（198 行）的对应物。
 *
 * <p><b>为什么挂在流体仓上而不是做一种新方块。</b>旧版就是这么做的：气体仓是**已有的流体仓**
 * 顺带具备的能力（{@code HybridGasTank} 的宿主是 {@code TileFluidTank} 与 {@code GuiContainerFluidHatch}，
 * 全部 {@code @Optional.Method(modid = "mekanism")}）。这条路与本项目的 D8 不冲突——
 * 方块只能在 mod 构造期注册、注册表随后冻结，而「给已有方块加一种能力」不需要新方块。
 *
 * <p><b>一个罐子要么装流体要么装气体</b>，这正是旧版 {@code HybridGasTank} 的语义
 * （它的 {@code setFluid} 与 {@code setGas} 互相覆盖同一个罐）。所以本类持有一个
 * {@link FluidTank} 的引用，并在装气体时把它清空；容量用那个罐的容量。
 *
 * <p><b>本类会加载 Mekanism 的类，所以它绝对不能被主模组引用。</b>主模组拿到它只有一条路：
 * {@code MekanismModule} 在 {@code FMLCommonSetupEvent} 里造一个、包成
 * {@code BlockEntityPortMapper} 交给 {@code ModCompat}。没装 Mekanism 时那个模块根本不会被调用，
 * 于是这个类不会被解析——这就是 {@code compat/} 隔离的全部意义。
 */
final class HybridGasTank implements IGasHandler {

    /** 只有一个罐，与旧版一样（{@code HybridGasTank} 就是一个 {@code HybridTank}）。 */
    private static final int TANKS = 1;

    private final MachineHatchBlockEntity hatch;
    private final FluidTank fluid;
    /**
     * 气罐的容量。
     *
     * <p><b>它不再借 {@code fluid.getCapacity()}</b>：那是流体罐的**总**容量，而气罐的空间要靠
     * 「总容量减去当前装着的流体」算出来，否则两种东西会同时挤进同一个罐里——旧版的
     * {@code HybridGasTank} 根本不可能出现那个状态（`setFluid` 与 `setGas` 互相覆盖同一个罐）。
     *
     * <p>取值在构造时定下（{@code fluid.getCapacity()}），因为流体仓的容量由它的等级决定、永不改变。
     */
    private final long capacity;

    /** 每当内容变化时通知宿主去存盘并（流体那一半需要）同步给客户端。 */
    private final Runnable onChanged;

    /** 当前气体，数量为 1 的载体；{@code EMPTY_GAS} 表示罐里是气以外的状态或空的。 */
    private GasStack stored = GasStack.EMPTY;
    private long amount;

    HybridGasTank(MachineHatchBlockEntity hatch, FluidTank fluid, Runnable onChanged) {
        this.hatch = hatch;
        this.fluid = fluid;
        this.onChanged = onChanged;
        this.capacity = Math.max(1L, fluid.getCapacity());
    }

    /** 这个仓是否已经有气体那一半（只有流体仓才该有）。 */
    boolean isValid() {
        return this.hatch != null && this.fluid != null;
    }

    // ------------------------------------------------------------------ IGasHandler

    @Override
    public int getTanks() {
        return TANKS;
    }

    @Override
    public GasStack getChemicalInTank(int tank) {
        return tank == 0 ? current() : GasStack.EMPTY;
    }

    @Override
    public void setChemicalInTank(int tank, GasStack stack) {
        if (tank != 0) {
            return;
        }
        if (stack == null || stack.isEmpty()) {
            clear();
        } else {
            setGas(stack);
        }
    }

    @Override
    public long getTankCapacity(int tank) {
        return tank == 0 ? this.capacity : 0L;
    }

    @Override
    public boolean isValid(int tank, GasStack stack) {
        return tank == 0;
    }

    /**
     * 灌入。**一个罐要么装流体、要么装气体**（旧版 {@code HybridGasTank} 的语义：`setFluid` 与 `setGas`
     * 互相覆盖同一个 {@code HybridTank}）。
     *
     * <p><b>流体会挡住气体，而灌气体会把流体清掉。</b>两条都是上面那句话的直接后果，缺一条就不自洽：
     * 只挡不清则永远进不去（流体仓里通常有流体），只清不挡则两种东西同时存在（旧版不可能出现的状态）。
     */
    @Override
    public GasStack insertChemical(int tank, GasStack stack, Action action) {
        GasStack result = insertChemicalInternal(tank, stack, action);
        // 每次插入尝试都记进本罐的计数器（**与日志开关无关**，见 InsertTally），
        // 并在打开调试开关时同时打印一行。两者都要：计数器是 /mm-diagnose 读的持久事实，
        // 而日志能给出顺序与上下文。
        MekanismProbe.insert("tank=" + tank + " stack=" + describe(stack) + " action=" + action
                + " stored=" + describe(this.stored) + " amount=" + this.amount
                + " capacity=" + getTankCapacity(0) + " -> " + describe(result));
        return result;
    }

    private GasStack insertChemicalInternal(int tank, GasStack stack, Action action) {
        if (tank != 0 || stack == null || stack.isEmpty()) {
            MEKANISM_PROBE.note("参数不对（tank=" + tank + " 或空栈）");
            return stack == null ? GasStack.EMPTY : stack;
        }
        // 罐里已经有流体：**先把它清掉再收气体**。旧版就是这么做的——一个罐只装一样
        // （`setFluid` 与 `setGas` 互相覆盖同一个罐）。
        //
        // 问的是 `realFluidAmount()` 而不是 `getFluidAmount()`：后者在有气体时被挡成 0
        // （见 MachineHatchBlockEntity#FluidTankWithGasPriority），用它判断会永远认为「罐里没流体」，
        // 于是旧液体留着不清——气体被抽空之后它又冒出来。
        if (this.hatch.realFluidAmount() > 0) {
            if (action.execute()) {
                this.hatch.clearFluidForGas();
                this.onChanged.run();
            }
        }
        if (!sameType(stack)) {
            // 罐里是**别种气体**：不受理。回整份，表示一点都没进去。
            MEKANISM_PROBE.note("被拒：罐里是别种气体（现在 " + describe(this.stored)
                    + "，来的是 " + describe(stack) + "）");
            return stack;
        }
        long free = this.capacity - this.amount;
        if (free <= 0L) {
            MEKANISM_PROBE.note("被拒：气罐已满（容量 " + this.capacity + "，已存 " + this.amount
                    + "，来的是 " + describe(stack) + "）");
            return stack;
        }
        long accepted = Math.min(free, stack.getAmount());
        if (accepted <= 0L) {
            MEKANISM_PROBE.note("被拒：可接受量为 0");
            return stack;
        }
        if (action.execute()) {
            MEKANISM_PROBE.noteExecute();
            this.amount += accepted;
            this.stored = new GasStack(stack, 1L);
            changed();
        }
        MEKANISM_PROBE.note("受理 " + describe(stack) + " 的 " + accepted + " mB（" + action + "）");
        long leftover = stack.getAmount() - accepted;
        return leftover <= 0L ? GasStack.EMPTY : new GasStack(stack, leftover);
    }

    /**
     * 插入尝试的计数器，挂在每个罐上（不是全局）。
     *
     * <p>为什么不放进 {@link MekanismProbe} 的全局计数：诊断要回答的是「**这个**仓被灌过没有」，
     * 而全局计数会被别的仓、别的维度的流量污染。
     */
    private final InsertTally MEKANISM_PROBE = new InsertTally();

    /** 一个罐上的插入尝试统计；给 {@code /mm-diagnose} 读，**不依赖日志开关**。 */
    static final class InsertTally {
        private int calls;
        private int successful;
        private int withExecute;
        private final java.util.Map<String, Integer> rejections = new java.util.LinkedHashMap<>();

        synchronized void note(String note) {
            this.calls++;
            if (note.startsWith("受理")) {
                this.successful++;
            } else {
                this.rejections.merge(note, 1, Integer::sum);
            }
        }

        synchronized void noteExecute() {
            this.withExecute++;
        }

        synchronized String describe() {
            if (this.calls == 0) {
                return "从未被调用（管道没有往里灌过）";
            }
            return "调用 " + this.calls + " 次，受理 " + this.successful + " 次"
                    + (this.withExecute > 0 ? "（其中 EXECUTE " + this.withExecute + " 次）" : "")
                    + (this.rejections.isEmpty() ? "" : "，拒绝明细 " + this.rejections);
        }
    }

    /** 给探针用的一行描述；不碰注册表之外的东西。 */
    private static String describe(@Nullable GasStack stack) {
        if (stack == null) {
            return "null";
        }
        if (stack.isEmpty()) {
            return "EMPTY";
        }
        return stack.getTypeRegistryName() + " x" + stack.getAmount();
    }

    /** 这个罐的当前内容，给 {@code /mm-diagnose} 用（一行，含容量）。标签由调用方加，这里不重复写。 */
    String describeContents() {
        return describeShort() + " / 容量 " + this.capacity + " mB";
    }

    /**
     * 这个罐的内容，**不含容量**——给界面 tooltip 用（容量由语言键的另一个 {@code %s} 承担）。
     *
     * <p>拆成两个方法是有原因的：上一版把「含容量的整句」塞进了一个只有一个 {@code %s} 的语言键，
     * 于是 tooltip 印成 `mekanism:brine x1 x32000 mB / 容量 32000 mB`——同一件事印了两遍。
     * 界面要的是**两个字段**（内容、容量），不是一句已经拼好的话。
     */
    String describeShort() {
        return this.amount <= 0L || this.stored.isEmpty()
                ? "空"
                : describe(this.stored) + " x" + this.amount + " mB";
    }

    /** 这个罐的容量。 */
    long capacity() {
        return this.capacity;
    }

    /** 这个罐里气体的颜色（由 Mekanism 的 ChemicalStack 给出），查不到时中性灰。 */
    int tint() {
        if (this.amount <= 0L || this.stored.isEmpty()) {
            return 0x9E9E9E;
        }
        try {
            return this.stored.getChemicalTint();
        } catch (Throwable unavailable) {
            return 0x9E9E9E;
        }
    }

    /** 这个罐里气体的量，给菜单的数据槽用。 */
    long amountForMenu() {
        return this.amount;
    }

    /** 这个罐上的插入尝试统计，给 {@code /mm-diagnose} 用。 */
    String describeInserts() {
        return MEKANISM_PROBE.describe();
    }

    @Override
    public GasStack extractChemical(int tank, long amount, Action action) {
        if (tank != 0 || amount <= 0L || this.amount <= 0L) {
            return GasStack.EMPTY;
        }
        long taken = Math.min(amount, this.amount);
        GasStack out = new GasStack(this.stored, taken);
        if (action.execute()) {
            this.amount -= taken;
            if (this.amount <= 0L) {
                clear();
            } else {
                changed();
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ NBT

    /** 存盘；键名沿用旧版 {@code HybridGasTank.writeGasToNBT} 的 {@code "gas"}。 */
    void writeToNbt(CompoundTag tag) {
        if (this.amount <= 0L || this.stored.isEmpty()) {
            return;
        }
        CompoundTag gas = new CompoundTag();
        // getTypeRegistryName() 而不是 getType().getRegistryName()：前者是 ChemicalStack 为这件事提供的访问器，
        // 也是 1.20.1 里「这个栈是什么化学」的权威答法。
        gas.putString("id", String.valueOf(this.stored.getTypeRegistryName()));
        gas.putLong("amount", this.amount);
        tag.put(TAG_GAS, gas);
    }

    /**
     * 读盘。
     *
     * <p>读到一个**注册表里没有**的气体时**静默清空**而不是抛异常：一份存档可能在装了某个模组时写下气体、
     * 之后那个模组被卸掉。抛异常会让**整个世界打不开**，而清空只是丢掉一个仓里的内容——
     * 后者可以恢复，前者不行。
     *
     * <p><b>但要记下「为什么读不出来」</b>（0.57.0）：区分「存档里写着一种注册表里没有的气体」（那就是
     * 上一个自然段说的情况，清空是对的）与「**注册表此刻还没就绪**」（那是本模组的时机问题，
     * 气体本来读得回来）。两者都会走到 {@code lookup} 返回 null，而它们在玩家眼里都只是
     * 「气罐空了」——所以这里把两种情形分别计数，由 {@code /mm-diagnose} 读出来。
     */
    void readFromNbt(CompoundTag tag) {
        this.stored = GasStack.EMPTY;
        this.amount = 0L;
        if (!tag.contains(TAG_GAS)) {
            return;
        }
        CompoundTag gas = tag.getCompound(TAG_GAS);
        long saved = gas.getLong("amount");
        if (saved <= 0L) {
            return;
        }
        String idText = gas.getString("id");
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Gas type = id == null ? null : lookup(id);
        if (type == null) {
            // 记下原始文本：这是「存档里写了什么」的唯一证据，而它读不出来时最容易丢的就是它。
            lastUnreadable = (idText + " x" + saved + " mB（" + (id == null ? "id 无法解析" : "注册表此刻没有它")
                    + "）");
            UNREADABLE.incrementAndGet();
            return;
        }
        this.stored = new GasStack(type, 1L);
        this.amount = Math.min(saved, this.capacity);
        RESTORED.incrementAndGet();
    }

    /** 读盘时「存档里写了气体、但这次读不出来」的次数。 */
    private static final java.util.concurrent.atomic.AtomicInteger UNREADABLE =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 读盘成功恢复气体的次数。 */
    private static final java.util.concurrent.atomic.AtomicInteger RESTORED =
            new java.util.concurrent.atomic.AtomicInteger();

    private static volatile String lastUnreadable = "（没有）";

    /**
     * 气体读盘的两个计数与最近一次读不出来的原始内容，给 {@code /mm-diagnose} 用。
     *
     * <p>为什么必须有：所有者报过「上一个会话灌进去的 brine，在新会话里是空的」。
     * 这件事可能是他抽走了，也可能是**写得进去、读不回来**——而后者从游戏里看只是「罐空了」，
     * 没有任何线索。这一行让下一次出现时能立刻判定，而不是靠回忆。
     */
    static String restoreCounters() {
        return "读盘恢复成功 " + RESTORED.get() + " 次 / **存档里有却读不出来** " + UNREADABLE.get()
                + " 次；最近一次读不出来的是: " + lastUnreadable;
    }

    /** 只在盒子内用，见 {@link MekanismAnchor}；这里复用同一段「注册表可能不在」的处理。 */
    @Nullable
    private static Gas lookup(ResourceLocation id) {
        try {
            var registry = MekanismAPI.gasRegistry();
            if (registry == null) {
                return null;
            }
            Gas gas = registry.getValue(id);
            return gas == MekanismAPI.EMPTY_GAS ? null : gas;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** 存档键名，旧版用的就是 {@code "gas"}。 */
    static final String TAG_GAS = "gas";

    // ------------------------------------------------------------------ internals

    private GasStack current() {
        return this.amount <= 0L || this.stored.isEmpty()
                ? GasStack.EMPTY
                : new GasStack(this.stored, this.amount);
    }

    private boolean sameType(GasStack stack) {
        return this.stored.isEmpty() || this.stored.isTypeEqual(stack);
    }

    private void setGas(GasStack stack) {
        this.stored = new GasStack(stack, 1L);
        this.amount = Math.min(stack.getAmount(), this.capacity);
        this.fluid.setFluid(FluidStack.EMPTY);
        changed();
    }

    private void clear() {
        this.stored = GasStack.EMPTY;
        this.amount = 0L;
        changed();
    }

    private void changed() {
        if (this.onChanged != null) {
            this.onChanged.run();
        }
    }

    // ------------------------------------------------------------------ capability

    /**
     * 这个仓的气体能力处理器**就是本对象自己**——`IGasHandler` 的实现与状态都在这里。
     *
     * <p>不在这里判断「哪个实体有没有气体那一半」：那是映射器的事（它知道哪些实体是流体仓，
     * 也知道自己是不是该认领这个能力）。本类只管一个仓的行为。
     */
    IGasHandler handler() {
        return this;
    }
}