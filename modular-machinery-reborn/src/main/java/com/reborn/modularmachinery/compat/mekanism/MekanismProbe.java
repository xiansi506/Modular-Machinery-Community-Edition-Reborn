package com.reborn.modularmachinery.compat.mekanism;

import com.reborn.modularmachinery.ModularMachineryReborn;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实机探针：气体端口这条路上**每一步到底有没有被走到**。
 *
 * <h2>为什么必须有它</h2>
 *
 * <p>「化学品进不去流体输入仓」有三个**完全不同**的原因，而它们在日志里长得一模一样
 * （什么都不打印）：能力查询根本没被转到本模组、被 {@code sameType} 拒了、容量被算成 0。
 * 读代码区分不了它们——本项目规矩 5 说的就是这件事：这类缺陷只能靠运行时探针 settle。
 * 所以这里在三个关键位置各打一行，**默认关闭**，用系统属性打开：
 *
 * <pre>{@code -Dmodular_machinery_reborn.compat.debug=true}</pre>
 *
 * <p>（与既有的 {@code -Dmodular_machinery_reborn.preview.debug=true} 同一形状。）
 *
 * <h2>为什么限次</h2>
 *
 * <p>能力查询是**每个方块、每次管道询问**都会走的路（Mekanism 的管道每 tick 都可能问），
 * 不限次会把日志淹掉、反而看不到有用的信息。所以每类信息各打印前 {@link #LIMIT} 次，
 * 并明确写出「后面还有多少次被抑制」——**一个被静默截断的探针与一个没打印的探针一样有害**。
 */
final class MekanismProbe {

    /** 开关：{@code -Dmodular_machinery_reborn.compat.debug=true}。 */
    private static final boolean ENABLED =
            Boolean.getBoolean("modular_machinery_reborn.compat.debug");

    /** 每一类信息打印多少次。 */
    private static final int LIMIT = 20;

    private static final AtomicInteger MAPPED = new AtomicInteger();
    private static final AtomicInteger NOT_MINE = new AtomicInteger();
    private static final AtomicInteger INSERTED = new AtomicInteger();
    private static final AtomicInteger HANDLERS = new AtomicInteger();

    private MekanismProbe() {
    }

    static boolean enabled() {
        return ENABLED;
    }

    /** 能力查询：这个映射器被问到了，以及结论是什么。 */
    static void map(String outcome, Object entity) {
        if (!ENABLED) {
            return;
        }
        int n = "mine".equals(outcome) ? MAPPED.incrementAndGet() : NOT_MINE.incrementAndGet();
        if (n <= LIMIT) {
            ModularMachineryReborn.LOGGER.info("[compat/mekanism] capability query on {} -> {}", 
                    entity == null ? "null" : entity.getClass().getSimpleName(), outcome);
        } else if (n == LIMIT + 1) {
            ModularMachineryReborn.LOGGER.info("[compat/mekanism] {} capability queries of this kind so far; "
                    + "further ones are suppressed", n);
        }
    }

    /** 端口收集：这个实体交出了几个处理器。 */
    static void handlers(Object entity, int count) {
        if (!ENABLED) {
            return;
        }
        int n = HANDLERS.incrementAndGet();
        if (n <= LIMIT) {
            ModularMachineryReborn.LOGGER.info("[compat/mekanism] port collection on {} -> {} handler(s)",
                    entity == null ? "null" : entity.getClass().getSimpleName(), count);
        }
    }

    /** 插入：谁在往里灌、罐里原本是什么、容量多少、返回了什么。 */
    static void insert(String detail) {
        if (!ENABLED) {
            return;
        }
        int n = INSERTED.incrementAndGet();
        if (n <= LIMIT) {
            ModularMachineryReborn.LOGGER.info("[compat/mekanism] insertChemical {}", detail);
        } else if (n == LIMIT + 1) {
            ModularMachineryReborn.LOGGER.info("[compat/mekanism] {} insertChemical calls so far; further ones "
                    + "are suppressed", n);
        }
    }

    /** 一次性启动信息：能力对象到底拿到没有。 */
    static void startup(String message) {
        if (ENABLED) {
            ModularMachineryReborn.LOGGER.info("[compat/mekanism] {}", message);
        }
    }
}
