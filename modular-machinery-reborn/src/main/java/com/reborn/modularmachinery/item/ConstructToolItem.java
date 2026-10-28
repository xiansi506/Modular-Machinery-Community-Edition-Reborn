package com.reborn.modularmachinery.item;

import com.mojang.logging.LogUtils;
import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.block.MachineControllerBlock;
import com.reborn.modularmachinery.machine.MachineDirectory;
import com.reborn.modularmachinery.network.SelectionSyncPacket;
import com.reborn.modularmachinery.selection.MachineFragmentWriter;
import com.reborn.modularmachinery.selection.SelectionExport;
import com.reborn.modularmachinery.selection.ServerSelections;
import com.reborn.modularmachinery.selection.StructureSelection;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/** Selects individual blocks and exports a complete, dimension-bound fragment from a controller click. */
public final class ConstructToolItem extends Item {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * From here up, a fragment is worth a warning in the log. Not a cap — the original has none and this refuses
     * nothing — just a line saying how big the file is about to get. 4096 parts is a 64x64 floor.
     */
    private static final int LARGE_FRAGMENT = 4096;

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

        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof MachineControllerBlock) {
            boolean saved = finalizeSelection(serverPlayer, level, pos, state.getValue(MachineControllerBlock.FACING));
            if (saved) ServerSelections.purge(serverPlayer);
            SelectionSyncPacket.sendTo(serverPlayer, saved ? List.of() : ServerSelections.get(serverPlayer).positions());
        } else {
            StructureSelection selection = ServerSelections.toggle(serverPlayer, pos);
            SelectionSyncPacket.sendTo(serverPlayer, selection.positions());
        }
        return InteractionResult.SUCCESS;
    }

    private static boolean finalizeSelection(ServerPlayer player, Level level, BlockPos controller, Direction facing) {
        StructureSelection selection = ServerSelections.get(player);
        if (selection.isEmpty()) {
            player.displayClientMessage(Component.translatable("message.structurebuild.empty"), false);
            return false;
        }
        player.displayClientMessage(Component.translatable("message.structurebuild.confirmrotation", facing.getName()), false);
        String fileName = MachineFragmentWriter.fileNameFor(player.getGameProfile().getName(), LocalDateTime.now());
        try {
            SelectionExport.ExportPlan plan = SelectionExport.plan(level, selection.positions(), controller, facing);
            if (plan.unavailable() > 0) {
                player.displayClientMessage(Component.translatable("message.structurebuild.incomplete", plan.unavailable()), false);
                return false;
            }
            if (plan.parts().size() >= LARGE_FRAGMENT) {
                LOGGER.warn("Large construct-tool export: {} parts", plan.parts().size());
            }
            Path directory = MachineDirectory.directory().resolveSibling("exports");
            Path written = SelectionExport.saveComplete(selection, plan, directory, fileName);
            MinecraftServer server = player.getServer();
            if (server != null && server.isDedicatedServer()) {
                player.displayClientMessage(Component.translatable("message.structurebuild.warndedicated"), false);
            }
            player.displayClientMessage(Component.translatable("message.structurebuild.save", written.getFileName().toString()), false);
            LOGGER.info("Wrote structure fragment to {}. Copy its parts into a machine definition and add registryname.", written);
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("Could not export structure fragment {}. Selection retained.", fileName, exception);
            player.displayClientMessage(Component.translatable("message.structurebuild.fail"), false);
            return false;
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.constructtool.creative").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.constructtool.usage").withStyle(ChatFormatting.GRAY));
    }
}
