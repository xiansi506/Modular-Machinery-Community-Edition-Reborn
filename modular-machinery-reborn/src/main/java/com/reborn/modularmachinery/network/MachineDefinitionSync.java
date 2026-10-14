package com.reborn.modularmachinery.network;

import com.reborn.modularmachinery.ModularMachineryReborn;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;

/**
 * 波 4 的发送端：什么时候把机器定义推给客户端。
 *
 * <h2>为什么是 {@code OnDatapackSyncEvent}</h2>
 *
 * <p>这个事件正好覆盖需要的两个时机，而且是 Forge 为「数据包内容变了，把结果告诉客户端」提供的
 * 那条路：
 *
 * <ul>
 *   <li><b>玩家登录</b>时针对该玩家触发一次；
 *   <li><b>{@code /reload} 之后</b>针对所有人触发一次（那时 {@code getPlayer()} 是 {@code null}）。</li>
 * </ul>
 *
 * <p>自己监听 {@code PlayerLoggedInEvent} 是错的：定义由数据包加载，而玩家可能在加载完成前进服，
 * 于是客户端会收到一份空的注册表，而它在整局游戏里都不会再更新——那种 bug 只在某些进服时序下出现。
 *
 * <h2>为什么在 {@code /reload} 之后重发是必须的，不是优化</h2>
 *
 * <p>{@code MachineLoader} 每次重载都会<strong>整体替换</strong>服务端的注册表（`replace`），
 * 所以一次 {@code /reload} 之后服务端手里的机器可能与客户端手里的完全不同（增、删、改都可能）。
 * 不重发的话，两个界面就会各自显示一份不同的机器表——而其中一份是过期的，
 * 这种不一致没有任何症状，只会让人怀疑自己的数据包。
 */
public final class MachineDefinitionSync {

    private MachineDefinitionSync() {
    }

    public static void onDatapackSync(OnDatapackSyncEvent event) {
        Map<ResourceLocation, String> snapshot = MachineDefinitionSyncPacket.snapshot();
        MachineDefinitionSyncPacket packet = new MachineDefinitionSyncPacket(snapshot);
        ServerPlayer player = event.getPlayer();
        if (player != null) {
            // 单人游戏里玩家进服走这条路。
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        } else {
            // `/reload`：注册表刚刚被整体替换，所有在线客户端都必须重新拿一份。
            ModNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), packet);
        }
        ModularMachineryReborn.LOGGER.info("[{}] Sent {} machine definition(s) to {}",
                ModularMachineryReborn.MOD_ID, snapshot.size(),
                player != null ? player.getName().getString() : "all clients");
    }
}
