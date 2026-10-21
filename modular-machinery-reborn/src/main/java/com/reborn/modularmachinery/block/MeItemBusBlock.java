package com.reborn.modularmachinery.block;

import com.reborn.modularmachinery.compat.ae2.Ae2ItemBusEntityFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;

import javax.annotation.Nullable;

/**
 * C4（0.73.0）：AE2 ME 物品输入/输出总线方块。
 *
 * <p>和普通仓口一样，一个方块家族只带一个 {@code size} 属性；ME 家族的阶梯只有一项
 * （{@link HatchTier#ME_INPUT} 或 {@link HatchTier#ME_OUTPUT}），只为复用仓口的方块状态、
 * 物品模型与右键打开菜单的路径。方块实体由 {@link Ae2ItemBusEntityFactory} 创建，
 * 所以本类不 import 任何 {@code appeng} 类型。
 *
 * <p>为什么是两个嵌套类而不是一个类加一个静态 {@code SIZE}：
 * {@code createBlockStateDefinition} 在 {@link Block} 构造器里执行，早于实例字段赋值，
 * 所以属性必须是具体类的静态常量；而输入/输出两个 {@link HatchKind} 的
 * {@code sizeProperty()} 是**不同实例**、各自只允许一个值，不能共用一个静态属性。
 */
public abstract class MeItemBusBlock extends MachineHatchBlock {

    protected MeItemBusBlock(HatchKind kind, Properties properties) {
        super(kind, properties);
        if (!kind.isMeBus()) {
            throw new IllegalArgumentException("MeItemBusBlock needs a ME bus kind, got " + kind);
        }
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return Ae2ItemBusEntityFactory.create(pos, state);
    }

    /**
     * 输入总线的配置槽也要掉落。父类只掉内部槽；旧版 {@code BlockMEItemBus} 在
     * {@code breakBlock} 里把配置槽一并掉出，这里补上同一个行为。
     */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof MeBusAccess bus
                && bus.configHandler() != null) {
            ItemStackDrops.pop(level, pos, bus.configHandler());
        }
        super.onRemove(state, level, pos, newState, moving);
    }

    /** 16 个配置槽 + 16 个内部槽的输入总线。 */
    public static final class Input extends MeItemBusBlock {

        public static final EnumProperty<HatchTier> SIZE = HatchKind.ME_ITEM_INPUT.sizeProperty();

        public Input(Properties properties) {
            super(HatchKind.ME_ITEM_INPUT, properties);
            registerDefaultState(defaultBlockState().setValue(SIZE, HatchTier.ME_INPUT));
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

    /** 36 个输出槽的输出总线。 */
    public static final class Output extends MeItemBusBlock {

        public static final EnumProperty<HatchTier> SIZE = HatchKind.ME_ITEM_OUTPUT.sizeProperty();

        public Output(Properties properties) {
            super(HatchKind.ME_ITEM_OUTPUT, properties);
            registerDefaultState(defaultBlockState().setValue(SIZE, HatchTier.ME_OUTPUT));
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
