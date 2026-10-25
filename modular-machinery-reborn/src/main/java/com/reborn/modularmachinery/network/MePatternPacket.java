package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.compat.ae2.MePatternProviderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * C4-II（0.96.0）：样板供应器样板格的放入/取出（客户端 → 服务端）。
 *
 * <p>服务端从玩家当前有效菜单取得供应器，验证坐标、距离和 36 格范围。
 * 放入/取出真实移动一个编码样板，手持栈以服务端菜单为准。
 * 写回后同步所有正在查看的菜单。
 */
public final class MePatternPacket {

    private final BlockPos pos;
    private final int slot;
    @Nullable
    private final ItemStack pattern;

    public MePatternPacket(BlockPos pos, int slot, @Nullable ItemStack pattern) {
        this.pos = pos;
        this.slot = slot;
        this.pattern = pattern;
    }

    public MePatternPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.slot = buf.readByte();
        this.pattern = buf.readBoolean() ? buf.readItem() : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeByte(this.slot);
        buf.writeBoolean(this.pattern != null);
        if (this.pattern != null) {
            buf.writeItem(this.pattern);
        }
    }

    public static void handle(MePatternPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.info("[MM-PP] packet received: pos={} slot={} pattern={}", message.pos, message.slot, message.pattern);
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.containerMenu instanceof com.reborn.modularmachinery.menu.MePatternProviderMenu menu)
                    || !menu.provider().getBlockPos().equals(message.pos) || !menu.stillValid(player)) {
                return;
            }
            MePatternProviderBlockEntity provider = menu.provider();
            if (message.slot < 0 || message.slot >= MePatternProviderBlockEntity.PATTERNS) {
                return;
            }
            // 0.97.3（所有者反馈：样板要**真实放入**，不是标记）：照旧版受限槽的真实槽语义——
            // 左键放入 = 手上的样板物理进格（原格内容换回手上）；右键空手 = 取出到手上。
            // carried 以服务端菜单为准（packet 里的副本只作参考，不信任）。
            ItemStack carried = player.containerMenu.getCarried();
            ItemStack previous = provider.getPatternAt(message.slot);
            if (message.pattern == null) {
                // 取出：格里有样板才动作，放到手上
                if (!previous.isEmpty() && carried.isEmpty()) {
                    provider.setPattern(message.slot, ItemStack.EMPTY);
                    player.containerMenu.setCarried(previous);
                    player.containerMenu.broadcastChanges();
                }
            } else {
                // 放入：只收编码样板（旧版 SlotRestrictedInput.ENCODED_PATTERN 的限制）
                if (carried.isEmpty()
                        || !appeng.crafting.pattern.AEPatternDecoder.INSTANCE.isEncodedPattern(carried)) {
                    com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.info("[MM-PP] REJECTED: carried is not an encoded pattern: {}", carried);
                    return;
                }
                if (!previous.isEmpty() && carried.getCount() != 1) return;
                provider.setPattern(message.slot, carried.copyWithCount(1));
                ItemStack remaining = carried.copy();
                remaining.shrink(1);
                player.containerMenu.setCarried(previous.isEmpty() ? remaining : previous);
                player.containerMenu.broadcastChanges();
            }
            // C4-II（0.97.2）：写入后把整张样板列表推回正在看窗口的玩家（旧版专用同步包架构）。
            // 0.102.0：包里带工作模式序号。
            MePatternSyncPacket.sendTo(player, message.pos, provider.patternsView(),
                    provider.workMode().ordinal());
        });
        context.setPacketHandled(true);
    }

    /** 放入一个手上的编码样板。 */
    public static void sendSet(BlockPos pos, int slot, ItemStack carried) {
        ModNetwork.CHANNEL.sendToServer(new MePatternPacket(pos, slot, carried));
    }

    /** 取出/清除。 */
    public static void sendClear(BlockPos pos, int slot) {
        ModNetwork.CHANNEL.sendToServer(new MePatternPacket(pos, slot, null));
    }
}
