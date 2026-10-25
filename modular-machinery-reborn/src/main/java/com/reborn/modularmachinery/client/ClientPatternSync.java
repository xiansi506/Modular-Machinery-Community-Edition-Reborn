package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.menu.MePatternProviderMenu;
import com.reborn.modularmachinery.network.MePatternSyncPacket;
import net.minecraft.client.Minecraft;

/**
 * C4-II（0.97.2）：样板列表镜像的客户端落点——\`MePatternSyncPacket\` 的客户端半边。
 * 镜像存在正在打开的菜单上；窗口没开或位置对不上时静默丢弃（服务端才是权威）。
 */
public final class ClientPatternSync {

    public static void handle(MePatternSyncPacket message) {
        var player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (player.containerMenu instanceof MePatternProviderMenu menu
                && menu.provider() != null
                && menu.provider().getBlockPos().equals(message.pos())) {
            menu.setClientPatterns(message.patterns());
            // 0.97.4：BE 镜像同步更新——重开窗口（新菜单从客户端 BE 初始化）也读得到。
            menu.provider().setClientPatternsMirror(message.patterns());
            // 0.102.0：工作模式随同一张包走（菜单镜像 + BE 镜像双落点，同样板列表的规矩）。
            menu.setClientWorkMode(message.workMode());
            menu.provider().setClientWorkModeMirror(message.workMode());
        }
    }

    private ClientPatternSync() {
    }
}
