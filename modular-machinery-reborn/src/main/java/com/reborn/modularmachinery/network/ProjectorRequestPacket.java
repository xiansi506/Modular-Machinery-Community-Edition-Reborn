package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.machine.BlockMatcher;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachinePattern;
import com.reborn.modularmachinery.machine.MachineRegistry;
import com.reborn.modularmachinery.machine.MachineStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * C6 第一片（0.104.0）：投影仪的虚影结构请求（客户端 → 服务端）。
 *
 * <p><b>为什么这个数据必须走网络</b>：虚影要按控制器<strong>定格</strong>下来的结构画
 * （基座 + 各动态段的实际节数 + 整体按朝向旋转），而 {@code rotationSteps} 与
 * {@code dynamicPatterns} 是服务端结构检查的运行时字段——不在方块实体的存档/同步 tag 里，
 * 客户端无从推导。结构组装（{@link MachineStructures#ghostStructure}）因此整个放在服务端做，
 * 客户端只收编好码的候选描述符（{@link BlockMatcher#descriptor()} ↔ {@link BlockMatcher#parse(String)}
 * 无损往返），见 {@link ProjectorResponsePacket}。
 *
 * <p>旧版对照：{@code BlockArrayPreviewRenderHelper} 是纯客户端的（GUI 里点放置按钮、
 * 直接读 {@code DynamicMachine} 注册表——1.12.2 的机器定义全在客户端）。本版机器定义
 * 与运行时结构都在服务端侧（直推架构 + 定义同步包的世界），所以旧版那段客户端自由
 * 在这里换成一个请求/应答对。
 */
public final class ProjectorRequestPacket {

    /** 服务端侧的防滥用上限：超出控制器附近范围的请求直接丢弃。 */
    private static final double MAX_REQUEST_RANGE_SQUARED = 64.0D * 64.0D;
    private static final Map<ServerPlayer, Long> LAST_REQUEST = new java.util.WeakHashMap<>();

    private final BlockPos pos;

    public ProjectorRequestPacket(BlockPos pos) {
        this.pos = pos;
    }

    public ProjectorRequestPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(this.pos);
    }

    public static void handle(ProjectorRequestPacket message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (player.blockPosition().distSqr(message.pos) > MAX_REQUEST_RANGE_SQUARED) {
                return;
            }
            if (!(player.getMainHandItem().getItem() instanceof com.reborn.modularmachinery.item.MachineProjectorItem)
                    && !(player.getOffhandItem().getItem() instanceof com.reborn.modularmachinery.item.MachineProjectorItem)) return;
            if (!player.level().hasChunkAt(message.pos)) return;
            long now = player.level().getGameTime();
            Long previous = LAST_REQUEST.put(player, now);
            if (previous != null && now >= previous && now - previous < 5) return;
            if (!(player.level().getBlockEntity(message.pos) instanceof MachineControllerBlockEntity controller)) {
                return;
            }
            ResourceLocation machineId = controller.previewMachineId();
            if (machineId == null) {
                return;
            }
            MachineDefinition machine = MachineRegistry.byId(machineId).orElse(null);
            if (machine == null) {
                return;
            }
            MachinePattern structure = MachineStructures.ghostStructure(controller, machine);
            Map<BlockPos, List<String>> descriptors = new LinkedHashMap<>();
            for (Map.Entry<BlockPos, List<BlockMatcher>> entry : structure.positions().entrySet()) {
                List<String> raw = new ArrayList<>(entry.getValue().size());
                for (BlockMatcher matcher : entry.getValue()) {
                    raw.add(matcher.descriptor());
                }
                descriptors.put(entry.getKey(), raw);
            }
            ProjectorResponsePacket.sendTo(player, message.pos, descriptors);
        });
        context.setPacketHandled(true);
    }

    /** 客户端入口：请求某个控制器的虚影结构。服务端校验后回 {@link ProjectorResponsePacket}。 */
    public static void sendRequest(BlockPos pos) {
        ModNetwork.CHANNEL.sendToServer(new ProjectorRequestPacket(pos));
    }
}
