package com.reborn.modularmachinery.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * The one place that empties an item inventory into the world.
 *
 * <p>The original spilled a block's inventory from {@code breakBlock}:
 * {@code BlockBus.java:50-63}, {@code BlockUpgradeBus.java:57-70} and {@code BlockController.java:178-191}
 * all walked their {@code IOInventory}, called {@code spawnAsEntity} and cleared the slot. On 1.20.1 there is
 * no {@code breakBlock}; the equivalent moments are {@code onRemove} (the block state really changed, including
 * explosions and replacement) and {@code playerDestroy} (a player broke it). Both funnel through here so the
 * copy-then-clear order is written once.
 *
 * <p>Only items are spilled. The original did not drop the contents of fluid or gas tanks, and this helper
 * therefore takes an {@link ItemStackHandler}, not a block entity: a fluid or energy hatch passes {@code null}
 * and nothing happens.
 */
final class ItemStackDrops {

    private ItemStackDrops() {
    }

    /**
     * Hands every non-empty stack to {@code sink}, then empties its slot.
     *
     * @return the number of stacks handed over
     */
    static int clearInto(ItemStackHandler items, Consumer<ItemStack> sink) {
        int moved = 0;
        for (int slot = 0; slot < items.getSlots(); slot++) {
            ItemStack stack = items.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            // Copy before clearing: the handler may hand out a live reference, and the caller is about to
            // spawn the stack into the world.
            sink.accept(stack.copy());
            items.setStackInSlot(slot, ItemStack.EMPTY);
            moved++;
        }
        return moved;
    }

    /** Pops the inventory at {@code pos}; a no-op on the client, and for blocks without item slots. */
    static void pop(Level level, BlockPos pos, @Nullable ItemStackHandler items) {
        if (items == null || level.isClientSide) {
            return;
        }
        clearInto(items, stack -> Block.popResource(level, pos, stack));
    }
}
