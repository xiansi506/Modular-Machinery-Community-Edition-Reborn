package com.reborn.modularmachinery.compat.ae2;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * C4（0.73.0）：把 {@code MeItemBusBlockEntity} 的构造从 {@code MeItemBusBlock} 里隔离出来。
 *
 * <p>{@code MeItemBusBlock} 自己不 import 任何 {@code appeng} 类型；只有这个方法需要
 * {@code MeItemBusBlockEntity}，而它仅在该方块（因此 AE2 已安装）存在时被调用。
 */
public final class Ae2ItemBusEntityFactory {

    private Ae2ItemBusEntityFactory() {
    }

    public static BlockEntity create(BlockPos pos, BlockState state) {
        return new MeItemBusBlockEntity(pos, state);
    }
}
