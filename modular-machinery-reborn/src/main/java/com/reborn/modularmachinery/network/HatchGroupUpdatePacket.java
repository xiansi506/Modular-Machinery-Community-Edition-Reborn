package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.block.MachineHatchBlockEntity;
import com.reborn.modularmachinery.menu.HatchGroupMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 「把这个仓口的组配置设成这样」——旧版 {@code PktGroupInputConfig} 的对应物。
 *
 * <p>旧版用两个包（一个只带组号、一个只带是否启用），这里合成一个：界面上的两次点击本来就
 * 各自知道另一个值，合成一条消息反而少一次中间状态。服务端像旧版一样只认玩家**当前打开的**
 * 那个窗口（{@code PktGroupInputConfig.java:44-56}），并额外核对方块实体还是同一个、以及这个仓口
 * 种类真能分组。
 */
public final class HatchGroupUpdatePacket {

    private final int groupId;
    private final boolean groupInput;

    public HatchGroupUpdatePacket(int groupId, boolean groupInput) {
        this.groupId = groupId;
        this.groupInput = groupInput;
    }

    public HatchGroupUpdatePacket(FriendlyByteBuf buf) {
        this.groupId = buf.readVarInt();
        this.groupInput = buf.readBoolean();
    }

    public int groupId() {
        return this.groupId;
    }

    public boolean groupInput() {
        return this.groupInput;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(this.groupId);
        buf.writeBoolean(this.groupInput);
    }

    public static void handle(HatchGroupUpdatePacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> apply(context.getSender(), message.groupId, message.groupInput));
        context.setPacketHandled(true);
    }

    private static void apply(Player sender, int groupId, boolean groupInput) {
        if (!(sender instanceof ServerPlayer player)) {
            return;
        }
        if (!(player.containerMenu instanceof HatchGroupMenu menu)) {
            return;
        }
        MachineHatchBlockEntity hatch = menu.hatch();
        if (player.level().getBlockEntity(hatch.getBlockPos()) != hatch
                || !menu.stillValid(player)
                || !hatch.canGroupInput()) {
            return;
        }
        // setGroup 自己会把组号夹到 >= 0，并对不可分组的仓口静默忽略——所以旧版那个
        // 「> Integer.MAX_VALUE 就夹到 MAX_VALUE」的分支在这里不需要单独写。
        hatch.setGroup(groupInput, groupId);
    }
}
