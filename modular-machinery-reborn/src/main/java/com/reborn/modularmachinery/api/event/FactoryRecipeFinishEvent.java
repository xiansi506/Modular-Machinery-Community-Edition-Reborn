package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * C2 收尾（0.89.0）：工厂线程完成一条配方后发出——旧版 {@code FactoryRecipeFinishEvent}
 * （{@code TileFactoryController:305}）的对应物。产物已结算、完成命令已跑；只读。
 */
public final class FactoryRecipeFinishEvent extends FactoryRecipeEvent {

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public FactoryRecipeFinishEvent() {
    }

    public FactoryRecipeFinishEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                                    MachineRecipe recipe, int progress, int maxProgress, int parallelism,
                                    int threadOrdinal) {
        super(controller, machineId, recipe, progress, maxProgress, parallelism, threadOrdinal);
    }
}
