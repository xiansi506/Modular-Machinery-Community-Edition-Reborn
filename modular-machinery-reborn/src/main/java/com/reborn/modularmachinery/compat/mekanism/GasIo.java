package com.reborn.modularmachinery.compat.mekanism;

import mekanism.api.Action;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.gas.IGasHandler;

import java.util.List;

/**
 * 气体端口的读、抽、灌 —— {@link com.reborn.modularmachinery.recipe.IngredientIo} 的流体那几个方法的气体版本。
 *
 * <p>三条纪律照抄 {@code IngredientIo} 的类注释，因为它们不是风格而是缺陷史：
 * <b>每次提交前先模拟</b>、<b>提交要么全做要么全不做</b>（跨整个 handler 列表），
 * 于是合成永远不会「吃掉一半原料」。本类的 {@link #extract} / {@link #insert} 都是这个形状。
 *
 * <p><b>为什么 {@code long}。</b>{@code ChemicalStack#getAmount()} 与 {@code IChemicalHandler} 的
 * {@code getTankCapacity} / {@code extractChemical} 都是 {@code long}。引擎那一侧是 {@code int}，
 * 所以本类的签名也用 {@code int}，只在调用 Mekanism 时提升——**溢出因此只可能发生在「单次聚合超过 int」
 * 这一种情形**，而那与本项目能源侧的处理方式一致（{@code IngredientIo.insertCapacity} 也用 {@code long}
 * 累加、最后夹到 {@code UNBOUNDED}）。
 *
 * <p><b>空栈的来源。</b>{@code IChemicalHandler} 继承 {@code IEmptyStackProvider}，所以每个 handler 自己
 * 答得出「我这门化学的空栈是什么」——{@code handler.getEmptyStack()}。用它而不是
 * {@code GasStack.EMPTY}：后者是**气体这一门**的空栈，而 {@code IChemicalHandler} 是四门化学共用的接口，
 * 一个实现方可以合法地不是气体 handler。虽然本项目只取 {@code IGasHandler} 形状的端口，
 * 但用 API 自己给的答案就不必依赖这个前提。
 */
final class GasIo {

    private GasIo() {
    }

    /** 这些端口上现存的 {@code gas} 总量。 */
    static int count(List<IGasHandler> handlers, GasStack gas) {
        long total = 0L;
        for (IGasHandler handler : handlers) {
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                GasStack stored = handler.getChemicalInTank(tank);
                if (!stored.isEmpty() && stored.isTypeEqual(gas)) {
                    total += stored.getAmount();
                    if (total >= Integer.MAX_VALUE) {
                        return Integer.MAX_VALUE;
                    }
                }
            }
        }
        return (int) total;
    }

    /**
     * 从这些端口抽出 {@code amount}，不足则**一并不抽**。
     *
     * <p>先按 {@link #count} 确认总量够，再逐罐 {@code Action.EXECUTE}——
     * 与 {@code IngredientIo.drainFluid} 完全同构（那边也是先 {@code countFluid} 再逐罐 {@code EXECUTE}）。
     */
    static boolean extract(List<IGasHandler> handlers, GasStack gas, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (count(handlers, gas) < amount) {
            return false;
        }
        long remaining = amount;
        for (IGasHandler handler : handlers) {
            if (remaining <= 0L) {
                break;
            }
            GasStack drained = handler.extractChemical(new GasStack(gas, remaining), Action.EXECUTE);
            remaining -= drained.getAmount();
        }
        return remaining <= 0L;
    }

    /** 这些端口能不能再接受 {@code amount}（只模拟，不改动任何东西）。 */
    static boolean canInsert(List<IGasHandler> handlers, GasStack gas, int amount) {
        if (amount <= 0) {
            return true;
        }
        long remaining = amount;
        for (IGasHandler handler : handlers) {
            if (remaining <= 0L) {
                break;
            }
            GasStack leftover = handler.insertChemical(new GasStack(gas, remaining), Action.SIMULATE);
            remaining = leftover.getAmount();
        }
        return remaining <= 0L;
    }

    /** 灌入 {@code amount}，不足则**一并失败**（先模拟，够了才真的灌）。 */
    static boolean insert(List<IGasHandler> handlers, GasStack gas, int amount) {
        if (amount <= 0) {
            return true;
        }
        if (!canInsert(handlers, gas, amount)) {
            return false;
        }
        long remaining = amount;
        for (IGasHandler handler : handlers) {
            if (remaining <= 0L) {
                break;
            }
            GasStack leftover = handler.insertChemical(new GasStack(gas, remaining), Action.EXECUTE);
            remaining = leftover.getAmount();
        }
        if (remaining > 0L) {
            // 模拟说这不可能发生。留下半灌的罐比响亮报错更糟——与 IngredientIo.insertAll 同一条理由。
            throw new IllegalStateException("Gas insertion disagreed with its own simulation for "
                    + gas.getTypeRegistryName() + " x" + amount + " (leftover " + remaining + ")");
        }
        return true;
    }

    /**
     * 这些端口还能容纳多少 {@code gas}，按每个罐自己的容量与本门化学的存量算。
     *
     * <p>与流体那边一样**是精确值而不是上界**：{@code getTankCapacity} 与 {@code getChemicalInTank}
     * 合起来就说清了「这个罐还能收多少」，不需要靠 {@code insertChemical} 去试
     * （对照 {@code IngredientIo.insertCapacity(List<IFluidHandler>, FluidStack)} 的注释）。
     */
    static int capacity(List<IGasHandler> handlers, GasStack gas) {
        long capacity = 0L;
        for (IGasHandler handler : handlers) {
            for (int tank = 0; tank < handler.getTanks(); tank++) {
                GasStack stored = handler.getChemicalInTank(tank);
                long free = handler.getTankCapacity(tank);
                if (!stored.isEmpty()) {
                    if (!stored.isTypeEqual(gas)) {
                        // 罐里是别的东西：这一格对本需求没有容量。（旧版 HybridGasTank 的罐是「要么流体要么气体」，
                        // 1.20.1 的 IChemicalHandler 一个罐只装一门化学，所以这条分支就是它的对应物。）
                        continue;
                    }
                    free -= stored.getAmount();
                }
                if (free > 0L) {
                    capacity += free;
                }
                if (capacity >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return (int) capacity;
    }
}
