package com.reborn.modularmachinery.compat.ae2;

import com.reborn.modularmachinery.block.ModBlocks;
import com.reborn.modularmachinery.menu.MePatternProviderMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/** Opens the provider menu, records memory-card links and preserves storage in normal block drops. */
public class MePatternProviderBlock extends Block implements EntityBlock {

    public static final String TITLE_KEY = "block.modular_machinery_reborn.me_pattern_provider";

    public MePatternProviderBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return ModBlocks.ME_PATTERN_PROVIDER_ENTITY.get().create(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                            net.minecraft.world.entity.LivingEntity placer, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof MePatternProviderBlockEntity provider) {
            provider.setNetworkOwner(player);
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        var held = player.getItemInHand(hand);
        if (held.getItem() instanceof appeng.api.implementations.items.IMemoryCard card && player.isShiftKeyDown()) {
            if (!level.isClientSide && player.mayBuild()) {
                var data = new net.minecraft.nbt.CompoundTag(); data.putLong("position", pos.asLong());
                data.putString("dimension", level.dimension().location().toString());
                card.setMemoryCardContents(held, MePatternMirrorBlock.CARD_KEY, data);
                player.displayClientMessage(Component.translatable("gui.modular_machinery_reborn.mepatternprovider.saved"), false);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        // 0.96.1 修复「窗口打不开」：两个原因。
        // ① 返回值：1.20.1 的方块交互必须 sidedSuccess——客户端 SUCCESS（触发重手摆动 + 把交互
        //    报告给服务端），服务端才跑 use；写死 SUCCESS 常量在部分路径上不触发服务端半边。
        // ② 0.95.0 期间放置的供应器方块是裸 Block（那时没有 newBlockEntity），世界里**没有方块实体**；
        //    0.96.0 换类只影响新放置的。旧方块要给一句明确反馈，不能静默。
        if (hand == InteractionHand.MAIN_HAND
                && level.getBlockEntity(pos) instanceof MePatternProviderBlockEntity provider) {
            if (!level.isClientSide) {
                net.minecraftforge.network.NetworkHooks.openScreen(
                        (net.minecraft.server.level.ServerPlayer) player,
                        new SimpleMenuProvider(
                                (id, inventory, p) -> new MePatternProviderMenu(id, inventory, provider),
                                Component.translatable(TITLE_KEY)),
                        pos);
                com.reborn.modularmachinery.network.MePatternSyncPacket.sendTo(
                        (net.minecraft.server.level.ServerPlayer) player, pos,
                        provider.patternsView(), provider.workMode().ordinal());
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide) {
            player.sendSystemMessage(Component.translatable(
                    "screen.modular_machinery_reborn.me_pattern_provider.no_block_entity"));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public <T extends BlockEntity> net.minecraft.world.level.block.entity.BlockEntityTicker<T> getTicker(Level level, BlockState state, net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        return level.isClientSide || type != ModBlocks.ME_PATTERN_PROVIDER_ENTITY.get() ? null : (world, pos, blockState, entity) -> ((MePatternProviderBlockEntity)entity).serverTick();
    }
    /** 普通掉落保留全部仓储及等待退回状态，不复制 AE 网络节点。 */
    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state,
            net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        var drops = new java.util.ArrayList<>(super.getDrops(state, params));
        if (params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
                instanceof MePatternProviderBlockEntity provider) {
            for (var drop : drops) if (drop.is(asItem())) drop.getOrCreateTag().put("BlockEntityTag", provider.dropContents());
        }
        return drops;
    }
}
