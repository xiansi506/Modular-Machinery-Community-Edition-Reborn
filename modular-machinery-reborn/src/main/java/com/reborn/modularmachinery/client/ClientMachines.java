package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineRegistry;
import com.reborn.modularmachinery.network.MachineDefinitionSyncPacket;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端手里那一份机器定义，对应服务端 {@link MachineRegistry}。
 *
 * <p>形状照 {@link ClientSelection}：一个只被网络包写、只被界面读的静态缓存，
 * 断线时清空。
 *
 * <h2>为什么要把它写进注册表，而不是另开一个客户端专用表</h2>
 *
 * <p>读它的地方（JEI 的两个分类、升级总线界面、建造预览）本来就在问 {@link MachineRegistry}。
 * 如果把同步来的定义放进另一张表，那么每一个读点都要改成"先问客户端表、再问注册表"——
 * 而那正是「两条入口各自演进」的起点，这个项目已经为此付过一次代价。所以客户端重建之后
 * <strong>写进同一个注册表</strong>，读点一行都不用改。
 *
 * <p>{@code replace} 的单参数版本在这里是正确的那一个：客户端不往外同步，
 * 所以它不需要源 JSON（JSON 已经在同步到达时被用掉了）。
 *
 * <p><b>只读，不可信。</b>内容来自服务器，但也可能来自一个版本不匹配的对端，
 * 所以解析失败的条目会被跳过并计数，而不是让整次同步失败——少一台机器好过整个界面打不开。
 */
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
        MachineRegistry.replace(parsed);
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
        MachineRegistry.replace(Map.of());
    }

    /**
     * 断线事件的入口。客户端专用，由 {@code ModularMachineryReborn} 在 {@code Dist.CLIENT} 下挂上。
     *
     * <p>挂在 {@code PlayerEvent.PlayerLoggedOutEvent} 上：那在单人游戏退出存档时也会触发，
     * 而单人游戏里客户端与服务端是同一个注册表——所以清空之后，下一次进服会由
     * {@code OnDatapackSyncEvent} 重新填好。不清的话，一个已经被删掉的机器会留在界面上。
     */
    public static void onLoggingOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
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
