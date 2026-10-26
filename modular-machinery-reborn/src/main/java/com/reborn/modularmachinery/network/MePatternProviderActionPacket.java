package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.compat.ae2.MePatternProviderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Menu-authorized provider actions: mode, return, input group, fluid container and preview page. */
public final class MePatternProviderActionPacket {

    private final BlockPos pos;
    private final int modeOrdinal;
    private int action;

    public MePatternProviderActionPacket(BlockPos pos, int modeOrdinal) {
        this.pos = pos;
        this.modeOrdinal = modeOrdinal;
    }

    public MePatternProviderActionPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.action = buf.readVarInt();
        this.modeOrdinal = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.action);
        buf.writeVarInt(this.modeOrdinal);
    }

    public static void handle(MePatternProviderActionPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.containerMenu instanceof com.reborn.modularmachinery.menu.MePatternProviderMenu menu)
                    || !menu.provider().getBlockPos().equals(message.pos) || !menu.stillValid(player)) {
                return;
            }
            MePatternProviderBlockEntity provider = menu.provider();
            if (message.action == 0) {
                var modes = MePatternProviderBlockEntity.WorkMode.values();
                if (message.modeOrdinal < 0 || message.modeOrdinal >= modes.length) return;
                provider.setWorkMode(modes[message.modeOrdinal]);
            } else if (message.action == 1) provider.storage().requestReturnAll();
            else if (message.action == 2) provider.storage().setGroup(message.modeOrdinal);
            else if (message.action == 3 && !provider.storage().pending()) {
                // Fluid container is held on the menu cursor; successful transfers replace that exact stack.
                var carried = menu.getCarried();
                if (carried.isEmpty()) return;
                var inventory = new net.minecraftforge.items.wrapper.PlayerMainInvWrapper(player.getInventory());
                var result = net.minecraftforge.fluids.FluidUtil.tryEmptyContainerAndStow(carried, provider.storage().manualFluid, inventory, Integer.MAX_VALUE, player, true);
                if (!result.isSuccess()) result = net.minecraftforge.fluids.FluidUtil.tryFillContainerAndStow(carried, provider.storage().manualFluid, inventory, Integer.MAX_VALUE, player, true);
                if (result.isSuccess()) menu.setCarried(result.getResult());
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("gui.modular_machinery_reborn.mepatternprovider.fluid." + (result.isSuccess() ? "success" : "failed")), true);
            } else if (message.action == 4) menu.setBufferPage(message.modeOrdinal);
            else return;
            // 同步推回（新模式 + 样板列表一张包）。
            MePatternSyncPacket.sendTo(player, message.pos, provider.patternsView(), provider.workMode().ordinal());
        });
        context.setPacketHandled(true);
    }

    public static void sendAction(BlockPos pos, int action, int value) {
        var message = new MePatternProviderActionPacket(pos, value); message.action = action; ModNetwork.CHANNEL.sendToServer(message);
    }
    public static void sendSetMode(BlockPos pos, int modeOrdinal) {
        ModNetwork.CHANNEL.sendToServer(new MePatternProviderActionPacket(pos, modeOrdinal));
    }
}
