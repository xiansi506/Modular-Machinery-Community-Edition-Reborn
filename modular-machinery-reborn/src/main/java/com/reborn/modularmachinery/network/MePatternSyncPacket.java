package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.client.ClientPatternSync;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Provider patterns and a bounded preview. Persistent unlimited storage is never sent to clients. */
public final class MePatternSyncPacket {

    private final BlockPos pos;
    private final List<ItemStack> patterns;
    /** 0.102.0：工作模式序号（0=默认 1=合成锁 2=强化阻挡），随样板列表一起推。 */
    private final int workMode;
    private net.minecraft.nbt.CompoundTag summary = new net.minecraft.nbt.CompoundTag();
    public net.minecraft.nbt.CompoundTag summary() { return summary.copy(); }

    public MePatternSyncPacket(BlockPos pos, List<ItemStack> patterns, int workMode) {
        this.pos = pos;
        this.patterns = List.copyOf(patterns);
        this.workMode = workMode;
    }

    public MePatternSyncPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        int count = buf.readVarInt();
        if (count < 0 || count > 36) throw new IllegalArgumentException("Invalid provider pattern count");
        List<ItemStack> read = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            read.add(buf.readItem());
        }
        this.patterns = read;
        // 0.102.0：格式变更段（客户端/服务端必须同版本的既有规则覆盖它）。
        this.workMode = buf.readVarInt();
        var readSummary = buf.readNbt(); this.summary = readSummary == null ? new net.minecraft.nbt.CompoundTag() : readSummary;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.patterns.size());
        for (ItemStack stack : this.patterns) {
            buf.writeItem(stack);
        }
        buf.writeVarInt(this.workMode);
        buf.writeNbt(this.summary);
    }

    public BlockPos pos() {
        return this.pos;
    }

    public List<ItemStack> patterns() {
        return this.patterns;
    }

    public int workMode() {
        return this.workMode;
    }

    public static void handle(MePatternSyncPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // 与 SelectionSyncPacket 同一条规矩：客户端逻辑经 DistExecutor 进独立客户端类，
        // 公共类永不点名 client 状态。
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPatternSync.handle(message)));
        context.setPacketHandled(true);
    }

    /** 写入后调用：把服务端的整张样板列表（连同工作模式）推给正在看窗口的玩家。 */
    public static void sendTo(ServerPlayer player, BlockPos pos, List<ItemStack> patterns, int workMode) {
        var message = new MePatternSyncPacket(pos, patterns, workMode);
        if (player.containerMenu instanceof com.reborn.modularmachinery.menu.MePatternProviderMenu menu && menu.provider().getBlockPos().equals(pos)) message.summary = menu.bufferSummary();
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }
}
