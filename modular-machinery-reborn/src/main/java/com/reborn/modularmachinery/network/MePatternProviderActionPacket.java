package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.compat.ae2.MePatternProviderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C4-II（0.102.0）：供应器工作模式切换（客户端 → 服务端）——旧版
 * {@code PktMEPatternProviderAction}（ENABLE_*_MODE 五个动作）的对应物。
 *
 * <p>本版五个模式只实现三个（BLOCKING / ISOLATION 有据暂缓，见方块实体类注释），
 * 所以载荷是一个模式序号而不是旧版的动作枚举；服务端验证序号在范围内后
 * {@code setWorkMode}（模式切换的状态复位语义都在那边），再把样板列表连同新模式推回
 * 正在看窗口的玩家（同步走 MePatternSyncPacket——0.97.1 实测 sendBlockUpdated 的
 * 数据包 tag 为 null，客户端镜像不依赖那条路）。
 */
public final class MePatternProviderActionPacket {

    private final BlockPos pos;
    private final int modeOrdinal;

    public MePatternProviderActionPacket(BlockPos pos, int modeOrdinal) {
        this.pos = pos;
        this.modeOrdinal = modeOrdinal;
    }

    public MePatternProviderActionPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.modeOrdinal = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
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
            MePatternProviderBlockEntity.WorkMode[] modes = MePatternProviderBlockEntity.WorkMode.values();
            if (message.modeOrdinal < 0 || message.modeOrdinal >= modes.length) {
                return;
            }
            provider.setWorkMode(modes[message.modeOrdinal]);
            // 同步推回（新模式 + 样板列表一张包）。
            MePatternSyncPacket.sendTo(player, message.pos, provider.patternsView(), message.modeOrdinal);
        });
        context.setPacketHandled(true);
    }

    public static void sendSetMode(BlockPos pos, int modeOrdinal) {
        ModNetwork.CHANNEL.sendToServer(new MePatternProviderActionPacket(pos, modeOrdinal));
    }
}
