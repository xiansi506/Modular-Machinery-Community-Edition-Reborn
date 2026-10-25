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
                    // 未支持的 key 必须拒绝整批，不能报告成功并丢掉它。
                    return false;
                }
                if (remaining != 0) return false;
            }
        }
        return transaction.commit();
    }
}
