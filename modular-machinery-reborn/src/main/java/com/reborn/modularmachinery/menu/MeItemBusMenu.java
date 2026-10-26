package com.reborn.modularmachinery.menu;

import com.reborn.modularmachinery.block.HatchKind;
import com.reborn.modularmachinery.block.MachineHatchBlockEntity;
import com.reborn.modularmachinery.block.MeBusAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

import javax.annotation.Nullable;

/**
 * C4（0.73.0）：AE2 ME 物品输入/输出总线的容器。
 *
 * <p>窗口尺寸按旧版：输入 176×204（16 个配置槽 + 16 个内部槽），输出 176×195（36 个内部槽）。
 * 这个类不 import 任何 {@code appeng} 类型；「网络在不在线」通过 {@link MeBusAccess} 取，
 * 再用一个 {@link ContainerData} 值同步给客户端。
 */
public final class MeItemBusMenu extends AbstractContainerMenu {

    public static final int CONFIG_SLOTS = 16;
    public static final int INPUT_INTERNAL_SLOTS = 16;
    public static final int OUTPUT_INTERNAL_SLOTS = 36;
    private static final int PLAYER_SLOTS = 36;

    private final MachineHatchBlockEntity hatch;
    @Nullable
    private final MeBusAccess bus;
    private final boolean input;
    private final int playerInventoryY;

    /** 客户端从 {@link ContainerData} 收到的值；服务端的 get 直接问方块实体。 */
    private int clientOnline;

    private final ContainerData data = new ContainerData() {
        @Override
        public int get(int index) {
            if (index != 0) {
                return 0;
            }
            if (isClientSide()) {
                return MeItemBusMenu.this.clientOnline;
            }
            return MeItemBusMenu.this.bus != null && MeItemBusMenu.this.bus.meOnline() ? 1 : 0;
        }

        @Override
        public void set(int index, int value) {
            if (index == 0) {
                MeItemBusMenu.this.clientOnline = value;
            }
        }

        @Override
        public int getCount() {
            return 1;
        }
    };

    public MeItemBusMenu(int windowId, Inventory inventory, MachineHatchBlockEntity hatch) {
        super(ModMenus.ME_ITEM_BUS.get(), windowId);
        this.hatch = hatch;
        this.bus = hatch instanceof MeBusAccess access ? access : null;
        this.input = hatch.kind() == HatchKind.ME_ITEM_INPUT;
        this.playerInventoryY = this.input ? 123 : 113;

        this.addPlayerSlots(inventory, this.playerInventoryY);

        if (this.input) {
            // 0.94.0（照高版 AE2 接口）：配置格是 **ghost 标记**，不挂真实槽——交互由窗口侧捕获
            // 并发包（MeFluidConfigPacket）。0.94.1 修复：0.94.0 那次替换实际没有落上（脚本未命中
            // 仍报成功），真实槽留在了原地——这就是「标记还是消耗物品/一个物品显示两个」的直接原因
            // （真实槽收进物品 + ghost 渲染叠加）。占位槽 mayPlace/mayPickup 都关，杜绝一切
            // vanilla 路径（点击/拖拽/双击收集）把物品塞进虚空容器。
            for (int i = 0; i < CONFIG_SLOTS; i++) {
                this.addSlot(new net.minecraft.world.inventory.Slot(
                        new net.minecraft.world.SimpleContainer(CONFIG_SLOTS), i,
                        8 + (i % 4) * 18, 35 + (i / 4) * 18) {
                    @Override
                    public boolean mayPlace(net.minecraft.world.item.ItemStack stack) {
                        return false;
                    }

                    @Override
                    public boolean mayPickup(net.minecraft.world.entity.player.Player player) {
                        return false;
                    }
                });
            }
            ItemStackHandler internal = this.hatch.itemHandler();
            if (internal != null) {
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 4; col++) {
                        this.addSlot(new SlotItemHandler(internal, col + row * 4, 98 + col * 18, 35 + row * 18));
                    }
                }
            }
            this.addDataSlots(this.data);
        } else {
            ItemStackHandler internal = this.hatch.itemHandler();
            if (internal != null) {
                for (int row = 0; row < 4; row++) {
                    for (int col = 0; col < 9; col++) {
                        this.addSlot(new SlotItemHandler(internal, col + row * 9, 8 + col * 18, 24 + row * 18));
                    }
                }
            }
            this.addDataSlots(this.data);
        }
    }

    public MeItemBusMenu(int windowId, Inventory inventory, FriendlyByteBuf data) {
        this(windowId, inventory, (MachineHatchBlockEntity) inventory.player.level().getBlockEntity(data.readBlockPos()));
    }

    private void addPlayerSlots(Inventory inventory, int top) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, top + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, 8 + col * 18, top + 58));
        }
    }

    private boolean isClientSide() {
        return this.hatch.getLevel() != null && this.hatch.getLevel().isClientSide;
    }

    /** C4-II（0.93.0）：配置包的验证要用——发件人开着的必须是这台总线的界面。 */
    public MachineHatchBlockEntity hatch() {
        return this.hatch;
    }

    public boolean isInput() {
        return this.input;
    }

    public boolean meOnline() {
        return this.data.get(0) == 1;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack copy = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        copy = stack.copy();

        // 玩家槽永远在前 36 个。
        if (index < PLAYER_SLOTS) {
            int first = this.input ? CONFIG_SLOTS + PLAYER_SLOTS : PLAYER_SLOTS;
            int last = this.input ? first + INPUT_INTERNAL_SLOTS : first + OUTPUT_INTERNAL_SLOTS;
            if (!this.moveItemStackTo(stack, first, last, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!this.moveItemStackTo(stack, 0, PLAYER_SLOTS, true)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    @Override
    public boolean stillValid(Player player) {
        return !this.hatch.isRemoved()
                && player.distanceToSqr(Vec3.atCenterOf(this.hatch.getBlockPos())) <= 64.0;
    }

    @Override
    public boolean clickMenuButton(Player player, int action) {
        if (!this.input || this.bus == null || this.hatch.getLevel() == null
                || this.hatch.getLevel().isClientSide || this.hatch.getLevel() != player.level()
                || !stillValid(player)) return false;
        return MeConfigAmount.apply(this.bus, action);
    }
}
