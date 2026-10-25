package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.client.preview.BlueprintPreviewScreen;
import com.reborn.modularmachinery.client.preview.WorldGhostPreview;
import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachinePattern;
import com.reborn.modularmachinery.network.ProjectorRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * Client-only entry points, kept in their own class so nothing common ever loads a screen type.
 *
 * <p>Callers reach these through {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ...)}, which is the
 * same pattern the mod already uses for its client setup.
 */
public final class ClientPreview {

    private ClientPreview() {
    }

    public static void openBlueprint(MachineDefinition machine) {
        Minecraft.getInstance().setScreen(new BlueprintPreviewScreen(machine));
    }

    /**
     * 0.104.0（C6 第一片）：投影仪对成型控制器的开关动作。
     *
     * <p>取消分支纯客户端（虚影状态在这里）；开始分支必须向服务端要结构——
     * {@code rotationSteps} 与动态段节数只在服务端（见 {@code ProjectorRequestPacket} 的 javadoc），
     * 所以这里只发请求，「投影已开始」的消息等 {@link #ghostPreviewFromServer} 真正收到
     * 结构时再发。
     */
    public static void toggleGhostPreview(MachineControllerBlockEntity controller,
                                          BlockPos pos, Player player) {
        if (WorldGhostPreview.isActiveAt(pos)) {
            WorldGhostPreview.clear();
            player.displayClientMessage(Component.translatable(
                    "message.machine_projector.cleared"), true);
            return;
        }
        ProjectorRequestPacket.sendRequest(pos);
    }

    /** 服务端应答落点：结构已编好码，交给虚影渲染器置位。 */
    public static void ghostPreviewFromServer(BlockPos attached, MachinePattern pattern) {
        if (WorldGhostPreview.startFromRaw(attached, pattern)) {
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                player.displayClientMessage(Component.translatable(
                        "message.machine_projector.projected"), true);
            }
        }
    }
}
