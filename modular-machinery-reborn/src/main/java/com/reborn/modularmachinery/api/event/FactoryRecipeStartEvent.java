package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * C2 收尾（0.89.0）：工厂线程开工成后发出——旧版 {@code FactoryRecipeStartEvent}
 * （{@code TileFactoryController:275}）的对应物。
 *
 * <p>与单控制器 {@link RecipeStartEvent} 同一条规矩：只在开工**真的发生**后发（本版 {@code begin}
 * 里 {@code start} 已消费一次性输入、槽位已武装），只读不可取消——旧版的「取消照样开工」
 * （1.12 事件总线残留）有意不复制；要否决开工用 {@link RecipeCheckEvent#setFailed}（两版引擎共用的
 * 开工门槛事件，0.88.0 起可否决）。
 */
public final class FactoryRecipeStartEvent extends FactoryRecipeEvent {

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public FactoryRecipeStartEvent() {
    }

    public FactoryRecipeStartEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                                   MachineRecipe recipe, int progress, int maxProgress, int parallelism,
                                   int threadOrdinal) {
        super(controller, machineId, recipe, progress, maxProgress, parallelism, threadOrdinal);
    }
}
