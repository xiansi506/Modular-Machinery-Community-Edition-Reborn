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

/**
 * C4-II（0.97.2）：样板供应器的样板列表镜像（服务端 → 客户端）——旧版
 * \`PktMEPatternProviderHandlerItems\` 架构的对应物。
 *
 * <p>为什么不用方块实体 update tag：0.97.0/0.97.1 实测证明 \`sendBlockUpdated\` 路径
 * （getUpdatePacket → ClientboundBlockEntityDataPacket）到达客户端的 **tag 为 null**
 * （1.20.1 里 null tag = 「移除方块实体」语义，无条件 load 会 NPE——日志已捕获）。旧版本来就用
 * 专用包给 GUI 供数据，本版照搬：写入后由服务端**主动**把整张样板列表推给正在看窗口的玩家；
 * 镜像存在**菜单**上（窗口关闭即失效，不与 chunk 数据竞争），窗口渲染/交互只读镜像。
 */
public final class MePatternSyncPacket {

    private final BlockPos pos;
    private final List<ItemStack> patterns;
    /** 0.102.0：工作模式序号（0=默认 1=合成锁 2=强化阻挡），随样板列表一起推。 */
    private final int workMode;

    public MePatternSyncPacket(BlockPos pos, List<ItemStack> patterns, int workMode) {
        this.pos = pos;
        this.patterns = List.copyOf(patterns);
        this.workMode = workMode;
    }

    public MePatternSyncPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        int count = buf.readVarInt();
        List<ItemStack> read = new ArrayList<>(Math.min(count, 64));
        for (int i = 0; i < count; i++) {
            read.add(buf.readItem());
        }
        this.patterns = read;
        // 0.102.0：格式变更段（客户端/服务端必须同版本的既有规则覆盖它）。
        this.workMode = buf.readVarInt();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
        buf.writeVarInt(this.patterns.size());
        for (ItemStack stack : this.patterns) {
            buf.writeItem(stack);
        }
        buf.writeVarInt(this.workMode);
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
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new MePatternSyncPacket(pos, patterns, workMode));
    }
}
