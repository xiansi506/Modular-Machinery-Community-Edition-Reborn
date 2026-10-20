package com.reborn.modularmachinery.recipe;

import com.reborn.modularmachinery.machine.HatchCollection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The original's {@code RequirementCatalyst} ({@code hellfirepvp.modularmachinery.common.crafting
 * .requirement.RequirementCatalyst}), re-expressed as a first-class requirement type.
 *
 * <p><b>What a catalyst is.</b> An <i>optional</i> start-phase input that is not consumed per parallel copy:
 * when one group is present the craft pays it once and folds its {@link RecipeModifier}s into the settlement;
 * when it is absent the craft still starts and simply runs without those modifiers. That is what the original
 * did: its {@code canStartCrafting} returned {@code CraftCheck.skipComponent()} when the group was absent, and
 * {@code ResultType.INVALID_SKIP} was <b>not</b> turned into an error by
 * {@code RecipeCraftingContext.CraftingCheckResult} — the result was still a success, with no modifier added.
 * Its three constructors also called {@code setParallelizeUnaffected(true)}, and its
 * {@code addModifierToContext} fed the modifier list into the context only on the present branch.
 *
 * <h2>Why one copy and one modifier application</h2>
 *
 * <p>The original's {@code setParallelizeUnaffected(true)} did more than set a flag: in
 * {@code ComponentRequirement.MultiCompParallelizable#setParallelizeUnaffected} it also forced
 * {@code parallelism = 1}, and {@code setParallelism} ignored every later value for such a requirement
 * ({@code _mmce-src/.../common/crafting/helper/ComponentRequirement.java:471-489}). Two consequences follow
 * directly from that field:
 *
 * <ul>
 *   <li>{@code RequirementIngredientArray#startCrafting} → {@code doItemIO(..., parallelism, ...)} consumed
 *       <b>one</b> catalyst group, never {@code amount * P}.</li>
 *   <li>{@code RequirementCatalyst#addModifierToContext} took its {@code parallelism > 1} branch only when the
 *       field was above one — unreachable for an unaffected requirement — so the modifiers were added
 *       <b>once</b>, without a {@code × P} step.</li>
 * </ul>
 *
 * <p>This port therefore consumes one group and applies each modifier once, which is the original's actual
 * behaviour rather than a re-interpretation of it. A pack author who wants more catalyst per craft writes a
 * larger {@code amount}; a modifier that should scale with parallelism belongs on a normal requirement.
 */
public final class CatalystRequirement extends MachineRequirement {

    private final List<IngredientArrayEntry> entries;
    private final float chance;
    @Nullable
    private final NbtPredicate nbt;
    private final List<RecipeModifier> modifiers;
    private final List<String> tooltips;

    CatalystRequirement(List<IngredientArrayEntry> entries, float chance, @Nullable NbtPredicate nbt,
                        List<RecipeModifier> modifiers, List<String> tooltips) {
        super(IOType.INPUT, false);
        this.entries = List.copyOf(entries);
        this.chance = chance;
        this.nbt = nbt;
        this.modifiers = List.copyOf(modifiers);
        this.tooltips = List.copyOf(tooltips);
        // The original's every constructor called this; it is what keeps the group out of the recipe's
        // parallelism minimum and makes it work on one copy.
        setParallelizeUnaffected(true);
    }

    // ------------------------------------------------------------------ the data, for tests and the network

    public int entryCount() {
        return this.entries.size();
    }

    public int entryAmount(int index) {
        return this.entries.get(index).amount();
    }

    public String entryItemId(int index) {
        return this.entries.get(index).itemId();
    }

    public boolean entryIsTag(int index) {
        return this.entries.get(index).isTag();
    }

    public List<ItemStack> entryStacks(int index) {
        return List.of(this.entries.get(index).ingredient().getItems());
    }

    /**
     * Every alternative of the whole group, in payment order, each sized to its entry's amount and carrying the
     * preview NBT — one JEI cell's ingredient list. Display only: matching and extraction still go through
     * {@link IngredientArrayEntry}, never through these stacks.
     */
    public List<ItemStack> entryStacksFlat() {
        CompoundTag tag = this.nbt == null ? null : this.nbt.toTag();
        List<ItemStack> out = new ArrayList<>();
        for (int index = 0; index < this.entries.size(); index++) {
            int amount = entryAmount(index);
            for (ItemStack stack : entryStacks(index)) {
                ItemStack copy = stack.copy();
                copy.setCount(amount);
                if (tag != null) {
                    copy.setTag(tag.copy());
                }
                out.add(copy);
            }
        }
        return out;
    }

    /** The parsed ingredient of one entry; package-private because {@link Ingredient} is network/wire detail. */
    Ingredient entryIngredient(int index) {
        return this.entries.get(index).ingredient();
    }

    public float chance() {
        return this.chance;
    }

    @Nullable
    public NbtPredicate nbt() {
        return this.nbt;
    }

    public List<RecipeModifier> modifiers() {
        return this.modifiers;
    }

    public List<String> tooltips() {
        return this.tooltips;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public RecipeModifier.Target modifierTarget() {
        return RecipeModifier.Target.ITEM;
    }

    @Override
    public RequirementType type() {
        return Holder.TYPE;
    }

    /**
     * Whether the ports hold the group's first payable entry, <b>one copy</b> of it.
     *
     * <p>The original's {@code canStartCrafting} delegated to {@code RequirementIngredientArray}, which was
     * asked for one copy here (the recipe context called it with {@code ResultChance.GUARANTEED} and the
     * component's own parallelism was 1 after {@code setParallelizeUnaffected}). {@code chance} is deliberately
     * ignored: the original rolled it only in {@code startCrafting}, so a failed roll pays nothing while the
     * craft still runs.
     */
    public boolean hasCatalyst(HatchCollection ports) {
        List<IItemHandler> handlers = ports.itemInputs();
        for (IngredientArrayEntry entry : this.entries) {
            if (entry.availableIn(handlers, this.nbt) >= entry.amount()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A catalyst never blocks a start. The original's absent-catalyst branch returned
     * {@code CraftCheck.skipComponent()}, and the recipe context counted only {@code isSuccess()} results while
     * silently dropping {@code INVALID_SKIP}; the craft therefore ran without a catalyst and without its
     * modifiers. {@link #hasCatalyst} is what the recipe asks when it decides whether to fold them in.
     */
    @Override
    public boolean canSatisfy(HatchCollection ports) {
        return true;
    }

    @Override
    public boolean apply(HatchCollection ports, RandomSource random) {
        if (!hasCatalyst(ports)) {
            // Optional input: absent means "run without the catalyst", not "refuse the craft".
            return true;
        }
        if (this.chance < 1.0F && random.nextFloat() >= this.chance) {
            return true;
        }
        List<IItemHandler> handlers = ports.itemInputs();
        for (IngredientArrayEntry entry : this.entries) {
            int amount = entry.amount();
            if (entry.availableIn(handlers, this.nbt) < amount) {
                continue;
            }
            if (!IngredientIo.extract(handlers, stack -> entry.matches(stack, this.nbt), amount)) {
                // A race between the check and the extraction. Report it instead of throwing into the tick
                // loop; the caller records a normal start failure.
                return false;
            }
            return true;
        }
        return true;
    }

    @Override
    public String describe() {
        List<String> ids = new ArrayList<>(this.entries.size());
        for (IngredientArrayEntry entry : this.entries) {
            ids.add(entry.amount() + "x " + entry.itemId());
        }
        return "catalyst " + ids + (this.modifiers.isEmpty() ? "" : " (+" + this.modifiers.size() + " modifiers)");
    }

    /** 见 {@code ItemRequirement.Holder}：避开类初始化顺序。 */
    private static final class Holder {
        private static final RequirementType TYPE = RequirementTypes.CATALYST;
    }
}