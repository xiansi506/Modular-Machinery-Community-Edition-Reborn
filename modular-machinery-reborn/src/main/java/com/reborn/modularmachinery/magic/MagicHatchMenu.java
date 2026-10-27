package com.reborn.modularmachinery.magic;

import com.reborn.modularmachinery.menu.ModMenus;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.SlotItemHandler;

public final class MagicHatchMenu extends AbstractContainerMenu {
    public final MagicHatchBlockEntity hatch;
    public MagicHatchMenu(int id, Inventory inventory, MagicHatchBlockEntity hatch) {
        super(ModMenus.MAGIC_HATCH.get(), id); this.hatch = hatch;
        hatch.refreshMenuData();
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row*9 + 9, 8+col*18, 84+row*18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 8+col*18, 142));
        if (hatch.orbs().getSlots() > 0) addSlot(new SlotItemHandler(hatch.orbs(), 0, 80, 49));
        addDataSlots(new ContainerData() {
            public int getCount() { return 24; }
            public int get(int index) { return hatch.menuWord(index); }
            public void set(int index, int value) { hatch.menuWord(index, value); }
        });
    }
    public MagicHatchMenu(int id, Inventory inventory, FriendlyByteBuf buf) { this(id, inventory, (MagicHatchBlockEntity) inventory.player.level().getBlockEntity(buf.readBlockPos())); }
    @Override public void broadcastChanges() { hatch.refreshMenuData(); super.broadcastChanges(); }
    @Override public boolean stillValid(Player player) { return !hatch.isRemoved() && player.level() == hatch.getLevel() && player.distanceToSqr(hatch.getBlockPos().getCenter()) <= 64; }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index); if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), original = stack.copy();
        boolean moved = index >= 36 ? moveItemStackTo(stack, 0, 36, true)
                : slots.size() > 36 && moveItemStackTo(stack, 36, slots.size(), false);
        if (!moved && index < 36) moved = index < 27 ? moveItemStackTo(stack,27,36,false) : moveItemStackTo(stack,0,27,false);
        if (!moved) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, stack); return original;
    }
}
