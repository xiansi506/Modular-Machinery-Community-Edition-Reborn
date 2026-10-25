package com.reborn.modularmachinery.common.assembly;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A8（0.106.0）：一次自动搭建的执行器——旧版
 * {@code ink/ikx/mmce/common/assembly/MachineAssembly}（529 行）的对应物，server 侧。
 *
 * <p>旧版的节奏原样保留：
 *
 * <ul>
 *   <li><b>启动时模拟扣减</b>（旧版 {@code checkAllItems :167-192} + {@code buildIngredients(false)
 *       :353-365}）：背包副本上逐格选候选，模拟通过（或 {@code needAllBlocks=false} 允许部分）才注册；
 *       副本扣减确定了每格用哪个候选，之后逐 tick 真实扣。</li>
 *   <li><b>每 tick 放一个方块</b>（旧版 {@code assembly(true) :376-385} → {@code assemblyItemBlocks
 *       :410-465}）：不可替换跳过、缺料报告跳过、{@code setBlock}、放置事件取消回滚、
 *       音效、液体格消耗背包流体容器并保留返回容器。</li>
 *   <li>创造模式 {@code assemblyCreative :387-408}：一次全放第一候选，不消耗。</li>
 * </ul>
 *
 * <p>与旧版的差异（都有据）：候选是 {@link BlockState} 而非 ItemStack 元组（见
 * {@link AssemblyIngredient}）；TE NBT 不存在；放置事件用 1.20.1 的
 * {@code BlockEvent.EntityPlaceEvent}（ForgeEventFactory.onBlockPlace 触发，旧版
 * {@code BlockEvent.PlaceEvent} 已并族）；缺料报告第一片是聊天消息而不是客户端展示包。
 */
public final class MachineAssembly {

    private final BlockPos ctrlPos;
    private final UUID playerId;
    private final List<AssemblyIngredient> remaining;
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;

    private MachineAssembly(BlockPos ctrlPos, UUID playerId, List<AssemblyIngredient> remaining) {
        this.ctrlPos = ctrlPos;
        this.playerId = playerId;
        this.remaining = new ArrayList<>(remaining);
    }

    public BlockPos ctrlPos() {
        return this.ctrlPos;
    }

    public UUID playerId() {
        return this.playerId;
    }

    public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension() { return dimension; }

    public boolean isCompleted() {
        return this.remaining.isEmpty();
    }

    /**
     * 启动一次自动搭建：模拟扣减选出每格候选，全有 → 注册逐 tick 执行；不全有时按
     * {@code needAllBlocks} 决定部分开始还是报缺料（旧版 {@code assemblyBefore :53-101} 的骨架）。
     * 创造模式直接一次放完（旧版 {@code assemblyCreative}），不进队列。
     */
    public static void start(ServerPlayer player, BlockPos ctrlPos, List<AssemblyIngredient> scanned) {
        List<AssemblyIngredient> working = new ArrayList<>(scanned);
        boolean creative = player.getAbilities().instabuild;

        // 旧版 checkAllItems + buildIngredients(false)：副本上逐格选候选并扣减。
        // 选不出候选的格子就是缺料清单。
        List<AssemblyIngredient> unresolved = creative ? List.of()
                : bindCandidates(player.getInventory(), working);

        if (!unresolved.isEmpty()) {
            reportMissing(player, unresolved);
            if (com.reborn.modularmachinery.config.ModConfig.assemblyNeedAllBlocks()) {
                return;
            }
            player.displayClientMessage(Component.translatable(
                    "message.modular_machinery_reborn.assembly.partial_assembly")
                    .withStyle(ChatFormatting.YELLOW), false);
            working.removeIf(unresolved::contains);
        }

        MachineAssembly assembly = new MachineAssembly(ctrlPos, player.getUUID(), List.copyOf(working));
        assembly.dimension = player.level().dimension();
        if (creative) {
            placeAllCreative(assembly, player);
            return;
        }
        MachineAssemblyManager.add(assembly);
    }

    /**
     * 每 {@code tickBlock} tick 调一次：放一个方块。返回 {@code true} 表示全部放完。
     * 控制器失效由消费方检查（旧版 {@code isControllerInvalid}）。
     */
    public boolean tickPlace(ServerLevel level, ServerPlayer player) {
        if (this.remaining.isEmpty()) {
            return true;
        }
        Iterator<AssemblyIngredient> iterator = this.remaining.iterator();
        AssemblyIngredient ingredient = iterator.next();
        BlockPos realPos = this.ctrlPos.offset(ingredient.relPos());

        if (!replaceable(level, realPos, player)) {
            // 旧版 replaceCheck 失败：提示 + 跳过该格（不移除整个搭建）。
            iterator.remove();
            return this.remaining.isEmpty();
        }

        BlockState state = ingredient.candidates().get(0);
        boolean creative = player.getAbilities().instabuild;
        if (!creative && !canConsume(player, ingredient)) {
            player.displayClientMessage(Component.translatable(
                    "message.modular_machinery_reborn.assembly.missing", describePos(realPos))
                    .withStyle(ChatFormatting.RED), false);
            iterator.remove();
            return this.remaining.isEmpty();
        }

        BlockSnapshot original = BlockSnapshot.create(level.dimension(), level, realPos);
        if (!level.setBlock(realPos, state, 3)) {
            iterator.remove();
            return this.remaining.isEmpty();
        }
        boolean cancelled = ForgeEventFactory.onBlockPlace(player,
                original,
                net.minecraft.core.Direction.UP);
        if (cancelled || (!creative && !consumeFor(player, ingredient))) {
            original.restore(true, false);
        } else {
            level.playSound(null, realPos,
                    ingredient.fluidBlock() ? SoundEvents.BUCKET_EMPTY : SoundEvents.STONE_PLACE,
                    SoundSource.BLOCKS, 1.0F, 1.0F);
        }

        iterator.remove();
        return this.remaining.isEmpty();
    }

    /** 创造模式：全部格子一次放下（旧版 {@code assemblyCreative}），不消耗、逐格跳过不可替换。 */
    private static void placeAllCreative(MachineAssembly assembly, ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        while (!assembly.isCompleted()) assembly.tickPlace(level, player);
        level.playSound(null, assembly.ctrlPos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        player.displayClientMessage(Component.translatable(
                "message.modular_machinery_reborn.assembly.success").withStyle(ChatFormatting.GREEN), false);
    }

    /** 旧版 {@code replaceCheck :313-335} 的 1.20.1 形态：越界/不可替换都提示并跳过。 */
    private static boolean replaceable(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (!level.isInWorldBounds(pos) || !level.getWorldBorder().isWithinBounds(pos)
                || !level.mayInteract(player, pos)) {
            player.displayClientMessage(Component.translatable(
                    "message.modular_machinery_reborn.assembly.too_high", describePos(pos))
                    .withStyle(ChatFormatting.RED), false);
            player.displayClientMessage(Component.translatable(
                    "message.modular_machinery_reborn.assembly.skipped", describePos(pos)), false);
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.canBeReplaced() || state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
            return true;
        }
        player.displayClientMessage(Component.translatable(
                "message.modular_machinery_reborn.assembly.cannot_replace", describePos(pos))
                .withStyle(ChatFormatting.RED), false);
        player.displayClientMessage(Component.translatable(
                "message.modular_machinery_reborn.assembly.skipped", describePos(pos)), false);
        return false;
    }

    /**
     * 背包副本上逐格选候选并扣减（旧版 {@code buildItemIngredients/buildFluidIngredients} 的合并）。
     * 返回选不出候选的格子（缺料清单）；选出的格子保留在 {@code working} 里等逐 tick 真扣。
     */
    private static List<AssemblyIngredient> bindCandidates(net.minecraft.world.entity.player.Inventory inventory,
                                                           List<AssemblyIngredient> working) {
        List<AssemblyIngredient> unresolved = new ArrayList<>();
        List<ItemStack> inventoryCopy = new ArrayList<>();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
                inventoryCopy.add(stack.copy());
            }
        }
        for (int index = 0; index < working.size(); index++) {
            AssemblyIngredient ingredient = working.get(index);
            AssemblyIngredient selected = null;
            for (BlockState state : ingredient.candidates()) {
                AssemblyIngredient candidate = new AssemblyIngredient(ingredient.relPos(), List.of(state),
                        state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock);
                boolean available = candidate.fluidBlock()
                        ? consumeFluid(inventoryCopy, fluidOf(candidate), IFluidHandler.FluidAction.EXECUTE)
                        : consumeItem(inventoryCopy, candidate);
                if (available) { selected = candidate; break; }
            }
            if (selected == null) unresolved.add(ingredient);
            else working.set(index, selected);
        }
        return unresolved;
    }

    /** 旧版 {@code consumeInventoryItem :279-287}：匹配即扣（作用于传入列表）。 */
    private static boolean consumeItem(List<ItemStack> inventory, AssemblyIngredient ingredient) {
        ItemStack required = asItemStack(ingredient.candidates().get(0));
        if (required.isEmpty()) {
            return false;
        }
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack inv = inventory.get(i);
            if (ItemStack.isSameItemSameTags(inv, required) && inv.getCount() >= required.getCount()) {
                inv.shrink(required.getCount());
                if (inv.isEmpty()) {
                    inventory.remove(i);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 在单个容器副本上先模拟整份，再执行并保存返回容器。
     * 候选规划传入背包副本；真实结算也先改副本，整份成功才写回玩家背包。
     */
    private static boolean consumeFluid(List<ItemStack> inventory, FluidStack required,
                                        IFluidHandler.FluidAction action) {
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack inv = inventory.get(i);
            // 操作单个容器的副本，先模拟整份；成功后写回 getContainer（桶会变成空桶）。
            LazyOptional<IFluidHandlerItem> capability = FluidUtil.getFluidHandler(inv.copyWithCount(1));
            IFluidHandlerItem handler = capability.resolve().orElse(null);
            if (handler == null) {
                continue;
            }
            FluidStack drained = handler.drain(required.copy(), IFluidHandler.FluidAction.SIMULATE);
            if (drained != null && drained.getAmount() >= required.getAmount()
                    && drained.getFluid() == required.getFluid()) {
                if (action.execute()) {
                    if (!required.isFluidStackIdentical(handler.drain(required.copy(), action))) return false;
                    ItemStack rest = inv.copy();
                    rest.shrink(1);
                    ItemStack container = handler.getContainer();
                    inventory.set(i, rest.isEmpty() ? container : rest);
                    if (!rest.isEmpty() && !container.isEmpty()) inventory.add(container);
                }
                return true;
            }
        }
        return false;
    }

    /** 逐 tick 的真实消耗（旧版 {@code assemblyItemBlocks :424} / 流体分支 {@code :481}）。 */
    private static boolean consumeFor(ServerPlayer player, AssemblyIngredient ingredient) {
        if (ingredient.fluidBlock()) {
            List<ItemStack> live = new ArrayList<>();
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                live.add(player.getInventory().getItem(i).copy());
            }
            if (!consumeFluid(live, fluidOf(ingredient), IFluidHandler.FluidAction.EXECUTE)) return false;
            int slots = player.getInventory().getContainerSize();
            for (int i = 0; i < slots; i++) player.getInventory().setItem(i, live.get(i));
            for (int i = slots; i < live.size(); i++) player.getInventory().placeItemBackInInventory(live.get(i));
            player.getInventory().setChanged();
            return true;
        }
        InventoryBridge bridge = new InventoryBridge(player);
        return consumeItem(bridge.backing, ingredient);
    }

    private static boolean canConsume(ServerPlayer player, AssemblyIngredient ingredient) {
        List<ItemStack> copy = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) copy.add(player.getInventory().getItem(i).copy());
        return ingredient.fluidBlock() ? consumeFluid(copy, fluidOf(ingredient), IFluidHandler.FluidAction.SIMULATE)
                : consumeItem(copy, ingredient);
    }

    /** 缺料聊天报告（旧版 {@code PktAssemblyReport} 的第一片简化形态）。 */
    private static void reportMissing(ServerPlayer player, List<AssemblyIngredient> unresolved) {
        player.displayClientMessage(Component.translatable(
                "message.modular_machinery_reborn.assembly.missing_report_header")
                .withStyle(ChatFormatting.RED), false);
        Map<String, Integer> itemCounts = new LinkedHashMap<>();
        Map<String, Integer> fluidCounts = new LinkedHashMap<>();
        for (AssemblyIngredient ingredient : unresolved) {
            BlockState state = ingredient.candidates().get(0);
            if (ingredient.fluidBlock()) {
                ResourceLocation fluid = net.minecraftforge.registries.ForgeRegistries.FLUIDS
                        .getKey(state.getFluidState().getType());
                fluidCounts.merge(fluid == null ? "unknown" : fluid.toString(), 1, Integer::sum);
            } else {
                ResourceLocation item = net.minecraftforge.registries.ForgeRegistries.ITEMS
                        .getKey(state.getBlock().asItem());
                itemCounts.merge(item == null ? state.getBlock().toString() : item.toString(),
                        1, Integer::sum);
            }
        }
        itemCounts.forEach((name, count) -> player.displayClientMessage(Component.literal(
                "  " + name + " ×" + count).withStyle(ChatFormatting.GRAY), false));
        fluidCounts.forEach((fluid, count) -> player.displayClientMessage(Component.literal(
                "  " + fluid + " ×" + count + " (1000 mB each)").withStyle(ChatFormatting.GRAY), false));
    }

    private static FluidStack fluidOf(AssemblyIngredient ingredient) {
        // 液体格按 1000mB 一格计（一个方块体），与旧版 BlockLiquid 的 1000mB 粒度一致。
        return new FluidStack(ingredient.candidates().get(0).getFluidState().getType(), 1000);
    }

    private static ItemStack asItemStack(BlockState state) {
        if (state.getBlock().asItem() == net.minecraft.world.item.Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(state.getBlock());
    }

    private static String describePos(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /**
     * 物品消耗面向的极简视图：把玩家背包包成一个可变列表，让 {@link #consumeItem} 对物品与
     * 流体容器共用同一条扣减循环（真背包直接生效，不需要再拷贝）。
     */
    private static final class InventoryBridge {
        final List<ItemStack> backing = new ArrayList<>();

        InventoryBridge(ServerPlayer player) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                this.backing.add(player.getInventory().getItem(i));
            }
        }
    }
}
