package com.reborn.modularmachinery.block;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemStackHandler;

/**
 * C4（0.73.0）：ME 物品总线的搬运算法，<b>不依赖 AE2 类型</b>。
 *
 * <p>为什么把它从方块实体里拆出来：AE2 是可选依赖，而离线复现器的 classpath 里没有 AE2
 * （{@code .tmp-m6c-verify/classpath.txt} 里 {@code appliedenergistics2} 零命中）。如果搬运逻辑
 * 只写在 {@code MeItemBusBlockEntity} 里，它就永远只能「编译过」，无法在断言里跑。这里只认
 * {@link Storage} 这个两方法接口，假网络和 AE2 适配器都实现它。
 *
 * <p>算法照旧版 {@code MEItemInputBus} / {@code MEItemOutputBus}：
 * <ul>
 *   <li>输入：配置槽为空时，把内部槽里的东西送回网络（旧版空配置不会主动拉取）；配置槽有物品时，
 *       把内部槽维持成「同一物品、数量等于配置槽数量」，多了送回、少了抽取。槽里是别的物品时，
 *       先送回网络，再抽配置物。</li>
 *   <li>输出：把内部槽整叠往网络里塞，网络收不下的留在槽里。</li>
 * </ul>
 *
 * <p>失败计数与旧版 {@code failureCounter} 同义：失败的槽在下一次「全量检查」之前不再重试，
 * 免得网络刚满时每 tick 都做一次注定失败的模拟。调用者每 100 tick 传一次
 * {@code fullCheck = true}。
 */
public final class MeItemBusLogic {

    /** The two operations a storage must offer; both return the amount actually moved. */
    public interface Storage {
        int insert(ItemStack stack);

        int extract(ItemStack prototype, int amount);
    }

    private MeItemBusLogic() {
    }

    /**
     * Runs one input pass. Returns {@code true} when anything moved, so the caller can mark the block
     * entity dirty only when there is something to save.
     */
    public static boolean tickInput(ItemStackHandler inventory, ItemStackHandler config,
                                    Storage storage, int[] failures, boolean fullCheck) {
        boolean moved = false;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!fullCheck && failures[slot] > 0) {
                continue;
            }
            if (fullCheck) {
                failures[slot] = 0;
            }

            ItemStack configured = config.getStackInSlot(slot);
            ItemStack held = inventory.getStackInSlot(slot);

            if (configured.isEmpty()) {
                if (!held.isEmpty()) {
                    int inserted = insert(storage, held);
                    if (inserted > 0) {
                        shrink(held, inserted);
                        inventory.setStackInSlot(slot, held);
                        moved = true;
                    }
                }
                continue;
            }

            if (!held.isEmpty() && !ItemStack.isSameItemSameTags(held, configured)) {
                int inserted = insert(storage, held);
                if (inserted > 0) {
                    shrink(held, inserted);
                    inventory.setStackInSlot(slot, held);
                    moved = true;
                }
                if (!held.isEmpty()) {
                    failures[slot]++;
                    continue;
                }
            }

            int target = configured.getCount();
            if (held.getCount() > target) {
                ItemStack excess = held.copyWithCount(held.getCount() - target);
                int inserted = insert(storage, excess);
                if (inserted > 0) {
                    shrink(held, inserted);
                    inventory.setStackInSlot(slot, held);
                    moved = true;
                }
                if (inserted < excess.getCount()) {
                    failures[slot]++;
                }
            } else if (held.getCount() < target) {
                int missing = target - held.getCount();
                int extracted = extract(storage, configured.copyWithCount(missing), missing);
                if (extracted > 0) {
                    if (held.isEmpty()) {
                        inventory.setStackInSlot(slot, configured.copyWithCount(extracted));
                    } else {
                        held.grow(extracted);
                        inventory.setStackInSlot(slot, held);
                    }
                    moved = true;
                }
                if (extracted < missing) {
                    failures[slot]++;
                }
            }
        }
        return moved;
    }

    /**
     * Runs one output pass. Returns {@code true} when anything moved.
     */
    public static boolean tickOutput(ItemStackHandler inventory, Storage storage,
                                     int[] failures, boolean fullCheck) {
        boolean moved = false;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!fullCheck && failures[slot] > 0) {
                continue;
            }
            if (fullCheck) {
                failures[slot] = 0;
            }
            ItemStack held = inventory.getStackInSlot(slot);
            if (held.isEmpty()) {
                continue;
            }
            int inserted = insert(storage, held);
            if (inserted > 0) {
                shrink(held, inserted);
                inventory.setStackInSlot(slot, held);
                moved = true;
            }
            if (!held.isEmpty()) {
                failures[slot]++;
            }
        }
        return moved;
    }

    private static int insert(Storage storage, ItemStack stack) {
        return Math.max(0, Math.min(stack.getCount(), storage.insert(stack.copy())));
    }

    private static int extract(Storage storage, ItemStack prototype, int amount) {
        return Math.max(0, Math.min(amount, storage.extract(prototype, amount)));
    }

    private static void shrink(ItemStack stack, int amount) {
        stack.shrink(amount);
    }
}
