package com.reborn.modularmachinery.compat.ae2;

import appeng.api.implementations.items.*;
import com.reborn.modularmachinery.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class MePatternMirrorBlock extends Block implements EntityBlock {
    public static final String CARD_KEY="block.modular_machinery_reborn.me_pattern_provider";
    public MePatternMirrorBlock(Properties properties){super(properties);}
    public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return ModBlocks.ME_PATTERN_MIRROR_ENTITY.get().create(pos,state);}
    @Override public InteractionResult use(BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        if(hand!=InteractionHand.MAIN_HAND)return InteractionResult.PASS;
        if(!level.isClientSide&&level.getBlockEntity(pos) instanceof MePatternMirrorBlockEntity mirror){
            var held=player.getItemInHand(hand);
            if(held.getItem() instanceof IMemoryCard card&&!player.isShiftKeyDown()){
                if(!player.mayBuild()||!CARD_KEY.equals(card.getSettingsName(held))||!mirror.bind(card.getData(held)))card.notifyUser(player,MemoryCardMessages.INVALID_MACHINE);
                else player.displayClientMessage(Component.translatable("gui.modular_machinery_reborn.mepatternprovider.linked",mirror.linkDescription()),false);
            }else player.displayClientMessage(Component.translatable("gui.modular_machinery_reborn.mepatternprovider.linked",mirror.linkDescription()),false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
