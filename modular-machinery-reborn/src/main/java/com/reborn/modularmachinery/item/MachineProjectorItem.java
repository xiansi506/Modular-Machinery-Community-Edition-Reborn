package com.reborn.modularmachinery.item;

import com.reborn.modularmachinery.block.MachineControllerBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import javax.annotation.Nullable;
import java.util.List;

/**
 * C6 第一片（0.104.0）：机器投影仪的行为层——0.29.0 以来它一直是 13 行惰性物品
 * （原主当年点名的缓办项 {@code 移植方案-v2.md §9}），本版接上旧版投影仪的核心交互：
 *
 * <p><b>对成型控制器使用 → 开关世界幽灵预览</b>。旧版
 * {@code BlockArrayPreviewRenderHelper.placePreview :98-127} 是「预览面板里点放置按钮、
 * 再点世界里的控制器」两段式；1.20.1 侧的世界预览入口收敛成投影仪本身，
 * 交互减为一段：右键成型控制器 = 开始投影，再右键同一控制器 = 取消
 * （走远 32 格、断线、整机建成同样会自动清除，见 {@code client.preview.WorldGhostPreview}）。
 *
 * <p><b>全部效果都是客户端的</b>，且与 {@code BlueprintItem.use} 同一条纪律：客户端逻辑经
 * {@code DistExecutor.unsafeRunWhenOn} 进 {@code ClientPreview}（虚影状态、渲染与消息都在那边），
 * 公共类永远不直接引用客户端类型——所以客户端分支消费交互后服务器不会被叫醒
 * （SUCCESS 不发使用包），物品不改变任何世界状态；目标不是成型控制器时双方都
 * {@code PASS}，右键行为照常（比如对机器空位放方块）。
 */
public class MachineProjectorItem extends Item {

    public MachineProjectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        return useOn(context);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof MachineControllerBlockEntity controller) || controller.previewMachineId() == null) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.reborn.modularmachinery.client.ClientPreview
                            .toggleGhostPreview(controller, pos, context.getPlayer()));
            return InteractionResult.SUCCESS;
        }
        // 纯客户端效果：服务器不做任何事，也不该吞掉后续交互。
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.modular_machinery_reborn.machine_projector")
                .withStyle(ChatFormatting.GRAY));
    }
}
