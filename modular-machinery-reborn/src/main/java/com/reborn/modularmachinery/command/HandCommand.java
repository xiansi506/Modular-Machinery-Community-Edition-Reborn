package com.reborn.modularmachinery.command;

import com.reborn.modularmachinery.network.ClipboardCopyPacket;
import com.reborn.modularmachinery.recipe.NbtPredicate;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * F2（0.86.0）：{@code /mm-hand}——把主手（空则副手）物品写成**本模组方言的描述符行**：注册名，带 NBT 时
 * 再附 {@code (with nbt: <模板 JSON>)}。聊天里显示一行，同时送进执行者的剪贴板，包作者可以直接粘进
 * 机器定义或配方的物品条目里。旧版 {@code CommandHand.java} 的对应物。
 *
 * <h2>与旧版的差异（都写在这儿，别在别处猜）</h2>
 * <ul>
 *   <li><b>没有 {@code @meta} 后缀</b>。旧版对 {@code getHasSubtypes()} 的物品追加 {@code @损伤值}，因为
 *       1.12 的描述符靠元数据区分子物品；1.20.1 物品已扁平化，本版的方块/物品描述符也不再解析 {@code @}，
 *       打印它只会引导作者写出一个读不了的描述符。</li>
 *   <li><b>NBT 是 {@link NbtPredicate} 的模板方言</b>（值全字符串、数字带类型后缀），不是旧版
 *       {@code NBTJsonSerializer} 的方言——打印出来的是**本版**配方的 {@code nbt} 块能读回去的形状。</li>
 *   <li><b>燃烧时间一行保留</b>：照旧版对有燃料值的物品补一行，数据来自 {@link ForgeHooks#getBurnTime}。
 *       这只是信息展示；本版与旧版一样没有可用的燃料需求类型（{@code any:fuel} 已明确拒绝）。</li>
 *   <li>输出用中文短句（与 {@code /mm-diagnose} 的既有风格一致），可复制的描述符行本身保持
 *       `注册名 (with nbt: ...)` 的旧版形状。</li>
 * </ul>
 */
public final class HandCommand {

    private HandCommand() {
    }

    public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mm-hand")
                .requires(source -> source.hasPermission(2))
                .executes(context -> run(context.getSource())));
    }

    private static int run(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            held = player.getOffhandItem();
        }
        if (held.isEmpty()) {
            source.sendFailure(Component.translatable("command.modular_machinery_reborn.hand.empty"));
            return 0;
        }

        String registryName = String.valueOf(ForgeRegistries.ITEMS.getKey(held.getItem()));
        StringBuilder descriptor = new StringBuilder(registryName);
        if (held.hasTag()) {
            String json = NbtPredicate.tagToTemplateJson(held.getTag()).toString();
            if (!json.isEmpty()) {
                descriptor.append(" (with nbt: ").append(json).append(" )");
            }
        }

        String line = descriptor.toString();
        source.sendSuccess(() -> Component.literal(line), false);
        source.sendSuccess(() -> Component.translatable("command.modular_machinery_reborn.hand.copied"), false);
        ClipboardCopyPacket.sendTo(player, line);

        int burn = ForgeHooks.getBurnTime(held, null);
        if (burn > 0) {
            final int burnTime = burn;
            source.sendSuccess(() -> Component.translatable(
                    "command.modular_machinery_reborn.hand.fuel_burn_time", burnTime), false);
        }
        return 1;
    }
}
