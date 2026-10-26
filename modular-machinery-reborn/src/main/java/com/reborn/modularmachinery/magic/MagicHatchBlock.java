package com.reborn.modularmachinery.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import javax.annotation.Nullable;

public final class MagicHatchBlock extends BaseEntityBlock {
    public final MagicFamily family;
    public final boolean input;
    public MagicHatchBlock(MagicFamily family, boolean input, Properties properties) { super(properties); this.family = family; this.input = input; }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MagicHatchBlockEntity(pos, state); }
    @Override @Nullable public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide || MagicBlocks.TYPE == null ? null : createTickerHelper(type, MagicBlocks.TYPE.get(), (world, pos, blockState, hatch) -> hatch.serverTick());
    }
    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof MagicHatchBlockEntity hatch)
            NetworkHooks.openScreen(server, hatch, pos);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock()) && level.getBlockEntity(pos) instanceof MagicHatchBlockEntity hatch) {
            for (int i = 0; i < hatch.orbs().getSlots(); i++) Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), hatch.orbs().getStackInSlot(i).copy());
        }
        super.onRemove(state, level, pos, next, moving);
    }
}
