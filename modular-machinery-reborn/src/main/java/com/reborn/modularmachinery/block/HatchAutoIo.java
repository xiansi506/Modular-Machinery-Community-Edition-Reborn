package com.reborn.modularmachinery.block;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

/**
 * 仓口自动与相邻容器交换（A1，0.63.9）。
 *
 * <p>旧版三处：{@code TileItemInputBus.java:50-130} 从相邻容器抽物品，
 * {@code TileItemOutputBus.java:50-139} 往相邻容器推物品，
 * {@code TileEnergyOutputHatch.java:68-200} 每 tick 向相邻 FE 目标推能量。
 * 它们共用 {@code TileItemBus.java:39-63} 的 5–60 tick 自适应节流：
 * 成功次数越多，下一次尝试越早；失败则回到 60 tick。
 *
 * <p>本类只放**不需要世界**的那一半：容器之间的搬运、能量推送和节流算术。
 * 邻居查找、ticker 接线和「哪些仓要跳过」留在 {@link MachineHatchBlockEntity}，
 * 这样离线复现器可以只用 {@code IItemHandler} / {@code IEnergyStorage} 驱动本类。
 */
final class HatchAutoIo {

    /** 旧版 {@code TileItemBus.minWorkDelay}。 */
    static final int MIN_WORK_DELAY = 5;
    /** 旧版 {@code TileItemBus.maxWorkDelay}。 */
    static final int MAX_WORK_DELAY = 60;
    /** 旧版 {@code (maxWorkDelay - minWorkDelay) / 5}。 */
    private static final int MAX_SUCCESS_COUNTER = (MAX_WORK_DELAY - MIN_WORK_DELAY) / 5;

    private HatchAutoIo() {
    }

    /**
     * 旧版 {@code TileItemBus.canWork} 的直译。
     *
     * <p>{@code ticksExisted} 从 0 起算：旧版 {@code TileEntityRestrictedTick.update()}
     * 先执行 {@code doRestrictedTick()}，再把计数加一。
     */
    static boolean shouldWork(int ticksExisted, int successCounter, boolean inventoryChanged) {
        if (inventoryChanged) {
            return true;
        }
        if (successCounter <= 0) {
            return ticksExisted % MAX_WORK_DELAY == 0;
        }
        int workDelay = Math.max(MIN_WORK_DELAY, MAX_WORK_DELAY - successCounter * 5);
        return ticksExisted % workDelay == 0;
    }

    static int onSuccess(int successCounter) {
        return Math.min(MAX_SUCCESS_COUNTER, successCounter + 1);
    }

    static int onFailure(int successCounter) {
        return Math.max(0, successCounter - 1);
    }

    /**
     * 把相邻容器里的物品抽进自己的槽。
     *
     * <p>照旧版：每个外部槽先看每个内部槽；空内部槽接整次可抽的量，
     * 同类内部槽只补到上限。一个外部槽抽空就换下一个。
     *
     * @return 是否真的移动了物品
     */
    static boolean pull(IItemHandler external, ItemStackHandler internal) {
        boolean moved = false;
        for (int externalSlot = 0; externalSlot < external.getSlots(); externalSlot++) {
            ItemStack externalStack = external.getStackInSlot(externalSlot);
            if (externalStack.isEmpty()) {
                continue;
            }
            for (int internalSlot = 0; internalSlot < internal.getSlots(); internalSlot++) {
                ItemStack internalStack = internal.getStackInSlot(internalSlot);
                int maxCanExtract = Math.min(externalStack.getCount(), externalStack.getMaxStackSize());
                if (internalStack.isEmpty()) {
                    ItemStack extracted = external.extractItem(externalSlot, maxCanExtract, false);
                    if (extracted.isEmpty()) {
                        continue;
                    }
                    internal.setStackInSlot(internalSlot, extracted);
                    moved = true;
                    externalStack = external.getStackInSlot(externalSlot);
                    if (externalStack.isEmpty()) {
                        break;
                    }
                    continue;
                }
                int limit = Math.min(internalStack.getMaxStackSize(), internal.getSlotLimit(internalSlot));
                if (internalStack.getCount() >= limit || !ItemStack.isSameItemSameTags(internalStack, externalStack)) {
                    continue;
                }
                int extractAmount = Math.min(limit - internalStack.getCount(), maxCanExtract);
                if (extractAmount <= 0) {
                    continue;
                }
                ItemStack extracted = external.extractItem(externalSlot, extractAmount, false);
                if (extracted.isEmpty()) {
                    continue;
                }
                ItemStack merged = internalStack.copy();
                merged.grow(extracted.getCount());
                internal.setStackInSlot(internalSlot, merged);
                moved = true;
                externalStack = external.getStackInSlot(externalSlot);
                if (externalStack.isEmpty()) {
                    break;
                }
            }
        }
        return moved;
    }

    /**
     * 把自己的物品推到相邻容器。
     *
     * <p>照旧版：外部槽满就跳过；空外部槽或同类物品才插。外部槽已经有别的物品时，
     * 内部槽原样留着。目标一点都插不进去时不再继续试（旧版只对空槽做了这层保护，
     * 这里两类槽都做，避免目标拒收被记成一次成功）。
     *
     * @return 是否真的移动了物品
     */
    static boolean push(ItemStackHandler internal, IItemHandler external) {
        boolean moved = false;
        for (int externalSlot = 0; externalSlot < external.getSlots(); externalSlot++) {
            ItemStack externalStack = external.getStackInSlot(externalSlot);
            if (!externalStack.isEmpty() && externalStack.getCount() >= external.getSlotLimit(externalSlot)) {
                continue;
            }
            for (int internalSlot = 0; internalSlot < internal.getSlots(); internalSlot++) {
                ItemStack internalStack = internal.getStackInSlot(internalSlot);
                if (internalStack.isEmpty()) {
                    continue;
                }
                externalStack = external.getStackInSlot(externalSlot);
                if (!externalStack.isEmpty()) {
                    if (!ItemStack.isSameItemSameTags(externalStack, internalStack)) {
                        continue;
                    }
                    if (externalStack.getCount() >= external.getSlotLimit(externalSlot)) {
                        break;
                    }
                }
                int before = internalStack.getCount();
                ItemStack remainder = external.insertItem(externalSlot, internalStack, false);
                int inserted = before - remainder.getCount();
                if (inserted <= 0) {
                    break;
                }
                internal.setStackInSlot(internalSlot, remainder);
                moved = true;
                if (remainder.isEmpty()) {
                    break;
                }
            }
        }
        return moved;
    }

    /**
     * 从一个能量缓冲向相邻目标推送，最多 {@code maxTransfer}。
     *
     * <p>顺序与旧版不同：旧版先让目标真收、再扣自己；这里先模拟目标能收多少、再扣自己、
     * 最后真收，目标少收的差额退回。差别只在目标行为异常时可见——但那种情况下旧版会凭空
     * 少掉能量，而能量消失是玩家看得见的损失。
     */
    static int pushEnergy(EnergyStorage source, IEnergyStorage target, int maxTransfer) {
        if (target == null || !target.canReceive() || maxTransfer <= 0) {
            return 0;
        }
        int available = Math.min(maxTransfer, source.getEnergyStored());
        if (available <= 0) {
            return 0;
        }
        int accepted = target.receiveEnergy(available, true);
        if (accepted <= 0) {
            return 0;
        }
        int extracted = source.extractEnergy(accepted, false);
        if (extracted <= 0) {
            return 0;
        }
        int moved = target.receiveEnergy(extracted, false);
        if (moved < extracted) {
            source.receiveEnergy(extracted - moved, false);
        }
        return moved;
    }
}
