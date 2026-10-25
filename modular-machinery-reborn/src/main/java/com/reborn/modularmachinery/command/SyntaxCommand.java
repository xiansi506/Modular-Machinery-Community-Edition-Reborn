package com.reborn.modularmachinery.command;

import com.reborn.modularmachinery.machine.MachineDefinition;
import com.reborn.modularmachinery.machine.MachineLoader;
import com.reborn.modularmachinery.machine.MachineRegistry;
import com.reborn.modularmachinery.recipe.ModRecipeTypes;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F4（0.86.0）：{@code /mm-syntax}——旧版 {@code CommandSyntax} 的对应物：把「机器与配方现在加载得对不对」
 * 一次性问出来，错误逐条回到聊天里，而不是埋在日志里。
 *
 * <h2>与旧版的差异（旧版在 1.12 是「重跑一遍机器与配方加载」）</h2>
 * <ul>
 *   <li><b>机器侧改为重读磁盘、不重跑加载</b>：{@link MachineLoader#syntaxRescan} 用与 reload 完全相同的
 *       合并顺序和解析规则重读数据包 + 配置目录的当前文件，把每条失败带回命令；但**不**替换已加载注册表——
 *       一条诊断命令不该有副作用。作者据此修完文件后再 {@code /reload} 生效。旧版能整场重载，是因为它的
 *       机器在 1.12 的注册期加载；本版机器跟着数据包管线走，重载入口是原版的 {@code /reload}。</li>
 *   <li><b>KubeJS 脚本机器不在重扫范围内</b>（脚本只在真实 reload 中暂存）；「已加载但磁盘没有」的机器
 *       会在对照行里显出来，这正是脚本机器该出现的位置。</li>
 *   <li><b>配方侧报加载结果而不是重读 JSON</b>：1.20.1 里配方解析失败会让整场数据包加载失败，能被
 *       {@code RecipeManager} 拿到的都是已解析成功的。所以这里报的是：配方总数、每台机器各多少条、
 *       **指向未知机器的配方**（machine 字段写错——加载期两边各自解析，对不上要到运行时才炸，这里提前点名）、
 *       以及注册表里**一条配方都没有**的机器（配方的 machine 与定义的 registryname 对不上时最常见）。</li>
 *   <li>保留旧版的权限等级 2；输出随本版命令的惯例用中文。</li>
 * </ul>
 */
public final class SyntaxCommand {

    /** 每类长清单最多打印多少行，超出只报条数——诊断命令不该刷屏到把有用行顶出聊天。 */
    private static final int MAX_LINES = 30;

    private SyntaxCommand() {
    }

    public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mm-syntax")
                .requires(source -> source.hasPermission(2))
                .executes(context -> run(context.getSource())));
    }

    private static int run(CommandSourceStack source) {
        String prefix = "command.modular_machinery_reborn.syntax.";
        // ---- 机器侧：磁盘当前状态的语法检查 ------------------------------------------------
        MachineLoader.SyntaxReport report = MachineLoader.syntaxRescan(
                source.getServer().getResourceManager());
        source.sendSuccess(() -> Component.translatable(prefix + "machines_title"), false);
        source.sendSuccess(() -> Component.translatable(prefix + "machines_summary",
                report.variableSets(), report.parsedIds().size(), report.failures().size()), false);
        int shown = 0;
        for (String failure : report.failures()) {
            if (shown++ >= MAX_LINES) {
                final int rest = report.failures().size() - MAX_LINES;
                source.sendSuccess(() -> Component.translatable(prefix + "more_failures", rest), false);
                break;
            }
            source.sendSuccess(() -> Component.translatable(prefix + "failure", failure), false);
        }

        // ---- 与已加载注册表对照：磁盘能过但没加载 / 已加载但磁盘没有 -------------------------
        Set<String> loaded = new HashSet<>();
        for (MachineDefinition definition : MachineRegistry.all()) {
            loaded.add(definition.id().toString());
        }
        List<String> notLoadedYet = report.parsedIds().stream()
                .filter(id -> !loaded.contains(id)).toList();
        List<String> notOnDisk = loaded.stream()
                .filter(id -> !report.parsedIds().contains(id)).sorted().toList();
        if (notLoadedYet.isEmpty() && notOnDisk.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(prefix + "registry_match"), false);
        } else {
            if (!notLoadedYet.isEmpty()) {
                source.sendSuccess(() -> Component.translatable(prefix + "not_loaded_yet",
                        String.join(", ", notLoadedYet)), false);
            }
            if (!notOnDisk.isEmpty()) {
                source.sendSuccess(() -> Component.translatable(prefix + "not_on_disk",
                        String.join(", ", notOnDisk)), false);
            }
        }

        // ---- 配方侧：加载结果与交叉引用 -----------------------------------------------------
        source.sendSuccess(() -> Component.translatable(prefix + "recipes_title"), false);
        RecipeManager recipeManager = source.getServer().getRecipeManager();
        Map<String, Integer> perMachine = new LinkedHashMap<>();
        Set<String> referenced = new HashSet<>();
        int total = 0;
        int unknownMachine = 0;
        // 与全仓其它配方枚举同一个入口（getAllRecipesFor），不碰 byType——1.20.1 里它是 private。
        for (com.reborn.modularmachinery.recipe.MachineRecipe machineRecipe
                : recipeManager.getAllRecipesFor(ModRecipeTypes.MACHINE.get())) {
            total++;
            for (ResourceLocation machineId : machineRecipe.machineIds()) {
                String key = machineId.toString();
                perMachine.merge(key, 1, Integer::sum);
                referenced.add(key);
                if (!loaded.contains(key)) {
                    unknownMachine++;
                    if (unknownMachine <= MAX_LINES) {
                        source.sendSuccess(() -> Component.translatable(prefix + "unknown_machine",
                                machineRecipe.getId(), key), false);
                    }
                }
            }
        }
        if (unknownMachine > MAX_LINES) {
            final int rest = unknownMachine - MAX_LINES;
            source.sendSuccess(() -> Component.translatable(prefix + "more_unknown", rest), false);
        }
        final int recipeTotal = total;
        final int unknownTotal = unknownMachine;
        source.sendSuccess(() -> Component.translatable(prefix + "recipes_summary",
                recipeTotal, perMachine.size(), unknownTotal), false);

        List<String> withoutRecipes = loaded.stream()
                .filter(id -> !referenced.contains(id)).sorted().toList();
        if (withoutRecipes.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(prefix + "every_machine_has_recipes"), false);
        } else {
            final int rest = Math.max(0, withoutRecipes.size() - MAX_LINES);
            final String joined = String.join(", ", withoutRecipes.subList(0,
                    Math.min(withoutRecipes.size(), MAX_LINES)));
            source.sendSuccess(() -> Component.translatable(prefix + "no_recipe_machines",
                    withoutRecipes.size(), joined), false);
            if (rest > 0) {
                source.sendSuccess(() -> Component.translatable(prefix + "more_machines", rest), false);
            }
        }
        return report.failures().isEmpty() && unknownTotal == 0 ? 1 : 0;
    }
}
