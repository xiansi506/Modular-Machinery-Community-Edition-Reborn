package com.reborn.modularmachinery.item;

import com.reborn.modularmachinery.block.MachineControllerBlock;
import net.minecraft.ChatFormatting;
import com.reborn.modularmachinery.selection.ConstructToolService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/** Per-player selection gestures and the server-authorized structure authoring panel. */
public final class ConstructToolItem extends Item {

    public ConstructToolItem(Properties properties) {
        super(properties);
    }

    /**
     * The original's gate ({@code ItemConstructTool.java:54}) as pure arithmetic, so the truth table can be
     * asserted offline: the <b>server</b> side, creative mode, and permission to use commands — all three.
     */
    public static boolean maySelect(boolean clientSide, boolean creative, boolean mayUseCommands) {
        return !clientSide && creative && mayUseCommands;
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return useOn(context);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (!player.isCreative() || !player.hasPermissions(2)) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        if (player.isShiftKeyDown()) { ConstructToolService.open(serverPlayer, null); return InteractionResult.SUCCESS; }
        BlockPos pos = context.getClickedPos();
        if (level.getBlockState(pos).getBlock() instanceof MachineControllerBlock) {
            ConstructToolService.open(serverPlayer, pos);
        } else {
            ConstructToolService.click(serverPlayer, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(Level level, Player player, net.minecraft.world.InteractionHand hand) {
        if (!player.isCreative() || !player.hasPermissions(2) || !player.isShiftKeyDown()) return net.minecraft.world.InteractionResultHolder.pass(player.getItemInHand(hand));
        if (player instanceof ServerPlayer serverPlayer) ConstructToolService.open(serverPlayer, null);
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.constructtool.creative").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.constructtool.usage").withStyle(ChatFormatting.GRAY));
    }
}
