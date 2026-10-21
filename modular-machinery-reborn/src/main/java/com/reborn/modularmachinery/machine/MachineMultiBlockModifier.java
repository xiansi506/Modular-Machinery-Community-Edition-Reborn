package com.reborn.modularmachinery.machine;

import com.reborn.modularmachinery.recipe.RecipeModifier;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/**
 * 一条 `multi-block-modifiers`：额外一段多块结构条件，条件全部命中时给配方贡献 {@link RecipeModifier}。
 *
 * <p><b>名字不是「替换结构」。</b>旧版的 {@code MultiBlockModifierReplacement} 实际做的事是
 * `BlockArray.matches(world, controller, false, null)`：检查另一段结构是否还在，命中后把它的 modifier
 * 交给控制器。它<strong>不改基座匹配、不替换方块、不隐藏方块</strong>。本类保留这个真实语义。
 *
 * <p><b>与旧版的一处有意差异：没有名字，也不去重。</b>旧版把命中的多块 modifier 按
 * {@code getModifierName()} 放进一张表，所以后写的多块条件会覆盖同名的单块条件
 * （{@code TileMultiblockMachineController.java:874-881}）。本版的单块 {@link MachineModifier} 本来就没有
 * 名字，没有可覆盖的键；因此这里把每个命中的条件<strong>全部追加</strong>，声明顺序保持不变。
 * 需要互斥的作者应当把条件写成互不重叠的结构，而不是依赖覆盖。
 *
 * <p><b>旋转只转位置与 selector-tag，不转方块状态。</b>{@link MachinePattern#rotated(int)} 与
 * {@link MachinePattern#rotateYCounterClockwise()} 只改坐标和标签；旧版还会对每个
 * {@code BlockInformation} 调用 {@code copyRotateYCCW()} 旋转方块状态。段里放朝向敏感的方块时两者会不同；
 * 这与现有动态段的行为一致，已记入 {@code docs/已知限制.md}。
 */
public record MachineMultiBlockModifier(MachinePattern pattern, List<RecipeModifier> modifiers,
                                       String description) {

    public MachineMultiBlockModifier {
        Objects.requireNonNull(pattern, "pattern");
        modifiers = List.copyOf(modifiers);
        description = description == null ? "" : description;
    }

    /**
     * 把条件按控制器的朝向转过去，再检查整段结构。
     *
     * @param controller    控制器位置
     * @param rotationSteps 相对 NORTH 的逆时针四分之一圈数
     * @param at            「这个绝对坐标上是否有允许的方块」；调用者负责区块加载判断
     */
    public boolean matches(BlockPos controller, int rotationSteps,
                           BiPredicate<BlockPos, List<BlockMatcher>> at) {
        return this.pattern.rotated(rotationSteps).matches(controller, at);
    }

    /** 返回命中的条件，保持声明顺序；不修改传入列表。 */
    public static List<MachineMultiBlockModifier> active(List<MachineMultiBlockModifier> declared,
                                                         BlockPos controller, int rotationSteps,
                                                         BiPredicate<BlockPos, List<BlockMatcher>> at) {
        if (declared.isEmpty()) {
            return List.of();
        }
        List<MachineMultiBlockModifier> found = new ArrayList<>();
        for (MachineMultiBlockModifier modifier : declared) {
            if (modifier.matches(controller, rotationSteps, at)) {
                found.add(modifier);
            }
        }
        return List.copyOf(found);
    }
}
