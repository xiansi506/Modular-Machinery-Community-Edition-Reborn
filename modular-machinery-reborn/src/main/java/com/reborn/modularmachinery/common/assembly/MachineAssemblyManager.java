package com.reborn.modularmachinery.common.assembly;

import net.minecraft.core.BlockPos;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * A8（0.106.0）：进行中的自动搭建登记表——旧版
 * {@code ink/ikx/mmce/common/assembly/MachineAssemblyManager}（46 行）的对应物。
 *
 * <p>语义原样：一个控制器同时只能有一个搭建任务（{@code checkMachineExist}）；按玩家取
 * （他的 tick 推进他的任务）；登出清空。旧版持有 {@code EntityPlayer} 引用并在登出事件里
 * 清理；本版直接存 {@link UUID}，逐 tick 按在线玩家解析——登出后任务自然找不到人，
 * 显式清理（登出事件）只是提早一拍。
 */
public final class MachineAssemblyManager {

    private record Location(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos pos) { }
    private static final Map<Location, MachineAssembly> ASSEMBLIES = new ConcurrentHashMap<>();

    private MachineAssemblyManager() {
    }

    public static void add(MachineAssembly assembly) {
        ASSEMBLIES.put(new Location(assembly.dimension(), assembly.ctrlPos()), assembly);
    }

    /** 旧版 {@code checkMachineExist}：同控制器已有任务在跑。 */
    public static boolean exists(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos ctrlPos) {
        return ASSEMBLIES.containsKey(new Location(dimension, ctrlPos));
    }

    /** 某玩家名下的全部任务（旧版 {@code getMachineAssemblyListFromPlayer}）。 */
    public static List<MachineAssembly> ofPlayer(UUID playerId) {
        return ASSEMBLIES.values().stream()
                .filter(assembly -> assembly.playerId().equals(playerId))
                .collect(Collectors.toList());
    }

    public static void remove(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, BlockPos ctrlPos) {
        ASSEMBLIES.remove(new Location(dimension, ctrlPos));
    }

    /** 登出清理（旧版 {@code removeMachineAssembly(player)}）。 */
    public static void removeAllOf(UUID playerId) {
        ASSEMBLIES.entrySet().removeIf(entry -> entry.getValue().playerId().equals(playerId));
    }

    /** 供测试/诊断读取；不要在运行时遍历后修改。 */
    public static Collection<MachineAssembly> view() {
        return List.copyOf(ASSEMBLIES.values());
    }
}
