package com.reborn.modularmachinery.command;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.machine.ControllerTiming;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.List;

/**
 * F3（0.86.0）：{@code /mm-performance_report}——把当前加载的每台控制器的逐 tick 计时打出来，
 * 旧版 {@code CommandPerformanceReport} 的对应物；带 {@code reset} 子命令清零重测。
 *
 * <h2>数据源与旧版不同（这是有意的，不是欠账）</h2>
 *
 * <p>旧版报的是它那个**异步任务执行器**（{@code TaskExecutor} 线程池）的四个全局计数器：执行轮数、
 * 任务总数、总耗时、任务耗时。本版没有那个线程池——工厂与单机引擎按设计同步运行（见
 * {@link ControllerTiming} 的说明与「工厂真异步不做」的决定），照搬那四个数字只能造假。
 *
 * <p>本版真正测着的等价物是每台控制器自己的 {@link ControllerTiming}（窗口 20 tick 的滚动平均，
 * 单位 µs/t，机器耗时与配方搜索分开记）。所以本命令报告**逐台**数字并按平均耗时降序，头部给台数汇总；
 * {@code reset} 清的是每台的窗口（{@link ControllerTiming#reset()}），与旧版清全局计数器同义。
 *
 * <p>旧版输出还带 {@code WorkMode}（ASYNC/SEMI-SYNC/SYNC）；本版恒为 SYNC（同步构造），每台都一样，
 * 打印它是噪音，不印——要确认这一点去看控制器界面收尾行，那里有。
 */
public final class PerformanceReportCommand {

    private PerformanceReportCommand() {
    }

    public static void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mm-performance_report")
                .requires(source -> source.hasPermission(2))
                .executes(context -> report(context.getSource()))
                .then(Commands.literal("reset")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> reset(context.getSource()))));
    }

    private static int report(CommandSourceStack source) {
        List<MachineControllerBlockEntity> controllers = MachineControllerBlockEntity.loadedControllers();
        if (controllers.isEmpty()) {
            source.sendSuccess(() -> Component.translatable(
                    "command.modular_machinery_reborn.performance_report.none"), false);
            return 1;
        }

        // 快照每台的读数后再排序：sort 过程中某台 tick 一下不会换掉中间结果。
        record Row(MachineControllerBlockEntity controller, int usedAvg, int searchAvg) {
        }
        List<Row> rows = new java.util.ArrayList<>(controllers.size());
        int formed = 0;
        for (MachineControllerBlockEntity controller : controllers) {
            if (controller.isFormed()) {
                formed++;
            }
            rows.add(new Row(controller, controller.timing().usedTimeAvg(),
                    controller.timing().searchUsedTimeAvg()));
        }
        rows.sort(Comparator.comparingInt(Row::usedAvg).reversed());

        final int total = controllers.size();
        final int formedCount = formed;
        source.sendSuccess(() -> Component.translatable(
                "command.modular_machinery_reborn.performance_report.title", ControllerTiming.WINDOW), false);
        source.sendSuccess(() -> Component.translatable(
                "command.modular_machinery_reborn.performance_report.summary", total, formedCount), false);
        for (Row row : rows) {
            MachineControllerBlockEntity controller = row.controller();
            String dimension = controller.getLevel() == null ? "?"
                    : controller.getLevel().dimension().location().toString();
            String machine = controller.machineId() == null ? null : controller.machineId().toString();
            Component machineName = controller.machineId() == null
                    ? Component.translatable("command.modular_machinery_reborn.performance_report.unbound")
                    : Component.literal(machine);
            Component unformed = controller.isFormed() ? Component.empty()
                    : Component.translatable("command.modular_machinery_reborn.performance_report.unformed");
            source.sendSuccess(() -> Component.translatable(
                    "command.modular_machinery_reborn.performance_report.row",
                    controller.getBlockPos().toShortString(), dimension, machineName, unformed,
                    row.usedAvg(), row.searchAvg()), false);
        }
        source.sendSuccess(() -> Component.translatable(
                "command.modular_machinery_reborn.performance_report.note"), false);
        return rows.size();
    }

    private static int reset(CommandSourceStack source) {
        List<MachineControllerBlockEntity> controllers = MachineControllerBlockEntity.loadedControllers();
        for (MachineControllerBlockEntity controller : controllers) {
            controller.timing().reset();
        }
        final int count = controllers.size();
        source.sendSuccess(() -> Component.translatable(
                "command.modular_machinery_reborn.performance_report.reset", count), false);
        return count;
    }
}
