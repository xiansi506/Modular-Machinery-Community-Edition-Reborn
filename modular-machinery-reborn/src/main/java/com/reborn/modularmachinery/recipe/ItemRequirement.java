package com.reborn.modularmachinery.recipe;

import com.reborn.modularmachinery.machine.HatchCollection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * An item input or output of a machine recipe.
 *
 * <p>Inputs accept an {@link Ingredient}, which covers plain items as well as the item tags that replace the
 * original's ore dictionary. Both directions carry the original's {@code chance}: an output is only produced
 * when its roll hits, and an input is only consumed when its roll hits. A missed input roll skips that
 * requirement for this craft without failing the craft, exactly as {@code RequirementItem#startCrafting} did.
 * Outputs are rolled on their own, which is why the centrifuge wool recipe lists four separate string outputs
 * with different chances instead of one output with an amount range.
 *
 * <p>Outputs also honour the original's {@code minAmount}/{@code maxAmount} pair: the amount is rolled per
 * craft. The space check always uses {@code maxAmount}, so the worst case is reserved before the craft is
 * allowed to end. Inputs require a fixed {@code amount}; the loader rejects a range there rather than picking
 * an interpretation.
 *
 * <p><b>Modifiers.</b> Both the amount and the chance go through the machine's {@link RecipeModifiers} first,
 * which is what the original's {@code applyModifierAmount} and
 * {@code RecipeModifier.applyModifiers(context, this, this.chance, true)} did. Amount and chance are separate
 * modifier buckets there and stay separate here; that is the whole purpose of {@code affectChance}.
 *
 * <p><b>Parallelism.</b> Every amount is multiplied by {@link #parallelism()} — the original's
 * {@code maxConsume = toConsume * maxMultiplier}. The chance is <b>not</b> multiplied: the original rolled
 * {@code chance.canWork(...)} once per settlement and then moved {@code parallelism} copies through that one
 * decision, so a 25% output at parallelism 4 yields 4 items 25% of the time rather than 4 independent rolls.
 * The roll is taken per copy only where the recipe itself rolls a random amount, because
 * {@code minAmount}/{@code maxAmount} is a property of a single craft's result.
 */
public final class ItemRequirement extends MachineRequirement {

    /** The original's own two messages ({@code RequirementItem.java:288-292}), key for key. */
    public static final String FAILURE_ITEM_INPUT = "craftcheck.failure.item.input";
    public static final String FAILURE_ITEM_OUTPUT_SPACE = "craftcheck.failure.item.output.space";

    @Nullable private final Ingredient ingredient;
    @Nullable private final ItemStack stack;
    private final int minAmount;
    private final int maxAmount;
    private final float chance;
    /** The original's {@code nbt} block; input matching only. */
    @Nullable private final NbtPredicate nbt;
    /** The original's {@code nbt-display}, shown by JEI instead of the parsed tag. */
    @Nullable private final CompoundTag displayTag;

    private ItemRequirement(IOType ioType, @Nullable Ingredient ingredient, @Nullable ItemStack stack,
                            int minAmount, int maxAmount, float chance, @Nullable NbtPredicate nbt,
                            @Nullable CompoundTag displayTag) {
        super(ioType, false);
        this.ingredient = ingredient;
        this.stack = stack == null ? null : stack.copyWithCount(1);
        this.minAmount = Math.max(1, minAmount);
        this.maxAmount = Math.max(this.minAmount, maxAmount);
        this.chance = chance;
        this.nbt = nbt;
        this.displayTag = displayTag == null ? null : displayTag.copy();
    }

    public static ItemRequirement input(Ingredient ingredient, int amount) {
        return input(ingredient, amount, null, null);
    }

    /**
     * An item input with the original's {@code nbt} template.
     *
     * <p>{@code nbt} filters the input slots; {@code displayTag} is only for JEI, and when it is absent the
     * preview shows the template's concrete form ({@link #previewTag()}).
     */
    public static ItemRequirement input(Ingredient ingredient, int amount, @Nullable NbtPredicate nbt,
                                        @Nullable CompoundTag displayTag) {
        return input(ingredient, amount, nbt, displayTag, 1.0F);
    }

    /**
     * An item input that is consumed with {@code chance}.
     *
     * <p>A failed roll consumes nothing and does not fail the craft. The original did the same in
     * {@code RequirementItem#startCrafting}: the roll gated {@code doItemIO}, while {@code canStartCrafting}
     * only checked that the ingredients were present.
     */
    public static ItemRequirement input(Ingredient ingredient, int amount, @Nullable NbtPredicate nbt,
                                        @Nullable CompoundTag displayTag, float chance) {
        return new ItemRequirement(IOType.INPUT, ingredient, null, amount, amount, chance, nbt, displayTag);
    }

    public static ItemRequirement output(ItemStack stack, int minAmount, int maxAmount, float chance) {
        return output(stack, minAmount, maxAmount, chance, null);
    }

    /**
     * An item output whose stack already carries the concrete tag, plus an optional {@code nbt-display}
     * override for JEI.
     */
    public static ItemRequirement output(ItemStack stack, int minAmount, int maxAmount, float chance,
                                         @Nullable CompoundTag displayTag) {
        return new ItemRequirement(IOType.OUTPUT, null, stack, minAmount, maxAmount, chance, null, displayTag);
    }

    @Override
    public RecipeModifier.Target modifierTarget() {
        return RecipeModifier.Target.ITEM;
    }

    @Override
    public int sortingWeight() {
        return PRIORITY_WEIGHT_ITEM;
    }

    @Override
    public RequirementType type() {
        return Holder.TYPE;
    }

    /**
     * 这个类对应注册表里的哪一行。
     *
     * <p>放在一个嵌套类里，是为了避开**类初始化顺序**：{@code RequirementTypes} 的静态初始化会构造内置五种的
     * 读写器，如果 {@code ItemRequirement} 自己持有一个 {@code static final RequirementType TYPE = RequirementTypes.ITEM;}，
     * 那么 {@code RequirementTypes} 初始化到一半、{@code ITEM} 还是 {@code null} 时这个字段就可能被读成
     * {@code null}。放进 {@code Holder} 之后，它只在**第一次问 type() 时**才初始化，那时注册表必然已经好了。
     */
    private static final class Holder {
        private static final RequirementType TYPE = RequirementTypes.ITEM;
    }

    /** Upper bound of the amount: what the space check must reserve. */
    public int amount() {
        return this.maxAmount;
    }

    public int minAmount() {
        return this.minAmount;
    }

    public int maxAmount() {
        return this.maxAmount;
    }

    public boolean hasAmountRange() {
        return this.minAmount != this.maxAmount;
    }

    public float chance() {
        return this.chance;
    }

    @Nullable
    public Ingredient ingredient() {
        return this.ingredient;
    }

    @Nullable
    public NbtPredicate nbt() {
        return this.nbt;
    }

    /** The explicit {@code nbt-display} tag, or {@code null} when the file wrote none. */
    @Nullable
    public CompoundTag displayTag() {
        return this.displayTag == null ? null : this.displayTag.copy();
    }

    /**
     * Whether an input slot stack pays this requirement: the ingredient must match <b>and</b> the stack's NBT
     * must satisfy the template. Used by every count, extraction and container scan, so the availability the
     * machine reports and the items it actually takes cannot diverge.
     */
    public boolean inputMatches(ItemStack stack) {
        return !stack.isEmpty() && this.ingredient != null && this.ingredient.test(stack)
                && (this.nbt == null || this.nbt.matches(stack.getTag()));
    }

    /** The output stack at its maximum amount, or an empty stack for inputs. */
    public ItemStack outputStack() {
        return this.stack == null ? ItemStack.EMPTY : this.stack.copyWithCount(this.maxAmount);
    }

    /**
     * What JEI should show for an input: the explicit {@code nbt-display} when the file wrote one, otherwise
     * the template's concrete form. {@code null} means "show the plain ingredient".
     */
    @Nullable
    public CompoundTag previewTag() {
        if (this.displayTag != null) {
            return this.displayTag.copy();
        }
        return this.nbt == null ? null : this.nbt.toTag();
    }

    /** The output stack as JEI should show it: {@code nbt-display} overrides the produced tag. */
    public ItemStack previewOutputStack() {
        ItemStack out = outputStack();
        if (!out.isEmpty() && this.displayTag != null) {
            out.setTag(this.displayTag.copy());
        }
        return out;
    }

    // ------------------------------------------------------------------ modifiers

    /** The amount one copy consumes or produces, after this requirement's modifiers. */
    public int modifiedAmount(RecipeModifiers modifiers) {
        double modified = modifiers.apply(this, (double) this.maxAmount, false);
        return (int) Math.max(0.0, Math.floor(modified));
    }

    /** The chance one copy is produced or consumed with, after the chance bucket of its modifiers. */
    public float modifiedChance(RecipeModifiers modifiers) {
        return (float) Math.max(0.0, Math.min(1.0, modifiers.apply(this, (double) this.chance, true)));
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean canSatisfy(HatchCollection ports) {
        return canSatisfy(ports, RecipeModifiers.EMPTY);
    }

    /** {@link #canSatisfy} with modifiers, for the whole {@code parallelism} copies the craft settles. */
    public boolean canSatisfy(HatchCollection ports, RecipeModifiers modifiers) {
        return canSatisfy(ports, modifiers, this.parallelism());
    }

    /** {@link #canSatisfy} for an explicit number of copies. */
    public boolean canSatisfy(HatchCollection ports, RecipeModifiers modifiers, int copies) {
        int perCopy = modifiedAmount(modifiers);
        if (this.ioType() == IOType.INPUT) {
            return this.ingredient != null && perCopy > 0
                    && IngredientIo.count(ports.itemInputs(), this::inputMatches) >= perCopy * copies;
        }
        // Space is checked for the guaranteed maximum, so a craft cannot end when the result would not fit.
        return this.stack != null && IngredientIo.canInsertItems(ports.itemOutputs(), this.stack, perCopy * copies);
    }

    /**
     * The original's two item messages, split by direction ({@code RequirementItem.java:288-292}): a missing
     * input and an output that will not fit are different problems with different fixes, and the original said
     * which one it was.
     *
     * <p>Only asked after {@link #canSatisfy} has answered {@code false}, so the two can never disagree about
     * what happened. The per-copy arithmetic is not repeated here: the message names the requirement, not the
     * shortfall, which is all the original's wording carried.
     */
    @Nullable
    @Override
    public String startFailure(HatchCollection ports) {
        return this.ioType() == IOType.INPUT ? FAILURE_ITEM_INPUT : FAILURE_ITEM_OUTPUT_SPACE;
    }

    @Override
    public int parallelLimit(HatchCollection ports, RecipeModifiers modifiers, int ceiling) {
        if (!isParallelizable()) {
            return 1;
        }
        int perCopy = modifiedAmount(modifiers);
        if (perCopy <= 0) {
            // A modifier reduced the amount to nothing, so this requirement never bounds the craft.
            return ceiling;
        }
        if (this.ioType() == IOType.INPUT) {
            if (this.ingredient == null) {
                return 0;
            }
            return Math.min(ceiling, IngredientIo.count(ports.itemInputs(), this::inputMatches) / perCopy);
        }
        if (this.stack == null) {
            return ceiling;
        }
        return Math.min(ceiling, IngredientIo.insertCapacity(ports.itemOutputs(), this.stack) / perCopy);
    }

    @Override
    public boolean apply(HatchCollection ports, RandomSource random) {
        return apply(ports, random, RecipeModifiers.EMPTY);
    }

    /** {@link #apply} with modifiers, moving {@code parallelism} copies per settlement. */
    public boolean apply(HatchCollection ports, RandomSource random, RecipeModifiers modifiers) {
        int perCopy = modifiedAmount(modifiers);
        int copies = this.parallelism();
        if (this.ioType() == IOType.INPUT) {
            int total = perCopy * copies;
            if (total <= 0) {
                return true;
            }
            // One roll per settlement, like the output side: a miss leaves the inputs untouched and the craft
            // still runs. This is the original's startCrafting gate, not a canStart condition.
            float decidedChance = modifiedChance(modifiers);
            if (decidedChance < 1.0F && random.nextFloat() >= decidedChance) {
                return true;
            }
            return this.ingredient != null && IngredientIo.extract(ports.itemInputs(), this::inputMatches, total);
        }
        if (this.stack == null || perCopy <= 0 || copies <= 0) {
            return true;
        }

        float decidedChance = modifiedChance(modifiers);
        boolean rollChance = decidedChance < 1.0F;
        int total = 0;
        for (int copy = 0; copy < copies; copy++) {
            // The original rolled the chance once for the whole settlement (chance.canWork gated doItemIO), and
            // rolled a random amount per craft. With the default chance of 1.0 there is nothing to roll.
            if (rollChance && random.nextFloat() >= decidedChance) {
                continue;
            }
            total += this.hasAmountRange()
                    ? this.minAmount + random.nextInt(this.maxAmount - this.minAmount + 1)
                    : perCopy;
        }
        if (total <= 0) {
            return true;
        }
        // One pair: a range has already been rolled out above, and the plain case repeats the same count.
        return IngredientIo.insertAll(ports.itemOutputs(),
                List.of(new IngredientIo.StackAmount(this.stack, total)));
    }

    @Override
    public boolean tick(HatchCollection ports, RandomSource random) {
        return true;
    }

    @Override
    public String describe() {
        String what = this.ingredient != null
                ? Arrays.stream(this.ingredient.getItems())
                        .findFirst().map(ItemRequirement::nameOf).orElse("?")
                : nameOf(this.stack);
        String amountText = this.hasAmountRange()
                ? this.minAmount + "-" + this.maxAmount
                : Integer.toString(this.maxAmount);
        String chanceText = this.chance < 1.0F
                ? String.format(Locale.ROOT, " (%.0f%%)", this.chance * 100.0F) : "";
        return amountText + "x " + what + chanceText;
    }

    private static String nameOf(@Nullable ItemStack stack) {
        return stack == null || stack.isEmpty() ? "?" : stack.getHoverName().getString();
    }
}
