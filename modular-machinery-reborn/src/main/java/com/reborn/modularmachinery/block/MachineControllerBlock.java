package com.reborn.modularmachinery.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;

import javax.annotation.Nullable;

/**
 * A machine controller.
 *
 * <p>Two flavours exist, mirroring the original's two registration modes:
 *
 * <ul>
 *   <li><b>Generic</b> ({@code machine_controller}, no bound machine) — the controller finds out which machine
 *       it is by matching every registered definition against the blocks around it. This corresponds to the
 *       original's {@code blockcontroller} and its {@code only-one-machine-controller} mode.</li>
 *   <li><b>Bound</b> ({@code <machine>_controller}) — the block is tied to one machine, exactly like the
 *       original's per-machine {@code BlockController} which held its {@code DynamicMachine}. Only that
 *       machine's pattern is checked, so the controller cannot become a different machine.</li>
 * </ul>
 *
 * <p>The original registered the bound controllers for every machine it had loaded at startup. 1.20.1 forbids
 * registering blocks later, so the bound set is whatever
 * {@code config/modular_machinery_reborn/machinery/} declares — see {@code 移植方案-v2.md} D8 and D9.
 *
 * <h2>M6e: the factory flavour</h2>
 *
 * <p>{@link #factory()} adds a second, independent axis. The original had a separate
 * {@code BlockFactoryController} whose tile entity was a {@code TileFactoryController}, and registered it for
 * a machine when {@code isHasFactory()} was true ({@code RegistryBlocks.java:422-429}) — in addition to the
 * ordinary controller, unless the machine was {@code factory-only}. So the mode a controller runs in was always
 * a property of the <b>block</b>, and this project keeps it there: {@code machine_controller},
 * {@code factory_controller} and {@code <machine>_factory_controller} are three registrations of one block
 * class, distinguished by these two fields.
 *
 * <p>The other half of {@code has-factory} lives on the machine definition and is checked when a structure
 * forms ({@code MachineControllerBlockEntity}). Both halves have to agree, which is what makes the flag mean
 * something: a plain {@code machine_controller} can never run a factory, and a factory controller can never run
 * a machine whose definition does not ask for one.
 */
public final class MachineControllerBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    @Nullable
    private final ResourceLocation boundMachine;

    private final boolean factory;

    public MachineControllerBlock(Properties properties) {
        this(null, false, properties);
    }

    public MachineControllerBlock(@Nullable ResourceLocation boundMachine, Properties properties) {
        this(boundMachine, false, properties);
    }

    public MachineControllerBlock(@Nullable ResourceLocation boundMachine, boolean factory, Properties properties) {
        super(properties);
        this.boundMachine = boundMachine;
        this.factory = factory;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    /** The machine this controller is tied to, or {@code null} for the generic controller. */
    @Nullable
    public ResourceLocation boundMachine() {
        return this.boundMachine;
    }

    /**
     * Whether this block is a <b>factory</b> controller — the original's {@code BlockFactoryController}. It is
     * the block's half of {@code has-factory}; the machine's definition supplies the other.
     */
    public boolean factory() {
        return this.factory;
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
    @Override public BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override public BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        // The original controller had no working slots, so right-clicking only opened the screen.
        if (level.isClientSide || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.getBlockEntity(pos) instanceof MachineControllerBlockEntity controller) {
            // A factory controller announces a different menu type, so the branch has to be made here — the
            // block is the only object that knows which flavour it is before the packet is written.
            if (this.factory) {
                controller.openFactory((net.minecraft.server.level.ServerPlayer) player);
            } else {
                controller.open(player);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MachineControllerBlockEntity(pos, state); }

    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide
                && level.getBlockEntity(pos) instanceof MachineControllerBlockEntity controller) {
            controller.clearDistributedMachineColor(state.getValue(FACING));
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof MachineControllerBlockEntity controller) {
            controller.clearDistributedMachineColor(state.getValue(FACING));
            // The original did this in BlockController#breakBlock (:178-191); the only inventory the
            // controller has is the blueprint slot.
            ItemStackDrops.pop(level, pos, controller.items());
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    /**
     * A3（0.64.1）：旧版 {@code BlockController.java:259-271} 给控制器接比较器；工厂控制器
     * （{@code BlockFactoryController extends BlockController}）继承同一读数，区分只发生在
     * 方块实体的 {@code isWorking()} 上。所以这里不按 {@link #factory} 分叉。
     */
    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MachineControllerBlockEntity controller
                ? ComparatorLevels.controller(controller.isWorking(), controller.isFormed())
                : 0;
    }

    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == ModBlocks.MACHINE_CONTROLLER_ENTITY.get() ? (l, p, s, be) -> MachineControllerBlockEntity.tick(l, p, s, (MachineControllerBlockEntity) be) : null;
    }
}
