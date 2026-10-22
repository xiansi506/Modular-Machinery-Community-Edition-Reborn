package com.reborn.modularmachinery.recipe;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 配方选取顺序——旧版 {@code RecipeRegistry} 的那套「priority 层内再按权重」的顺序。
 *
 * <h2>为什么单独一个类</h2>
 *
 * <p>这条顺序被四个地方共用：控制器挑配方（{@code availableRecipes}/{@code findRecipe}）、{@code /mm-diagnose}
 * 的「第一条为什么开工不了」、JEI 的配方列表、工厂线程的候选表。旧版把它藏在
 * {@code TreeMap<Integer, TreeSet<MachineRecipe>>} 里，谁遍历谁就自动有序；这一版没有那层容器，
 * 四处各写一遍 {@code sort} 迟早会漂移，所以这里只有一份比较器。
 *
 * <h2>顺序本身（照抄旧版）</h2>
 *
 * <ul>
 *   <li>先按 {@link MachineRecipe#priority()} <b>升序</b>：旧版 {@code TreeMap} 的键，0 层先被看到；</li>
 *   <li>同一层内按 {@link MachineRecipe#buildWeight()} <b>升序</b>：旧版 {@code TreeSet} 的比较器；</li>
 *   <li>完全相等时保持输入顺序（{@link List#sort} 是稳定排序）。</li>
 * </ul>
 *
 * <p><b>不复制旧版 TreeSet 的"同权重丢弃"</b>：旧版的 {@code buildWeight} 以全局自增的 {@code sortId}
 * 为基数，两条不同配方只有在 sortId 差恰好等于输入权重差时才会碰撞——那是旧版的一个真实缺陷（后加入的
 * 配方静默消失），不是需要保真的语义。这里让相等者都留下，用稳定排序给出确定顺序。
 *
 * <p>本类不修改入参，返回新列表；调用方可以安全地把它当快照使用。
 */
public final class RecipeOrder {

    /** priority 升序，再 buildWeight 升序；相等时由稳定排序保持输入顺序。 */
    public static final Comparator<MachineRecipe> COMPARATOR =
            Comparator.comparingInt(MachineRecipe::priority)
                    .thenComparingInt(MachineRecipe::buildWeight);

    private RecipeOrder() {
    }

    /**
     * 属于 {@code machine} 的配方，按旧版顺序排好。
     *
     * @param recipes 配方来源（通常是 {@code RecipeManager#getAllRecipesFor}）
     * @param machine 已经解析成注册名的机器 id（{@link MachineRecipe#belongsTo}）
     */
    public static List<MachineRecipe> forMachine(Iterable<MachineRecipe> recipes, ResourceLocation machine) {
        List<MachineRecipe> out = new ArrayList<>();
        for (MachineRecipe recipe : recipes) {
            if (recipe.belongsTo(machine)) {
                out.add(recipe);
            }
        }
        out.sort(COMPARATOR);
        return out;
    }

    /** 所有配方按旧版顺序排好；JEI 需要跨机器的一份稳定顺序。 */
    public static List<MachineRecipe> sorted(Iterable<MachineRecipe> recipes) {
        List<MachineRecipe> out = new ArrayList<>();
        for (MachineRecipe recipe : recipes) {
            out.add(recipe);
        }
        out.sort(COMPARATOR);
        return out;
    }
}
