package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * C2 收尾（0.89.0）：工厂线程逐 tick 前后发出——旧版 {@code FactoryRecipeTickEvent}
 * （{@code TileFactoryController:227-252}）的对应物，可取消，两段语义不同：
 *
 * <ul>
 *   <li>{@link Phase#START} 被取消 = **整 tick 跳过**，本 tick 什么都不扣（旧版 {@code isFailure}
 *       短路；对应旧 {@code setFailed} 里「不让本 tick 结算」的那一半）。</li>
 *   <li>{@link Phase#END} 在本 tick 资源**已扣**之后、进度**未加**之前发出；被取消 = 资源留扣、
 *       进度不加——旧版 {@code preventProgressing}（回退 tick 计数）的等价物，本引擎里「已扣的
 *       逐 tick 资源」没有回退通道，所以差异只体现在进度上。</li>
 * </ul>
 *
 * <p>旧版 tick 事件上的 {@code setFailed(destruct, reason)} 不在本事件上：它的一半（跳过 tick）
 * 由 START 取消承担，另一半（放弃合成/改 failure-action）由 {@link FactoryRecipeFailureEvent} 承担。
 */
@net.minecraftforge.eventbus.api.Cancelable
public final class FactoryRecipeTickEvent extends FactoryRecipeEvent {

    private final Phase phase;

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public FactoryRecipeTickEvent() {
        this.phase = null;
    }

    public FactoryRecipeTickEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                                  MachineRecipe recipe, int progress, int maxProgress, int parallelism,
                                  int threadOrdinal, Phase phase) {
        super(controller, machineId, recipe, progress, maxProgress, parallelism, threadOrdinal);
        this.phase = java.util.Objects.requireNonNull(phase, "phase");
    }

    public Phase phase() {
        return this.phase;
    }
}
