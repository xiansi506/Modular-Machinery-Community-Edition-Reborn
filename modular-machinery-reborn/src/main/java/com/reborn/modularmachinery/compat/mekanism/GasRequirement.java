package com.reborn.modularmachinery.compat.mekanism;

import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.IOType;
import com.reborn.modularmachinery.recipe.MachineRequirement;
import com.reborn.modularmachinery.recipe.RecipeModifier;
import com.reborn.modularmachinery.recipe.RecipeModifiers;
import com.reborn.modularmachinery.recipe.RequirementType;
import com.reborn.modularmachinery.recipe.RequirementTypes;
import mekanism.api.chemical.gas.GasStack;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * 一条气体（Mekanism 化学体系里的 {@code Gas}）输入或输出需求。
 *
 * <p>它是本项目的**第一个真兼容模块**，形状逐条对着自己的流体版本 {@link
 * com.reborn.modularmachinery.recipe.FluidRequirement}：旧的 {@code gas} 与 {@code gas_pertick}
 * 正好对应 {@code fluid} 与 {@code fluid} + {@code "perTick": true}，差别只有「借哪套 handler 读液位」。
 * 这也是旧版自己的形状（{@code RequirementGas} / {@code RequirementGasPerTick} 与
 * {@code RequirementFluid} / {@code RequirementFluidPerTick} 同构）。
 *
 * <h2>与旧版 1.12.2 的三处差异（都是 1.20.1 逼出来的，不是选择）</h2>
 *
 * <ol>
 *   <li><b>气体的身份是全限定 id。</b>旧版写 {@code "gas": "oxygen"}，
 *       因为它的 {@code GasRegistry.getGas(String)} 收字符串；1.20.1 的
 *       {@code MekanismAPI.gasRegistry()} 是 Forge 注册表，键是 {@link net.minecraft.resources.ResourceLocation}，
 *       所以配方必须写 {@code "gas": "mekanism:oxygen"}。**旧版气体配方文件因此不能照旧读**，
 *       这一条写在本类的报错消息里，也登记在 {@code docs/波5.3-勘测-Mekanism化学体系.md} §3。</li>
 *   <li><b>数量是 {@code long} 的边界。</b>{@code ChemicalStack#getAmount()} 返回 {@code long}，
 *       而本项目的引擎（{@code parallelism}、{@code RecipeModifiers}、{@code MachineRequirement}）都是 {@code int}。
 *       本类**对外一律用 int**，只在调用 Mekanism 时做 {@code (long)} 提升——
 *       反过来（对外暴露 long）会把 long 溢出的可能性引进整个结算路径，那是另一件事。</li>
 *   <li><b>模拟与执行是两个方法参数。</b>旧版 {@code IGasHandler} 的 {@code receiveGas(..., boolean doTransfer)}
 *       把「模拟」写成一个布尔；1.20.1 的 {@code IChemicalHandler} 用 {@code Action.SIMULATE/EXECUTE}。
 *       语义相同，见 {@link GasIo}。</li>
 * </ol>
 *
 * <h2>本类为什么只被 Mekanism 在场时加载</h2>
 *
 * <p>它直接 import 了 {@code mekanism.api.chemical.gas.GasStack}，所以**类加载就要求 Mekanism 在场**。
 * 这不是疏忽：`compat/` 的设计就是「模块只在它依赖的模组在场时被注册」，而注册动作会读
 * {@link RequirementTypes}、间接触发本类型的构造。没装 Mekanism 时这个类不会被解析到。
 *
 * <p>同一条约束意味着**离线复现器必须有 Mekanism 的 jar 才能驱动本类**——
 * 这条依赖关系是真实的，不是可以绕过的，测试装置因此也要带上它。
 */
public final class GasRequirement extends MachineRequirement {

    /** 旧版 {@code RequirementGas} 的两个失败键（{@code doGasIO} 的 {@code CraftCheck.failure}），逐字。 */
    public static final String FAILURE_GAS_INPUT = "craftcheck.failure.gas.input";
    public static final String FAILURE_GAS_OUTPUT_SPACE = "craftcheck.failure.gas.output.space";

    private final GasStack gas;
    private final int amount;
    private final float chance;

    /**
     * @param gas    气体本身，**数量被归一成 1**（与 {@code FluidRequirement} 的 {@code new FluidStack(fluid, 1)} 同一条理由：
     *               数量是这个需求的字段，不是那个「类型载体」的属性）
     * @param amount 一份合成消耗或产出的量
     * @param perTick 每 tick 结算（旧版 {@code gas_pertick}）还是开工/完工时一次性结算（旧版 {@code gas}）
     */
    public GasRequirement(IOType ioType, GasStack gas, int amount, float chance, boolean perTick) {
        super(ioType, perTick);
        this.gas = copyWithAmount(gas, 1L);
        this.amount = Math.max(1, amount);
        this.chance = chance;
    }

    /** 把 {@code amount} 换成 {@code amount} 的那个栈，用于对外给出「类型 + 量」。 */
    private static GasStack copyWithAmount(GasStack stack, long amount) {
        return new GasStack(stack, amount);
    }

    /**
     * 这种需求在配方修改器里算哪一类。
     *
     * <p>**返回 {@link RecipeModifier.Target#ALL}，这是有意的，而且有代价**：
     * {@code Target} 是波 5.2 决定「本轮保持闭枚举」的那个枚举，它没有气体项。若为了「有归属」而返回
     * {@code FLUID}，那么一条写 {@code "target": "fluid"} 的修改器会**静默地**作用到气体上；
     * 返回 {@code ALL} 则只有通配修改器（{@code "target": "*"}）能碰到它——已实测
     * {@code RecipeModifier#matches} 对 {@code ALL} 一律返回 true。
     * 于是「结构修改器写了 {@code *} 就该作用到所有需求」这条直觉仍然成立，而写具体项碰不到气体是可解释的。
     */
    @Override
    public RecipeModifier.Target modifierTarget() {
        return RecipeModifier.Target.ALL;
    }

    /**
     * JEI 里占**罐格但只给文字**。
     *
     * <p>为什么不是 {@code FLUID}：那条路会让 JEI 去 cast {@code FluidRequirement} 并调它的
     * {@code fluidType()}——气体不是流体，那条路走不通。而旧版在 JEI 里对气体**本来也只有 tooltip**
     * （没有贴图），所以「占罐格 + 文字」既是本版能做到的，也是与原版一致的。
     *
     * <p>在此之前这里没有覆写，于是走默认的物品格，而物品格的绘制会把需求 cast 成
     * {@code ItemRequirement} —— **打开那个机器的 JEI 页就崩**。这一条同时是那个缺陷的修复。
     */
    @Override
    public TankKind tankKind() {
        return TankKind.GAS;
    }

    @Override
    public RequirementType type() {
        // 每 tick 的那一种在注册表里是另一个 id（旧版的 gas_pertick），所以这里必须按 perTick 分辨，
        // 不能只按 Java 类分辨。
        return this.perTick() ? MekanismModule.TYPE_GAS_PER_TICK : MekanismModule.TYPE_GAS;
    }

    /** 气体的类型载体，数量为 1——与 {@code FluidRequirement#fluidType()} 同一个用途。 */
    public GasStack gasType() {
        return copyWithAmount(this.gas, 1L);
    }

    public GasStack gas() {
        return copyWithAmount(this.gas, this.amount);
    }

    public int amount() {
        return this.amount;
    }

    public float chance() {
        return this.chance;
    }

    /** 一份合成消耗或产出的量，过完这个需求的修改器之后。 */
    public int modifiedAmount(RecipeModifiers modifiers) {
        double modified = modifiers.apply(this, (double) this.amount, false);
        return (int) Math.max(0.0, Math.floor(modified));
    }

    /** 一份合成产出它所用的概率，过完 chance 那一桶修改器之后。 */
    public float modifiedChance(RecipeModifiers modifiers) {
        if (this.ioType() != IOType.OUTPUT) {
            return 1.0F;
        }
        return (float) Math.max(0.0, Math.min(1.0, modifiers.apply(this, (double) this.chance, true)));
    }

    @Override
    public boolean canSatisfy(HatchCollection ports) {
        return canSatisfy(ports, RecipeModifiers.EMPTY);
    }

    /** {@link #canSatisfy} 带修改器，看的是整个 {@code parallelism} 份。 */
    public boolean canSatisfy(HatchCollection ports, RecipeModifiers modifiers) {
        int total = modifiedAmount(modifiers) * this.parallelism();
        if (this.ioType() == IOType.INPUT) {
            return total <= 0 || GasIo.count(ports.gasInputs(), this.gas) >= total;
        }
        return total <= 0 || GasIo.canInsert(ports.gasOutputs(), this.gas, total);
    }

    /**
     * 旧版 {@code RequirementGas} 的两个失败键，按方向分开（{@code doGasIO} 的 switch），逐字沿用。
     *
     * <p>只在 {@link #canSatisfy} 已经答过 {@code false} 之后被问——这是本项目 {@code startFailure} 的契约。
     */
    @Nullable
    @Override
    public String startFailure(HatchCollection ports) {
        return this.ioType() == IOType.INPUT ? FAILURE_GAS_INPUT : FAILURE_GAS_OUTPUT_SPACE;
    }

    @Override
    public int parallelLimit(HatchCollection ports, RecipeModifiers modifiers, int ceiling) {
        if (!isParallelizable()) {
            return 1;
        }
        int perCopy = modifiedAmount(modifiers);
        if (perCopy <= 0) {
            return ceiling;
        }
        if (this.ioType() == IOType.INPUT) {
            return Math.min(ceiling, GasIo.count(ports.gasInputs(), this.gas) / perCopy);
        }
        return Math.min(ceiling, GasIo.capacity(ports.gasOutputs(), this.gas) / perCopy);
    }

    @Override
    public boolean apply(HatchCollection ports, RandomSource random) {
        return transfer(ports, random, RecipeModifiers.EMPTY);
    }

    @Override
    public boolean tick(HatchCollection ports, RandomSource random) {
        return transfer(ports, random, RecipeModifiers.EMPTY);
    }

    /** {@link #apply} 带修改器。 */
    public boolean apply(HatchCollection ports, RandomSource random, RecipeModifiers modifiers) {
        return transfer(ports, random, modifiers);
    }

    /** {@link #tick} 带修改器。 */
    public boolean tick(HatchCollection ports, RandomSource random, RecipeModifiers modifiers) {
        return transfer(ports, random, modifiers);
    }

    private boolean transfer(HatchCollection ports, RandomSource random, RecipeModifiers modifiers) {
        int total = modifiedAmount(modifiers) * this.parallelism();
        if (this.ioType() == IOType.INPUT) {
            return total <= 0 || GasIo.extract(ports.gasInputs(), this.gas, total);
        }
        if (total <= 0 || modifiedChance(modifiers) < 1.0F && random.nextFloat() >= modifiedChance(modifiers)) {
            return true;
        }
        return GasIo.insert(ports.gasOutputs(), this.gas, total);
    }

    @Override
    public String describe() {
        String name = this.gas.getType().getTextComponent().getString();
        String chanceText = this.ioType() == IOType.OUTPUT && this.chance < 1.0F
                ? String.format(Locale.ROOT, " (%.0f%%)", this.chance * 100.0F) : "";
        String perTickText = this.perTick() ? "/t" : "";
        return this.amount + " mB " + name + perTickText + chanceText;
    }
}
