package com.reborn.modularmachinery.api.event;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import com.reborn.modularmachinery.recipe.MachineRecipe;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Posted around the start gate of a craft: {@link Phase#START} before {@code canStart}/{@code canTick} are
 * asked, {@link Phase#END} after they both answered yes and before the one-shot inputs are paid.
 *
 * <p>The original's {@code RecipeCheckEvent} could also fail the craft and lower parallelism
 * ({@code TileMultiblockMachineController:1020-1049}). This port has both halves:
 * {@link #setParallelism(int)} lowers the parallelism used for <b>this</b> start, the original's
 * {@code min(recipe.getParallelism(), requested)}, clamped to {@code 1..computed} when the controller resolves
 * it; and {@link #setFailed(String)} vetoes the start outright — 0.88.0（C2b）起，配套的**失败原因字符串通道**
 * （方块实体同步的 {@code failureCause}）让监听器给的理由能显示在界面上，不再是上一片记录的
 * 「取消后只剩一句 idle」。
 *
 * <p>取消的时机语义：{@code START} 段被取消 = 不问门槛直接不开工；{@code END} 段被取消 =
 * 门槛已过、**一次性输入尚未支付**，同样不开工。两种情况都不扣任何东西。
 */
@net.minecraftforge.eventbus.api.Cancelable
public final class RecipeCheckEvent extends RecipeEvent {

    private final Phase phase;
    private int parallelism;
    @Nullable
    private String failureReason;

    /** For Forge's listener-list probe only; see {@link MachineEvent#MachineEvent()}. */
    public RecipeCheckEvent() {
        this.phase = null;
        this.parallelism = 0;
    }

    public RecipeCheckEvent(MachineControllerBlockEntity controller, @Nullable ResourceLocation machineId,
                            Phase phase, MachineRecipe recipe, int progress, int maxProgress, int parallelism) {
        super(controller, machineId, recipe, progress, maxProgress, parallelism);
        this.phase = Objects.requireNonNull(phase, "phase");
        this.parallelism = parallelism;
    }

    public Phase phase() {
        return this.phase;
    }

    /** The parallelism the event currently asks for; starts at the controller's computed value. */
    public int requestedParallelism() {
        return this.parallelism;
    }

    /**
     * Lowers (or raises, up to the computed value) the parallelism used for this start. The controller clamps
     * the answer when it resolves it; this setter stores exactly what the listener wrote so the listener can
     * read back its own request.
     */
    public void setParallelism(int parallelism) {
        this.parallelism = parallelism;
    }

    /**
     * 否决这次开工（旧版 {@code setFailed}）。{@code reason} 是**可直接展示**的文案（翻译键或明文），
     * 会经方块实体同步到客户端、与失败键行并排显示；{@code null} 表示不展示理由。
     */
    public void setFailed(@Nullable String reason) {
        this.failureReason = reason;
        this.setCanceled(true);
    }

    /** 监听器写入的否决理由；未否决时为 {@code null}。 */
    @Nullable
    public String failureReason() {
        return this.failureReason;
    }

    /**
     * The parallelism the controller should use: the smaller of the computed value and the requested one,
     * never below one. This is the original's {@code Math.min(activeRecipe.getParallelism(), requested)} with
     * the lower bound the recipe setter already applies.
     */
    public int resolvedParallelism(int computed) {
        return Math.max(1, Math.min(computed, this.parallelism));
    }
}
