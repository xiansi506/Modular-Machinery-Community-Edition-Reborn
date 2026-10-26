package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * C2 小件（0.91.0）：一次合成**结算产物之前**发出——旧版 {@code ResultChanceCreateEvent}
 * （{@code RecipeCraftingContext#finishCrafting:385-395}，{@code Phase.END}）的对应物。
 *
 * <p>旧版携带一个按种子构造的 {@code ResultChance}（本次结算所有产物掷点的随机源），监听器可以
 * <b>整个替换</b>它——等价地决定「这次出多少」。本版没有 {@code ResultChance} 对象，产物量的掷点
 * 用的是下传给 {@code finish(...)} 的 {@link RandomSource}；事件携带它，监听器用
 * {@link #setRandom(RandomSource)} 替换后，替换结果就是本次结算实际使用的随机源。
 *
 * <p>触发点：单控制器与工厂线程都在结算（{@code recipe.finish}）之前各发一次；与旧版
 * 「每次 finishCrafting 一次」同粒度。默认随机源就是世界随机——不替换时行为与本版从前完全一致。
 */
public final class ResultChanceCreateEvent extends RecipeEvent {
    private int threadOrdinal = -1;
    public int threadOrdinal() { return threadOrdinal; }
    public ResultChanceCreateEvent forThread(int ordinal) { threadOrdinal = ordinal; return this; }

    private RandomSource random;

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public ResultChanceCreateEvent() {
    }

    public ResultChanceCreateEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                                   MachineRecipe recipe, int progress, int maxProgress, int parallelism,
                                   RandomSource random) {
        super(controller, machineId, recipe, progress, maxProgress, parallelism);
        this.random = Objects.requireNonNull(random, "random");
    }

    /** 本次结算将使用的随机源；默认是世界随机。 */
    public RandomSource random() {
        return this.random;
    }

    /** 替换本次结算使用的随机源（旧版 {@code setResultChance} 的等价物）。替换后立即生效于同一结算。 */
    public void setRandom(RandomSource random) {
        this.random = Objects.requireNonNull(random, "random");
    }
}
