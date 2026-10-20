package com.reborn.modularmachinery.block;

import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;

/**
 * A3（勘测 §4 第 116 行）：仓口与控制器给比较器的读数。
 *
 * <p>旧版在这四个方块上覆写了 {@code hasComparatorInputOverride}，读数全部来自
 * {@code util/RedstoneHelper.java:28-47}：
 *
 * <ul>
 *   <li><b>物品</b>：{@code ItemHandlerHelper.calcRedstoneFromInventory(inventory)}——按槽位
 *       {@code min(slotLimit, maxStackSize)} 的填充比例求平均，再映射到 1..15。</li>
 *   <li><b>流体</b>：{@code round(15 * amount / capacity)}，夹在 0..15。</li>
 *   <li><b>能量</b>：同式，{@code cur / max}。</li>
 *   <li><b>控制器</b>：工作中 15、已成型但空闲 1、其它 0
 *       （{@code BlockController.java:259-271}）。</li>
 * </ul>
 *
 * <p>这里把公式单独放成静态方法，是为了让离线断言能直接钉住数值（只碰库存/储罐/能量，
 * 不需要世界），方块那边只剩“拿到方块实体、按家族分派”的接线。夹取与取整必须保持旧版写法：
 * 流体用 float 除法、能量用 double 除法后转 float，这两种在极大容量下才会差 1 级，照抄即可。
 *
 * <p><b>气体不计入</b>：旧版没有气体仓；本版的混合仓把气体放在
 * {@code FluidTankWithGasPriority} 的另一半，{@code getFluidAmount()} 只回答流体。
 * 这里故意保持“比较器只读流体”，与界面/Jade 那条把气体当内容显示的路分开。
 */
final class ComparatorLevels {

    private ComparatorLevels() {
    }

    /** 物品仓：旧版直接交给 Forge 的库存读数，空/无库存是 0。 */
    static int items(@Nullable IItemHandler handler) {
        return handler == null ? 0 : ItemHandlerHelper.calcRedstoneFromInventory(handler);
    }

    /** 流体仓：旧版 {@code TileFluidTank} 分支。 */
    static int tank(@Nullable FluidTank tank) {
        if (tank == null) {
            return 0;
        }
        int capacity = tank.getCapacity();
        if (capacity <= 0) {
            return 0;
        }
        return clamp(Math.round(15F * (tank.getFluidAmount() / (float) capacity)));
    }

    /** 能量仓：旧版 {@code TileEnergyHatch} 分支。 */
    static int energy(@Nullable EnergyStorage energy) {
        if (energy == null) {
            return 0;
        }
        int capacity = energy.getMaxEnergyStored();
        if (capacity <= 0) {
            return 0;
        }
        // 旧版是 double 相除再转 float（RedstoneHelper.java:42-43），照抄以免边界上差一级。
        return clamp(Math.round(15F * (float) ((double) energy.getEnergyStored() / (double) capacity)));
    }

    /** 控制器：工作中 15、已成型 1、没有结构 0（{@code BlockController.java:262-263}）。 */
    static int controller(boolean working, boolean formed) {
        return working ? 15 : formed ? 1 : 0;
    }

    private static int clamp(int level) {
        return Math.max(0, Math.min(15, level));
    }
}
