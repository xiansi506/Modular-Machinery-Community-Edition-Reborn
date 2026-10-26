package com.reborn.modularmachinery.compat.mekanism;

import com.reborn.modularmachinery.block.MeGasStorage;
import com.reborn.modularmachinery.recipe.ResourceTransaction;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.gas.Gas;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasHandler;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;

/** Nine gas tanks, independent of the bus's fluid pool; shared by pipes, recipes and ME transfers. */
public final class MeGasTanks implements MeGasStorage, IGasHandler, ResourceTransaction.OpaquePort {
    private final GasStack[] tanks = new GasStack[SLOTS];
    private final Gas[] marks = new Gas[CONFIG_SLOTS];
    private final int[] amounts = new int[CONFIG_SLOTS];
    private final Runnable changed;
    public MeGasTanks(Runnable changed) {
        this.changed = changed;
        Arrays.fill(tanks, GasStack.EMPTY);
    }
    @Nullable public static Gas resolve(@Nullable ResourceLocation id) {
        Gas gas = id == null ? null : MekanismAPI.gasRegistry().getValue(id);
        return gas == null || gas.isEmptyType() ? null : gas;
    }
    private static Entry entry(GasStack stack) {
        return new Entry(stack.getTypeRegistryName(), stack.getAmount(), stack.getTextComponent(), stack.getType().getTint());
    }
    @Override public Entry contents(int tank) {
        return tank < 0 || tank >= SLOTS || tanks[tank].isEmpty() ? null : entry(tanks[tank]);
    }
    @Override public Entry mark(int slot) {
        return slot < 0 || slot >= CONFIG_SLOTS || marks[slot] == null ? null : entry(new GasStack(marks[slot], amounts[slot]));
    }
    @Override public boolean setMark(int slot, @Nullable ResourceLocation id, int amount) {
        if (slot < 0 || slot >= CONFIG_SLOTS) return false;
        Gas gas = resolve(id);
        if (id != null && gas == null) return false;
        marks[slot] = gas;
        amounts[slot] = gas == null ? 0 : Math.max(1, Math.min(amount, SLOTS * CAPACITY));
        return true;
    }
    @Override public Entry containedGas(ItemStack stack) {
        var capability = mekanism.common.capabilities.Capabilities.GAS_HANDLER;
        if (stack.isEmpty() || capability == null) return null;
        IGasHandler handler = stack.getCapability(capability).orElse(null);
        if (handler != null) for (int i = 0; i < handler.getTanks(); i++) {
            GasStack gas = handler.getChemicalInTank(i);
            if (!gas.isEmpty()) return entry(gas);
        }
        return null;
    }
    @Override public Object handler() { return this; }
    @Override public int getTanks() { return SLOTS; }
    @Override public GasStack getChemicalInTank(int tank) {
        return tank < 0 || tank >= SLOTS ? GasStack.EMPTY : tanks[tank].copy();
    }
    @Override public void setChemicalInTank(int tank, GasStack stack) {
        if (tank < 0 || tank >= SLOTS) return;
        tanks[tank] = stack.isEmpty() ? GasStack.EMPTY : new GasStack(stack, Math.min(CAPACITY, stack.getAmount()));
        changed.run();
    }
    @Override public long getTankCapacity(int tank) { return tank < 0 || tank >= SLOTS ? 0 : CAPACITY; }
    @Override public boolean isValid(int tank, GasStack stack) { return tank >= 0 && tank < SLOTS && !stack.isEmpty(); }
    @Override public GasStack insertChemical(int tank, GasStack stack, Action action) {
        if (!isValid(tank, stack)) return stack.copy();
        GasStack stored = tanks[tank];
        if (!stored.isEmpty() && !stored.isTypeEqual(stack)) return stack.copy();
        long moved = Math.min(CAPACITY - stored.getAmount(), stack.getAmount());
        if (moved > 0 && action.execute()) {
            tanks[tank] = new GasStack(stack, stored.getAmount() + moved);
            changed.run();
        }
        return moved == stack.getAmount() ? GasStack.EMPTY : new GasStack(stack, stack.getAmount() - moved);
    }
    /** Fill matching tanks before empty ones, so alternating gases do not fragment the pool. */
    @Override public GasStack insertChemical(GasStack stack, Action action) {
        GasStack remaining = stack.copy();
        for (int pass = 0; pass < 2; pass++) for (int i = 0; i < SLOTS && !remaining.isEmpty(); i++) {
            if ((pass == 0) == !tanks[i].isEmpty()) remaining = insertChemical(i, remaining, action);
        }
        return remaining;
    }
    @Override public GasStack extractChemical(int tank, long amount, Action action) {
        if (tank < 0 || tank >= SLOTS || amount <= 0 || tanks[tank].isEmpty()) return GasStack.EMPTY;
        GasStack stored = tanks[tank];
        long moved = Math.min(amount, stored.getAmount());
        GasStack result = new GasStack(stored, moved);
        if (action.execute()) {
            tanks[tank] = moved == stored.getAmount() ? GasStack.EMPTY : new GasStack(stored, stored.getAmount() - moved);
            changed.run();
        }
        return result;
    }
    private long held(Gas gas) {
        long total = 0;
        for (GasStack stack : tanks) if (!stack.isEmpty() && stack.getType() == gas) total += stack.getAmount();
        return total;
    }
    private long returnGas(Gas gas, long amount, Network network) {
        long accepted = Math.max(0, Math.min(amount, network.insert(gas.getRegistryName(), amount)));
        if (accepted > 0) extractChemical(new GasStack(gas, accepted), Action.EXECUTE);
        return accepted;
    }
    @Override public boolean tick(boolean input, Network network) {
        Map<Gas, Long> targets = new LinkedHashMap<>();
        if (input) for (int i = 0; i < CONFIG_SLOTS; i++) if (marks[i] != null) {
            targets.merge(marks[i], (long) amounts[i], (a, b) -> Math.min(SLOTS * CAPACITY, a + b));
        }
        boolean moved = false;
        Map<Gas, Long> existing = new LinkedHashMap<>();
        for (GasStack stack : tanks) if (!stack.isEmpty()) existing.merge(stack.getType(), stack.getAmount(), Long::sum);
        // Return unmarked/excess gas before requesting new types, freeing shared space first.
        for (var stored : existing.entrySet()) {
            long excess = stored.getValue() - targets.getOrDefault(stored.getKey(), 0L);
            if (excess > 0) moved |= returnGas(stored.getKey(), excess, network) > 0;
        }
        for (var target : targets.entrySet()) {
            long missing = target.getValue() - held(target.getKey());
            if (missing <= 0) continue;
            GasStack prototype = new GasStack(target.getKey(), missing);
            long capacity = missing - insertChemical(prototype, Action.SIMULATE).getAmount();
            if (capacity <= 0) continue;
            long extracted = Math.max(0, Math.min(capacity, network.extract(target.getKey().getRegistryName(), capacity)));
            if (extracted > 0) {
                insertChemical(new GasStack(target.getKey(), extracted), Action.EXECUTE);
                moved = true;
            }
        }
        return moved;
    }
    @Override public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag contents = new ListTag();
        for (GasStack stack : tanks) contents.add(stack.isEmpty() ? new CompoundTag() : stack.write(new CompoundTag()));
        tag.put("tanks", contents);
        ListTag configured = new ListTag();
        for (int i = 0; i < CONFIG_SLOTS; i++) {
            CompoundTag value = new CompoundTag();
            if (marks[i] != null) { value.putString("gas", marks[i].getRegistryName().toString()); value.putInt("amount", amounts[i]); }
            configured.add(value);
        }
        tag.put("marks", configured);
        return tag;
    }
    @Override public void load(CompoundTag tag) {
        Arrays.fill(tanks, GasStack.EMPTY); Arrays.fill(marks, null); Arrays.fill(amounts, 0);
        ListTag contents = tag.getList("tanks", Tag.TAG_COMPOUND);
        for (int i = 0; i < SLOTS && i < contents.size(); i++) {
            GasStack stack = GasStack.readFromNBT(contents.getCompound(i));
            if (!stack.isEmpty()) tanks[i] = new GasStack(stack, Math.min(CAPACITY, stack.getAmount()));
        }
        ListTag configured = tag.getList("marks", Tag.TAG_COMPOUND);
        for (int i = 0; i < CONFIG_SLOTS && i < configured.size(); i++) {
            CompoundTag mark = configured.getCompound(i);
            setMark(i, ResourceLocation.tryParse(mark.getString("gas")), mark.getInt("amount"));
        }
    }
    private GasStack[] snapshot() { return Arrays.stream(tanks).map(GasStack::copy).toArray(GasStack[]::new); }
    private void restore(GasStack[] values) { for (int i = 0; i < SLOTS; i++) tanks[i] = values[i].copy(); changed.run(); }
    private boolean unchanged(GasStack[] values) {
        for (int i = 0; i < SLOTS; i++) {
            if (values[i].getAmount() != tanks[i].getAmount()
                    || (!values[i].isEmpty() && !values[i].isTypeEqual(tanks[i]))) return false;
        }
        return true;
    }
    @Override public Object transactionView(ResourceTransaction transaction) {
        GasStack[] before = snapshot();
        MeGasTanks shadow = new MeGasTanks(() -> {});
        shadow.restore(before);
        transaction.enlist(() -> unchanged(before), () -> restore(before));
        transaction.record(() -> { restore(shadow.snapshot()); return true; });
        return shadow;
    }
}
