package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * C2 收尾（0.89.0）：工厂线程里跑一条配方时的事件基类——旧版 {@code FactoryRecipeEvent}
 * （携带 {@code FactoryRecipeThread}）的对应物。本版没有线程对象可外露（{@code FactoryThread}
 * 是工厂引擎的内部状态槽），改携 {@link #threadOrdinal()}：核心线程与普通线程共用的槽位序号，
 * 与工厂界面的槽位一一对应。
 *
 * <p>子类与时机（对照旧版 {@code TileFactoryController:215-305}）：
 * {@link FactoryRecipeStartEvent} 开工成后；{@link FactoryRecipeTickEvent} 逐 tick 前后（可取消）；
 * {@link FactoryRecipeFailureEvent} 逐 tick 失败时（可改写 destruct）；{@link FactoryRecipeFinishEvent} 完成后。
 */
public abstract class FactoryRecipeEvent extends RecipeEvent {

    private final int threadOrdinal;

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    protected FactoryRecipeEvent() {
        this.threadOrdinal = -1;
    }

    protected FactoryRecipeEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                                 MachineRecipe recipe, int progress, int maxProgress, int parallelism,
                                 int threadOrdinal) {
        super(controller, machineId, recipe, progress, maxProgress, parallelism);
        this.threadOrdinal = threadOrdinal;
    }

    /** 工厂线程的槽位序号（核心线程与普通线程共用一套），与工厂界面的槽位对应。 */
    public int threadOrdinal() {
        return this.threadOrdinal;
    }
}
