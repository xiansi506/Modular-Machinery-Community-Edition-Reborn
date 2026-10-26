package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.block.MeBusAccess;
import com.reborn.modularmachinery.compat.ae2.MeItemBusBlockEntity;
import com.reborn.modularmachinery.menu.MeItemBusMenu;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * C4-II（0.94.0，照高版 AE2 接口的配置格模型）：在输入总线界面上写一个**配置格**——
 * 物品 ghost / 流体标记 / 清空（客户端 → 服务端）。一格要么物品要么流体，服务端写入时互斥。
 *
 * <p>服务端验证：发件人正开着这台输入总线的界面、槽号 0..15、物品/流体真实存在
 * （物品经注册表 id 重建并夹到自身堆叠上限，防手写包塞超大数量当目标量；流体目标量夹到全池容量）。
 * 写回走 {@link MeBusAccess#setConfigItem}/{@link MeBusAccess#setConfigFluid}，
 * 由实现负责清退避/置脏/方块更新，客户端随 update tag 镜像刷新。
 */
public final class MeFluidConfigPacket {

    /** 该格写入什么。 */
    public enum Mode {
        CLEAR, ITEM, FLUID, GAS
    }

    private final int slot;
    private final Mode mode;
    @Nullable
    private final ResourceLocation id;
    private final int amount;

    public MeFluidConfigPacket(int slot, Mode mode, @Nullable ResourceLocation id, int amount) {
        this.slot = slot;
        this.mode = mode;
        this.id = id;
        this.amount = amount;
    }

    public MeFluidConfigPacket(FriendlyByteBuf buf) {
        this.slot = buf.readByte();
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= Mode.values().length) throw new IllegalArgumentException("Unknown ME config mode " + ordinal);
        this.mode = Mode.values()[ordinal];
        this.id = buf.readBoolean() ? buf.readResourceLocation() : null;
        this.amount = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(this.slot);
        buf.writeByte(this.mode.ordinal());
        buf.writeBoolean(this.id != null);
        if (this.id != null) {
            buf.writeResourceLocation(this.id);
        }
        buf.writeVarInt(this.amount);
    }

    public static void handle(MeFluidConfigPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            AbstractContainerMenu menu = player.containerMenu;
            if (!(menu instanceof MeItemBusMenu busMenu) || !busMenu.isInput()) {
                return;
            }
            if (!busMenu.stillValid(player) || busMenu.hatch().getLevel() != player.level()) return;
            if (!(busMenu.hatch() instanceof MeBusAccess bus)) {
                return;
            }
            if (message.slot < 0 || message.slot >= MeItemBusBlockEntity.CONFIG_GRID) {
                return;
            }
            switch (message.mode) {
                case CLEAR -> bus.setConfigItem(message.slot, null);
                case ITEM -> {
                    if (message.id == null) {
                        return;
                    }
                    ItemStack proto = new ItemStack(BuiltInRegistries.ITEM.get(message.id));
                    if (proto.isEmpty()) {
                        return;
                    }
                    int count = Math.max(1, Math.min(message.amount, proto.getMaxStackSize()));
                    bus.setConfigItem(message.slot, new ItemStack(proto.getItem(), count));
                }
                case FLUID -> {
                    if (message.id == null) {
                        return;
                    }
                    Fluid fluid = BuiltInRegistries.FLUID.get(message.id);
                    if (fluid == Fluids.EMPTY) {
                        return;
                    }
                    int amount = Math.max(1, Math.min(message.amount,
                            com.reborn.modularmachinery.block.MeFluidTanks.SLOTS
                                    * com.reborn.modularmachinery.block.MeFluidTanks.CAPACITY));
                    bus.setConfigFluid(message.slot, fluid, amount);
                }
                case GAS -> {
                    if (message.id == null || bus.gasTanks() == null) return;
                    bus.setConfigGas(message.slot, message.id, Math.max(1, Math.min(message.amount,
                            com.reborn.modularmachinery.block.MeGasStorage.SLOTS
                                    * com.reborn.modularmachinery.block.MeGasStorage.CAPACITY)));
                }
            }
        });
        context.setPacketHandled(true);
    }

    /** 手持物品 → 物品 ghost（数量 = 手持数，右键 = 1）。 */
    public static void sendItem(int slot, ItemStack carried, boolean single) {
        ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(carried.getItem());
        int count = single ? 1 : carried.getCount();
        ModNetwork.CHANNEL.sendToServer(new MeFluidConfigPacket(slot, Mode.ITEM, id, count));
    }

    /** 手持流体容器 → 流体标记（目标量 = 容器量）。 */
    public static void sendFluid(int slot, Fluid fluid, int amount) {
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
        ModNetwork.CHANNEL.sendToServer(new MeFluidConfigPacket(slot, Mode.FLUID, id, amount));
    }

    public static void sendClear(int slot) {
        ModNetwork.CHANNEL.sendToServer(new MeFluidConfigPacket(slot, Mode.CLEAR, null, 0));
    }

    public static void sendGas(int slot, ResourceLocation gas, long amount) {
        int target = (int) Math.max(1, Math.min(amount,
                com.reborn.modularmachinery.block.MeGasStorage.SLOTS * com.reborn.modularmachinery.block.MeGasStorage.CAPACITY));
        ModNetwork.CHANNEL.sendToServer(new MeFluidConfigPacket(slot, Mode.GAS, gas, target));
    }
}
