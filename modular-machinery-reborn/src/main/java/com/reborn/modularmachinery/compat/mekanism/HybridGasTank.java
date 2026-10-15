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
    /** 每当内容变化时通知宿主去存盘并（流体那一半需要）同步给客户端。 */
    private final Runnable onChanged;

    /** 当前气体，数量为 1 的载体；{@code EMPTY_GAS} 表示罐里是流体或空的。 */
    private GasStack stored = GasStack.EMPTY;
    private long amount;

    HybridGasTank(MachineHatchBlockEntity hatch, FluidTank fluid, Runnable onChanged) {
        this.hatch = hatch;
        this.fluid = fluid;
        this.onChanged = onChanged;
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
        return tank == 0 ? this.fluid.getCapacity() : 0L;
    }

    @Override
    public boolean isValid(int tank, GasStack stack) {
        return tank == 0;
    }

    /**
     * 灌入。**先看这一罐是不是已经装着别的东西**——旧版 {@code HybridGasTank} 一个罐只装一样。
     *
     * <p>数量用 {@code long} 收（{@code IGasHandler} 的签名如此），但罐容量是 {@code int}，
     * 所以按容量减去已存量来夹。
     */
    @Override
    public GasStack insertChemical(int tank, GasStack stack, Action action) {
        GasStack result = insertChemicalInternal(tank, stack, action);
        // 探针：只在打开调试开关时打印，而且**插入路径每次都打**（限次见 MekanismProbe）。
        // 为什么必须打在「返回什么」上：`进不去` 有三个完全不同的原因——能力没被问到、
        // 被 sameType 拒了、容量算成 0——它们在日志里都表现为「什么都没发生」。
        MekanismProbe.insert("tank=" + tank + " stack=" + describe(stack) + " action=" + action
                + " stored=" + describe(this.stored) + " amount=" + this.amount
                + " capacity=" + getTankCapacity(0) + " -> " + describe(result));
        return result;
    }

    private GasStack insertChemicalInternal(int tank, GasStack stack, Action action) {
        if (tank != 0 || stack == null || stack.isEmpty()) {
            return stack == null ? GasStack.EMPTY : stack;
        }
        if (!sameType(stack)) {
            // 罐里是流体（或有别种气体）：不受理。回整份，表示一点都没进去。
            return stack;
        }
        long free = getTankCapacity(0) - this.amount;
        if (free <= 0L) {
            return stack;
        }
        long accepted = Math.min(free, stack.getAmount());
        if (accepted <= 0L) {
            return stack;
        }
        if (action.execute()) {
            this.amount += accepted;
            // 装了气体就把流体清掉：一个罐只装一样（旧版 setFluid/setGas 互相覆盖）。
            this.fluid.setFluid(FluidStack.EMPTY);
            changed();
        }
        long leftover = stack.getAmount() - accepted;
        return leftover <= 0L ? GasStack.EMPTY : new GasStack(stack, leftover);
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

    /** 这个罐的当前内容，给 {@code /mm-diagnose} 用。 */
    String describeContents() {
        String content = this.amount <= 0L || this.stored.isEmpty()
                ? "空"
                : describe(this.stored) + " x" + this.amount + " mB";
        return content + " / 容量 " + getTankCapacity(0) + " mB";
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
        ResourceLocation id = ResourceLocation.tryParse(gas.getString("id"));
        Gas type = id == null ? null : lookup(id);
        if (type == null) {
            return;
        }
        this.stored = new GasStack(type, 1L);
        this.amount = Math.min(saved, getTankCapacity(0));
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
        this.amount = Math.min(stack.getAmount(), getTankCapacity(0));
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