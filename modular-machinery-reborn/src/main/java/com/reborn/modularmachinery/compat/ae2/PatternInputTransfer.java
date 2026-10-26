package com.reborn.modularmachinery.compat.ae2;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import com.reborn.modularmachinery.machine.HatchCollection;
import com.reborn.modularmachinery.recipe.ResourceTransaction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

/** AE2 false 必须表示本批完全未接收；所有成分先共用事务副本，最后一次提交。 */
public final class PatternInputTransfer {
    private PatternInputTransfer() { }

    @FunctionalInterface
    public interface AdditionalInput {
        boolean push(HatchCollection planned, appeng.api.stacks.AEKey key, long amount);
    }
    private static AdditionalInput additionalInput = (ports, key, amount) -> false;
    public static void setAdditionalInput(AdditionalInput handler) { additionalInput = java.util.Objects.requireNonNull(handler); }

    public static boolean push(HatchCollection ports, KeyCounter[] holders) {
        ResourceTransaction transaction = new ResourceTransaction();
        HatchCollection planned = ports.transactionView(transaction);
        for (KeyCounter holder : holders) {
            for (var entry : holder) {
                long amount = entry.getLongValue();
                if (amount < 0 || amount > Integer.MAX_VALUE) return false;
                int remaining = (int) amount;
                if (remaining == 0) continue;
                if (entry.getKey() instanceof AEItemKey key) {
                    ItemStack stack = key.toStack(remaining);
                    for (IItemHandler input : planned.itemInputs()) {
                        for (int slot = 0; slot < input.getSlots() && remaining > 0; slot++) {
                            remaining = input.insertItem(slot, stack.copyWithCount(remaining), false).getCount();
                        }
                    }
                } else if (entry.getKey() instanceof AEFluidKey key) {
                    FluidStack stack = key.toStack(remaining);
                    for (IFluidHandler input : planned.fluidInputs()) {
                        if (remaining <= 0) break;
                        remaining -= input.fill(new FluidStack(stack, remaining), IFluidHandler.FluidAction.EXECUTE);
                    }
                } else {
                    // The optional chemical bridge receives the SAME transaction as items and fluids.
                    if (!additionalInput.push(planned, entry.getKey(), amount)) return false;
                    remaining = 0;
                }
                if (remaining != 0) return false;
            }
        }
        return transaction.commit();
    }
}
