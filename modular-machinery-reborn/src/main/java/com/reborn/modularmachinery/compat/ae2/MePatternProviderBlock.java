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

/**
 * C4-II（0.96.0）：样板供应器的方块——右键打开样板格窗口（0.95.0 的 BE 只有方块，格子的
 * 放入入口就是这一片）。结构上它只是一个普通方块：端口收集收的是 **BE**（供应器 BE 实现
 * 了物品端口所需的一切），方块本身没有家族/档位属性。
 *
 * <p>0.96.2 修复「新放置的方块也没有 BE」：方块必须实现 **{@link EntityBlock}**——
 * 「有没有方块实体」由这个接口的 {@code newBlockEntity} 决定，{@code Block} 的默认实现返回
 * null。0.96.0 曾有一个 \`newBlockEntity\` 覆写但因缺 import 编译失败被我删掉，当时错判它
 * 冗余——实际缺的是接口本身，删覆写只是把症状一并删了。
 */
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
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
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

    /** 旧版的携带内容掉落：保留样板和模式，不携带 AE 节点或进行中的锁。 */
    @Override
    public java.util.List<net.minecraft.world.item.ItemStack> getDrops(BlockState state,
            net.minecraft.world.level.storage.loot.LootParams.Builder params) {
        var drops = super.getDrops(state, params);
        if (params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
                instanceof MePatternProviderBlockEntity provider) {
            net.minecraft.nbt.ListTag patterns = new net.minecraft.nbt.ListTag();
            for (var stack : provider.patternsView()) patterns.add(stack.save(new net.minecraft.nbt.CompoundTag()));
            for (var drop : drops) if (drop.is(asItem())) {
                var saved = drop.getOrCreateTagElement("BlockEntityTag");
                saved.put("patterns", patterns.copy());
                saved.putByte("work_mode", (byte) provider.workMode().ordinal());
            }
        }
        return drops;
    }
}
