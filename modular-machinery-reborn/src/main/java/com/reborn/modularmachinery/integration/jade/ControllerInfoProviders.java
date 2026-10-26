package com.reborn.modularmachinery.integration.jade;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * E9（0.99.0）：控制器的 Jade 信息——旧版 {@code MMInfoProvider}（TOP，415 行）的控制器半边对应物。
 *
 * <p>旧版 TOP 显示：owner（自己/已知名字/未知 UUID）、结构成型与否、状态行（空闲/工作中/失败原因）、
 * 并行（当前/上限）、进度（潜行时以秒显示）。本版 Jade 插件此前只有流体/气体条——E9 行记录的
 * 「进度、状态、owner、并行数都没有」由本类补齐（文本行；进度条 v1 用百分比文本，不画 Jade 进度元素）。
 *
 * <p><b>数据流</b>：服务端 provider 把控制器状态写进 NBT（Jade 负责同步给客户端）→ 客户端 tooltip
 * 读镜像。服务器才有 owner 名字与权威状态，所以走 Jade 的 server-data 机制而不是客户端直接读 BE。
 *
 * <p><b>能量行（0.105.0 补齐）</b>：旧版从激活配方的需求里聚合能耗——本版同一条算式住在
 * {@code EnergyRequirement.perTickDraw}（逐 tick 结算本身），BE 的 {@code energySnapshot}
 * 聚合 INPUT/OUTPUT 两路，服务端快照 → NBT → tooltip 两行（消耗/生成）。0.115.0 同时聚合工厂
 * 的核心与普通线程；显示当前配方需求速率，临时阻塞但保留配方的线程也计入，不是实际流量计。
 *
 * <p>API 签名（jade-11.13.3，javap 已核对）：\`IServerDataProvider#appendServerData(CompoundTag, BlockAccessor)\`
 * ——不像预想的那样按 player/level/BE 分参，全部从 {@link BlockAccessor} 取；两个 provider 都必须
 * 给 {@code getUid()}（IJadeProvider 的抽象方法）。
 */
public final class ControllerInfoProviders {

    public static final ResourceLocation ID = new ResourceLocation("modular_machinery_reborn", "controller_info");

    private ControllerInfoProviders() {
    }

    // ------------------------------------------------------------ 服务端：状态 → NBT

    public static final class Server implements IServerDataProvider<BlockAccessor> {

        @Override
        public ResourceLocation getUid() {
            return ID;
        }

        @Override
        public void appendServerData(CompoundTag tag, BlockAccessor access) {
            if (!(access.getBlockEntity()
                    instanceof com.reborn.modularmachinery.block.MachineControllerBlockEntity controller)) {
                return;
            }
            Player viewer = access.getPlayer();
            Level level = access.getLevel();

            tag.putBoolean("formed", controller.isFormed());
            tag.putString("status", controller.status().translationKey());

            UUID owner = controller.getOwner();
            if (owner != null) {
                tag.putString("owner", resolveOwnerName(level, owner, viewer));
                tag.putBoolean("owner_self", owner.equals(viewer.getUUID()));
            }

            if (!controller.isFormed()) {
                return;
            }

            int parallel = controller.activeParallelism();
            int ceiling = controller.parallelCeiling();
            if (parallel > 1) {
                tag.putInt("parallelism", parallel);
                tag.putInt("ceiling", ceiling);
            }

            var failure = controller.startFailureKey();
            if (failure != null) {
                tag.putString("failure", failure);
            }
            var cause = controller.failureCauseText();
            if (cause != null) {
                tag.putString("failureCause", cause);
            }

            if (controller.status() == com.reborn.modularmachinery.machine.ControllerStatus.CRAFTING
                    && controller.maxProgress() > 0) {
                tag.putInt("progress", controller.progress());
                tag.putInt("maxProgress", controller.maxProgress());
            }

            // E9 能量行（0.105.0）：当前合成每 tick 的 [消耗, 生成]（FE/t）。快照在 BE 的
            // energySnapshot——与逐 tick 结算同一条算式（EnergyRequirement.perTickDraw），
            // 工厂使用每个核心／普通线程自己的并行及有效修改器；这表示需求速率，不是实际流量。
            long[] energy = controller.energySnapshot(level);
            if (energy[0] > 0L) {
                tag.putLong("energyIn", energy[0]);
            }
            if (energy[1] > 0L) {
                tag.putLong("energyOut", energy[1]);
            }
        }

        /**
         * owner 名字解析（照旧版：自己 → 空串由「你自己」键替代；缓存命中 → 名字；未命中 → 未知 + UUID）。
         */
        private static String resolveOwnerName(Level level, UUID owner, Player viewer) {
            if (owner.equals(viewer.getUUID())) {
                return "";
            }
            MinecraftServer server = level.getServer();
            var profile = server == null ? null : server.getProfileCache().get(owner).orElse(null);
            return profile == null ? "? (UUID: " + owner + ")" : profile.getName();
        }
    }

    // ------------------------------------------------------------ 客户端：NBT → tooltip

    public static final class Client implements IBlockComponentProvider {

        @Override
        public ResourceLocation getUid() {
            return ID;
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor access, IPluginConfig config) {
            CompoundTag data = access.getServerData();
            if (data.isEmpty() || !data.contains("status")) {
                return;
            }

            if (data.contains("owner")) {
                String owner = data.getString("owner");
                Component name = data.getBoolean("owner_self")
                        ? Component.translatable("jade.modular_machinery_reborn.machine.owner.self")
                        : Component.literal(owner);
                tooltip.add(Component.translatable("jade.modular_machinery_reborn.machine.owner")
                        .append(name));
            }

            boolean formed = data.getBoolean("formed");
            tooltip.add(Component.translatable(data.getBoolean("formed")
                    ? "jade.modular_machinery_reborn.machine.structure.found"
                    : "jade.modular_machinery_reborn.machine.structure.none"));
            if (!formed) {
                return;
            }

            // 状态行：CRAFTING 用「工作中」，其余直接本地化状态键（与控制器界面同源）
            String status = data.getString("status");
            boolean crafting = status.endsWith(".crafting");
            tooltip.add(Component.translatable(crafting
                    ? "jade.modular_machinery_reborn.machine.working"
                    : status));

            if (data.contains("parallelism")) {
                tooltip.add(Component.translatable("jade.modular_machinery_reborn.machine.parallelism",
                        data.getInt("parallelism"), data.getInt("ceiling")));
            }

            if (data.contains("failure")) {
                tooltip.add(Component.translatable(data.getString("failure"))
                        .withStyle(net.minecraft.ChatFormatting.RED));
            }
            if (data.contains("failureCause")) {
                String cause = data.getString("failureCause");
                net.minecraft.network.chat.MutableComponent line = cause.contains(".")
                        ? Component.translatable(cause) : Component.literal(cause);
                tooltip.add(line.withStyle(net.minecraft.ChatFormatting.RED));
            }

            if (data.contains("progress")) {
                int progress = data.getInt("progress");
                int max = data.getInt("maxProgress");
                int percent = (int) (progress * 100L / max);
                String line = access.showDetails()
                        ? String.format("%.1f s / %.1f s", progress / 20.0F, max / 20.0F)
                        : percent + "%";
                tooltip.add(Component.translatable("jade.modular_machinery_reborn.machine.progress", line));
            }

            // E9 能量行（0.105.0）：「消耗 X FE/t」「生成 X FE/t」——旧版
            // addEnergyUsageText（MMInfoProvider）的同款两行；潜行时精确数字（无分组分隔符），
            // 站立时千位分组（旧版 formatEnergyUsage 的 sneak 分支语义）。
            if (data.contains("energyIn")) {
                tooltip.add(Component.translatable("jade.modular_machinery_reborn.machine.energy.in",
                        formatEnergy(access, data.getLong("energyIn"))));
            }
            if (data.contains("energyOut")) {
                tooltip.add(Component.translatable("jade.modular_machinery_reborn.machine.energy.out",
                        formatEnergy(access, data.getLong("energyOut"))));
            }
        }

        /** 旧版 {@code formatEnergyUsage}：潜行 = 精确数字，站立 = 千位分组。 */
        private static String formatEnergy(BlockAccessor access, long value) {
            return access.showDetails()
                    ? Long.toString(value)
                    : java.text.DecimalFormat.getIntegerInstance().format(value);
        }
    }
}
