package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineRegistry;
import com.reborn.modularmachinery.network.MachineDefinitionSyncPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/** 独立的逻辑客户端机器表；同步和断线都不得写入服务端权威注册表。 */
public final class ClientMachines {

    private static volatile int lastReceived = 0;
    private static volatile int lastRejected = 0;

    private ClientMachines() {
    }

    /**
     * 接收一次同步：解析每份定义并整体写入注册表。
     *
     * <p>整体替换而不是合并：服务端发的是**完整的一份**（它在自己的 {@code reload} 之后也是这样整体
     * 替换的），合并会让上一次同步里已经删掉的机器留在客户端。
     */
    public static void accept(Map<ResourceLocation, String> definitions) {
        Map<ResourceLocation, MachineDefinition> parsed = new LinkedHashMap<>();
        int rejected = 0;
        for (Map.Entry<ResourceLocation, String> entry : definitions.entrySet()) {
            MachineDefinition definition = MachineDefinitionSyncPacket.parseForClient(
                    entry.getKey(), entry.getValue());
            if (definition == null) {
                rejected++;
                continue;
            }
            parsed.put(definition.id(), definition);
        }
        // 定义自身的 id 与它被发送时用的 id 必须一致；不一致说明包被拼错了，
        // 这时以**定义自己的 id** 为准（它是从 JSON 里读出来的）。
        lastReceived = parsed.size();
        lastRejected = rejected;
        MachineRegistry.replaceClient(parsed);
        // 记一条日志，因为「同步成功」这件事在游戏里**看不见**：JEI 分类有内容就是有、
        // 没有就是没有，而两者在视觉上都只是一个空/满的分类。开发者需要一个能直接读的事实。
        com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.info(
                "[{}] Received {} machine definition(s){}", com.reborn.modularmachinery.ModularMachineryReborn.MOD_ID,
                lastReceived, rejected == 0 ? "" : ", " + rejected + " could not be read and were skipped");
    }

    /** 断线时清空——否则换一个服务器之后，上一个服务器的机器还留在界面里。 */
    public static void clear() {
        lastReceived = 0;
        lastRejected = 0;
        MachineRegistry.clearClient();
    }

    /** 只响应实际客户端网络断线，访客的服务端登出不触发此清理。 */
    public static void onLoggingOut(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /** 上一次同步收到了几台机器（诊断与断言用；界面不依赖它）。 */
    public static int lastReceived() {
        return lastReceived;
    }

    /** 上一次同步里解析失败的条目数（诊断与断言用）。 */
    public static int lastRejected() {
        return lastRejected;
    }
}
