package com.reborn.modularmachinery.machine;

import com.reborn.modularmachinery.recipe.RecipeModifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * One entry of a machine definition's {@code modifiers} array — the original's
 * {@code SingleBlockModifierReplacement}.
 *
 * <p>The original's semantics, kept exactly: a modifier names <b>one position</b> (relative to the controller)
 * and the blocks that may sit there, plus the {@link RecipeModifier}s that apply while they do. When the
 * structure forms, every position whose block actually matches contributes its modifiers to the craft. That is
 * the machinery behind the alloy furnace's built-in trick — put a vent above the controller and its item output
 * is doubled ({@code alloy_furnace.json}).
 *
 * <p>这描述的是旧版三个 replacement 类里的 {@code SingleBlockModifierReplacement}：一个坐标、一格方块。
 * 另外两个的现状：
 *
 * <ul>
 *   <li>{@code MultiBlockModifierReplacement} 做的是「另一段结构是否还在」，<strong>不是</strong>替换动态段；
 *       0.72.0（C9）把它移植为 {@link MachineMultiBlockModifier}，见 {@code multi-block-modifiers}。</li>
 *   <li>{@code DynamicModifierReplacement} 是同一件事的动态机器形态；本版没有对应的动态 modifier 入口，
 *       仍不移植。</li>
 * </ul>
 */
public record MachineModifier(BlockPos offset, List<BlockMatcher> accepted, List<RecipeModifier> modifiers,
                              String description) {

    /**
     * Whether the block actually placed at this modifier's position is one the definition accepts.
     *
     * <p>The original rotated both the offset and each accepted block descriptor counter-clockwise once per
     * quarter turn of the controller ({@code TileMultiblockMachineController#updateModifiers}), so a definition
     * is written once, for the north-facing controller, and works in all four orientations.
     */
    public boolean matches(Level level, BlockPos rotatedOffset, BlockState state) {
        for (BlockMatcher matcher : this.accepted) {
            if (matcher.matches(state)) {
                return true;
            }
        }
        return false;
    }

    /** The offset, rotated one quarter turn counter-clockwise per step — the original's {@code (z, y, -x)}. */
    public BlockPos rotatedOffset(int steps) {
        BlockPos pos = this.offset;
        for (int i = 0; i < steps; i++) {
            pos = new BlockPos(pos.getZ(), pos.getY(), -pos.getX());
        }
        return pos;
    }
}
