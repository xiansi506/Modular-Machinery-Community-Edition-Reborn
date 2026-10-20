package com.reborn.modularmachinery.block;

import com.reborn.modularmachinery.config.HatchConfig;
import com.reborn.modularmachinery.menu.HatchGroupMenu;
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
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One block per hatch family, with the tier carried by a {@code size} blockstate property.
 *
 * <p>This reproduces the original mod's layout: it registered a single block per family
 * ({@code blockinputbus}, {@code blockenergyoutputhatch}, …) and exposed the seven or eight tiers as
 * item variants of that one block, instead of registering a separate block per tier.
 *
 * <p>The three nested classes exist because {@link Block#createBlockStateDefinition} runs inside the
 * {@code Block} constructor, before any instance field is assigned — so the size property has to be a
 * static constant of a concrete class rather than a constructor argument.
 *
 * <p>Each hatch has its own screen, reached by right-clicking: item hatches show the tier's slots, fluid and
 * energy hatches show the tank or buffer as a bar. The window is always 176×166.
 */
public abstract class MachineHatchBlock extends Block implements EntityBlock {

    private final HatchKind kind;

    protected MachineHatchBlock(HatchKind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public HatchKind kind() {
        return this.kind;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MachineHatchBlockEntity(pos, state);
    }

    /**
     * 仓口自己的服务端 tick：物品输入抽、物品输出推、能量输出推 FE（A1，0.63.9）。
     *
     * <p>旧版这三个方块各自在 {@code update()} 里做事；1.20.1 的等价物是这里返回的 ticker。
     * 客户端不需要它：仓口的库存变化已经随菜单/方块实体同步，自动交换只该在服务端发生。
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof MachineHatchBlockEntity hatch) {
                hatch.serverTick();
            }
        };
    }

    /** The tier this block currently carries. */
    public HatchTier tier(BlockState state) {
        return state.getValue(sizeProperty());
    }

    /** The family's {@code size} property. Safe to call during construction in the nested classes. */
    public abstract EnumProperty<HatchTier> sizeProperty();

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        // The original opened the hatch's own screen on right-click; the screen is what shows the tier's slots
        // or the tank's contents, so there is no separate chat message any more.
        //
        // A2 adds the original's other gesture: sneak + both hands empty on an input hatch opens the group
        // configuration instead (EventHandler.java:165-181 — same three conditions, same "empty main and off
        // hand" rule). Everything else keeps the normal screen, including a sneak-click while holding an item.
        if (!level.isClientSide && hand == InteractionHand.MAIN_HAND) {
            if (level.getBlockEntity(pos) instanceof MachineHatchBlockEntity hatch) {
                boolean wantsGroupConfig = player.isShiftKeyDown()
                        && player.getMainHandItem().isEmpty()
                        && player.getOffhandItem().isEmpty()
                        && hatch.canGroupInput();
                if (wantsGroupConfig) {
                    net.minecraftforge.network.NetworkHooks.openScreen(
                            (net.minecraft.server.level.ServerPlayer) player,
                            new SimpleMenuProvider((id, inventory, p) -> new HatchGroupMenu(id, inventory, hatch),
                                    Component.translatable(HatchGroupMenu.TITLE_KEY)),
                            pos);
                } else {
                    net.minecraftforge.network.NetworkHooks.openScreen(
                            (net.minecraft.server.level.ServerPlayer) player, hatch, pos);
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Spills the item slots when the hatch block really goes away.
     *
     * <p>The original did this on any removal ({@code BlockBus.java:50-63}); {@code onRemove} keeps that
     * meaning instead of covering only a player's pickaxe. Fluid and energy hatches have no item handler, so
     * they drop the block and nothing else, exactly like the original.
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof MachineHatchBlockEntity hatch) {
            ItemStackDrops.pop(level, pos, hatch.itemHandler());
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    /**
     * A3（0.64.1）：比较器读数。旧版三个仓口方块都覆写了 {@code hasComparatorInputOverride}
     * （{@code BlockBus.java:136-143}、{@code BlockFluidHatch.java:137-145}、
     * {@code BlockEnergyHatch.java:104-112}），读数交给 {@code RedstoneHelper.getRedstoneLevel}。
     * 这里保留“永远有读数”的契约，具体数值按家族分派。
     */
    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof MachineHatchBlockEntity hatch)) {
            return 0;
        }
        return switch (hatch.kind().family()) {
            case ITEM -> ComparatorLevels.items(hatch.itemHandler());
            case FLUID -> ComparatorLevels.tank(hatch.fluidTank());
            case ENERGY -> ComparatorLevels.energy(hatch.energyStorage());
        };
    }

    /** The capacity a tier grants for a family, e.g. {@code 32 slots} or {@code 32000 mB}. */
    public static Component capacityComponent(HatchKind kind, HatchTier tier) {
        return switch (kind.family()) {
            case ITEM -> Component.translatable("tooltip.modular_machinery_reborn.hatch.slots", tier.itemSlots());
            case FLUID -> Component.translatable("tooltip.modular_machinery_reborn.hatch.tank",
                    HatchConfig.fluidHatchCapacity(tier));
            // The original printed these two with `MiscUtils.formatDecimal` (a thousands separator) in the branch
            // that is always taken — `BlockEnergyInputHatch.java:55-56` — and left the unit hard-coded as `FE`
            // there, using the configurable unit only in its IC2 and GregTech branches. The unit is configurable
            // here instead, because those two branches do not exist on 1.20.1 and a hard-coded `FE` sitting beside
            // a configurable unit is a contradiction the player can see. The grouping is the original's.
            case ENERGY -> Component.translatable("tooltip.modular_machinery_reborn.hatch.energy",
                    com.reborn.modularmachinery.config.DisplayNumbers.grouped(HatchConfig.energyHatchCapacity(tier)),
                    com.reborn.modularmachinery.config.DisplayNumbers.grouped(HatchConfig.energyHatchTransfer(tier)),
                    com.reborn.modularmachinery.config.ModConfig.energyDisplay().labelComponent());
        };
    }

    private static EnumProperty<HatchTier> propertyFor(List<HatchTier> ladder) {
        return EnumProperty.create("size", HatchTier.class, ladder.toArray(new HatchTier[0]));
    }

    /** Hatches whose ladder is {@code ItemBusSize}: seven tiers, no ultimate. */
    public static final class ItemPort extends MachineHatchBlock {
        public static final EnumProperty<HatchTier> SIZE = propertyFor(HatchTier.ITEM_LADDER);

        public ItemPort(HatchKind kind, Properties properties) {
            super(kind, properties);
            registerDefaultState(defaultBlockState().setValue(SIZE, HatchTier.ITEM_LADDER.get(0)));
        }

        @Override
        public EnumProperty<HatchTier> sizeProperty() {
            return SIZE;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(SIZE);
        }
    }

    /** Hatches whose ladder is {@code FluidHatchSize}: the item ladder plus vacuum. */
    public static final class FluidPort extends MachineHatchBlock {
        public static final EnumProperty<HatchTier> SIZE = propertyFor(HatchTier.FLUID_LADDER);

        public FluidPort(HatchKind kind, Properties properties) {
            super(kind, properties);
            registerDefaultState(defaultBlockState().setValue(SIZE, HatchTier.FLUID_LADDER.get(0)));
        }

        @Override
        public EnumProperty<HatchTier> sizeProperty() {
            return SIZE;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(SIZE);
        }
    }

    /** Hatches whose ladder is {@code EnergyHatchData}: the item ladder plus ultimate. */
    public static final class EnergyPort extends MachineHatchBlock {
        public static final EnumProperty<HatchTier> SIZE = propertyFor(HatchTier.ENERGY_LADDER);

        public EnergyPort(HatchKind kind, Properties properties) {
            super(kind, properties);
            registerDefaultState(defaultBlockState().setValue(SIZE, HatchTier.ENERGY_LADDER.get(0)));
        }

        @Override
        public EnumProperty<HatchTier> sizeProperty() {
            return SIZE;
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(SIZE);
        }
    }
}
