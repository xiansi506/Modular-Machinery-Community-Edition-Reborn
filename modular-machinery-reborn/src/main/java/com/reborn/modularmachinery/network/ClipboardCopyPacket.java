package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.client.ClientClipboard;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * F2（0.86.0）：把一段文本复制到执行命令的玩家的剪贴板——旧版 {@code PktCopyToClipboard}（服务端 → 客户端，
 * AWT 系统剪贴板）的对应物，{@code /mm-hand} 用它把描述符行送进剪贴板。
 *
 * <p>线上格式照旧版：一个长度前缀加 UTF-8 字节，而不是 {@code FriendlyByteBuf.writeUtf}——后者的
 * 读取端默认上限 32767 字符，而一个带大 NBT 的物品的模板 JSON 完全可能更长，超限会在读包时抛异常。
 * 读端的长度按 {@link #MAX_LENGTH_BYTES} 封顶，道理与 {@link SelectionSyncPacket} 的容量封顶相同：
 * 长度是攻击者可控数据，不能拿它当分配尺寸。
 */
public final class ClipboardCopyPacket {

    /** 读端接受的长度上限：2 MiB，足够任何合理的描述符行，又不至于被手写包当内存炸弹。 */
    private static final int MAX_LENGTH_BYTES = 2 * 1024 * 1024;

    private final String text;

    public ClipboardCopyPacket(String text) {
        this.text = text;
    }

    public ClipboardCopyPacket(FriendlyByteBuf buf) {
        int length = buf.readInt();
        if (length < 0 || length > MAX_LENGTH_BYTES) {
            throw new IllegalStateException("Clipboard message length out of range: " + length);
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        this.text = new String(bytes, StandardCharsets.UTF_8);
    }

    public String text() {
        return this.text;
    }

    public void encode(FriendlyByteBuf buf) {
        byte[] bytes = this.text.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bytes.length);
        buf.writeBytes(bytes);
    }

    public static void handle(ClipboardCopyPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        // 与 SelectionSyncPacket 同一条规矩：客户端逻辑只经 DistExecutor 点名，公共类永远不直接引用客户端状态。
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientClipboard.copy(message.text())));
        context.setPacketHandled(true);
    }

    public static void sendTo(ServerPlayer player, String text) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ClipboardCopyPacket(text));
    }
}
