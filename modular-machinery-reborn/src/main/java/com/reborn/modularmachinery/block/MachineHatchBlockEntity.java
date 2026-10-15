package com.reborn.modularmachinery.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;

/**
 * Storage behind one hatch block.
 *
 * <p>This is where M3 puts the port capability layer. In the original mod the <b>hatches</b> owned the storage
 * and exposed the Forge capabilities, while the controller exposed none — a machine's inventory, tank and
 * energy buffer were simply the sum of its hatches. That is what this reproduces, and it is why an item bus of
 * tier {@code ludicrous} really does hold 32 slots and a {@code vacuum} fluid hatch really does hold 32,000 mB.
 *
 * <p>One block entity type serves all six families: the family and tier are read from the blockstate, which is
 * possible because each family is a single block with a {@code size} property. Capacity comes from
 * {@link HatchTier}, which carries the original {@code ItemBusSize} / {@code FluidHatchSize} /
 * {@code EnergyHatchData} numbers.
 */
public class MachineHatchBlockEntity extends BlockEntity implements net.minecraft.world.MenuProvider {

    private static final String TAG_ITEMS = "items";
    private static final String TAG_TANK = "tank";
    private static final String TAG_ENERGY = "energy";

    private final HatchKind kind;
    private final HatchTier tier;

    @Nullable private final ItemStackHandler items;
    @Nullable private final FluidTank tank;
    @Nullable private final EnergyStorage energy;

    private final LazyOptional<ItemStackHandler> itemCapability;
    private final LazyOptional<FluidTank> fluidCapability;
    private final LazyOptional<EnergyStorage> energyCapability;

    public MachineHatchBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.HATCH_ENTITY.get(), pos, state);
        this.kind = state.getBlock() instanceof MachineHatchBlock hatch ? hatch.kind() : HatchKind.ITEM_INPUT;
        this.tier = state.getValue(this.kind.sizeProperty());

        // Locals first: javac's definite-assignment analysis does not treat a statement switch that covers
        // every enum constant as exhaustive when the selector is a method call, so it cannot prove the final
        // fields are assigned after the switch.
        ItemStackHandler itemStorage = null;
        FluidTank fluidStorage = null;
        EnergyStorage energyStorage = null;

        switch (this.kind.family()) {
            case ITEM -> {
                itemStorage = new ItemStackHandler(Math.max(1, this.tier.itemSlots())) {
                    @Override protected void onContentsChanged(int slot) { onStorageChanged(false); }
                };
            }
            case FLUID -> {
                fluidStorage = new FluidTankWithGasPriority(Math.max(1, this.tier.fluidCapacity()));
            }
            case ENERGY -> {
                int capacity = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, this.tier.energyCapacity()));
                int transfer = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, this.tier.energyTransfer()));
                energyStorage = new EnergyStorage(capacity, transfer, transfer, 0) {
                    @Override public int receiveEnergy(int maxReceive, boolean simulate) {
                        int received = super.receiveEnergy(maxReceive, simulate);
                        if (received > 0 && !simulate) { onStorageChanged(false); }
                        return received;
                    }
                    @Override public int extractEnergy(int maxExtract, boolean simulate) {
                        int extracted = super.extractEnergy(maxExtract, simulate);
                        if (extracted > 0 && !simulate) { onStorageChanged(false); }
                        return extracted;
                    }
                };
            }
        }

        this.items = itemStorage;
        this.tank = fluidStorage;
        this.energy = energyStorage;
        this.itemCapability = itemStorage == null ? LazyOptional.empty() : LazyOptional.of(() -> this.items);
        this.fluidCapability = fluidStorage == null ? LazyOptional.empty() : LazyOptional.of(() -> this.tank);
        this.energyCapability = energyStorage == null ? LazyOptional.empty() : LazyOptional.of(() -> this.energy);
    }

    public HatchKind kind() {
        return this.kind;
    }

    public HatchTier tier() {
        return this.tier;
    }

    /** The item storage, or {@code null} for fluid and energy hatches. */
    @Nullable
    public ItemStackHandler itemHandler() {
        return this.items;
    }

    /** The fluid tank, or {@code null} for item and energy hatches. */
    @Nullable
    public FluidTank fluidTank() {
        return this.tank;
    }

    /** The energy buffer, or {@code null} for item and fluid hatches. */
    @Nullable
    public EnergyStorage energyStorage() {
        return this.energy;
    }

    /** Human-readable capacity of this hatch, used by the tooltip and by hand-over notes. */
    public String capacityDescription() {
        return switch (this.kind.family()) {
            case ITEM -> this.tier.itemSlots() + " slots";
            case FLUID -> this.tier.fluidCapacity() + " mB";
            case ENERGY -> this.tier.energyCapacity() + " FE, " + this.tier.energyTransfer() + " FE/t";
        };
    }

    private void onStorageChanged(boolean syncToClient) {
        setChanged();
        if (syncToClient && this.level != null && !this.level.isClientSide) {
            // Only the fluid needs a block update: the client draws the fluid's own texture. Item slots travel
            // with the menu and the energy numbers travel in the menu's container data, so both would be waste.
            this.level.sendBlockUpdated(this.worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------- menu data

    /** Client-side mirror of the four numbers the hatch screen shows. */
    private int clientEnergyStored;
    private int clientEnergyCapacity;
    private int clientFluidAmount;
    private int clientFluidCapacity;
    /**
     * 波 5.3：气体那一半的量与容量，同样走菜单的数据槽。
     *
     * <p>为什么要另开两个槽而不是复用流体那对：流体与气体共用同一个罐的**空间**，但它们是
     * **两笔不同的内容**（一个罐要么装流体要么装气体）。界面要把两者都画出来，就必须分别拿到数字。
     */
    private int clientGasAmount;
    private int clientGasCapacity;

    /**
     * One of the four values the hatch screen displays, in the order the menu's container data uses them.
     *
     * <p>The server answers from the live storage; the client answers from the mirrored values, which the menu
     * writes through {@link #setMenuValue}. This is the same arrangement vanilla furnaces use, and it exists
     * because a container data slot is the only place those numbers can live on the client.
     */
    public int menuValue(int index) {
        if (this.level == null || this.level.isClientSide) {
            return switch (index) {
                case 0 -> this.clientEnergyStored;
                case 1 -> this.clientEnergyCapacity;
                case 2 -> this.clientFluidAmount;
                case 3 -> this.clientFluidCapacity;
                case 4 -> this.clientGasAmount;
                case 5 -> this.clientGasCapacity;
                default -> 0;
            };
        }
        return switch (index) {
            case 0 -> this.energy == null ? 0 : this.energy.getEnergyStored();
            case 1 -> this.energy == null ? 0 : this.energy.getMaxEnergyStored();
            case 2 -> this.tank == null ? 0 : this.tank.getFluidAmount();
            case 3 -> this.tank == null ? 0 : this.tank.getCapacity();
            // 气体的两个数字从**兼容层**取，而且走的是通用入口 {@code ModCompat.namedValues}——
            // 本类**不认识任何模块**（它每个世界都会被加载，包括没装那个模组的进程）。
            // 没装 Mekanism 时这个 map 是空的，这两个槽就是 0，界面上那一截不画。
            case 4 -> gasMenuValue(true);
            case 5 -> gasMenuValue(false);
            default -> 0;
        };
    }

    /**
     * 流体仓的罐：**一个仓里只能有一种东西**（波 5.3）。
     *
     * <p>旧版 {@code HybridGasTank extends HybridTank} 的语义是「{@code setFluid} 与 {@code setGas} 互相覆盖
     * 同一个罐」，所以管道本来就**拿不到**一个「既有液体又有气体」的罐——它只有一个罐。
     * 本版把气体那一半放在兼容层里，于是必须**显式**做到这件事，否则流体管道会往同一个罐里灌液体，
     * 两者同时存在。
     *
     * <p>做法：罐里已经有气体时，流体那一侧**看起来是空的**——容量照报（管道据此知道这里能装流体），
     * 但已有的流体读成 0、灌入一律收 0。等气体被抽空，流体那一半自动恢复可用。
     * 挡住的只是**读与灌**，不是那道墙：没有任何状态被丢掉，也就没有「看不见的液体」。
     */
    private final class FluidTankWithGasPriority extends FluidTank {

        FluidTankWithGasPriority(int capacity) {
            super(capacity);
        }

        @Override protected void onContentsChanged() {
            // The screen renders the fluid's own texture, so the client needs to know which fluid it is.
            onStorageChanged(true);
        }

        @Override public net.minecraftforge.fluids.FluidStack getFluid() {
            return gasBlocksFluid() ? net.minecraftforge.fluids.FluidStack.EMPTY : super.getFluid();
        }

        @Override public int getFluidAmount() {
            return gasBlocksFluid() ? 0 : super.getFluidAmount();
        }

        @Override public int fill(net.minecraftforge.fluids.FluidStack resource,
                                  net.minecraftforge.fluids.capability.IFluidHandler.FluidAction action) {
            // 灌不进去：气体占着这个罐。收 0 是「一点都没收」，管道据此就知道这里装不下。
            return gasBlocksFluid() ? 0 : super.fill(resource, action);
        }

        /**
         * 这个罐里**真的**有没有流体，绕过上面那层挡住逻辑。
         *
         * <p>气体罐要问这个问题来决定「灌气体前要不要先清掉流体」——而被挡住时
         * {@code getFluidAmount()} 恒答 0，从外面问不出来。
         */
        int realFluidAmount() {
            return super.getFluidAmount();
        }

        /**
         * **真的**把这个罐清空，绕过挡住逻辑。
         *
         * <p>为什么必须是真的清空而不是「读起来是空的」：后者会在气体被抽空之后把旧液体露出来，
         * 而那正是「一个仓只能有一种东西」的反面。
         */
        void clearForGas() {
            if (super.getFluidAmount() > 0) {
                super.setFluid(net.minecraftforge.fluids.FluidStack.EMPTY);
            }
        }
    }

    /**
     * 这个仓里现在是否有气体（于是流体那一侧要看起来是空的）。
     *
     * <p>走**通用入口** {@code ModCompat.namedValues}——本类不认识任何模块，也不该认识：
     * 它每个世界都会被加载，包括没装那个模组的进程。没装 Mekanism 时这个 map 是空的，本方法恒为 false。
     */
    private boolean gasBlocksFluid() {
        String amount = com.reborn.modularmachinery.compat.ModCompat.namedValues(this).get("gas.amount");
        if (amount == null) {
            return false;
        }
        try {
            return Long.parseLong(amount) > 0L;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /**
     * **真的**把这个仓的流体那一半清空（绕过「有气体时看起来是空的」那层挡住逻辑）。
     *
     * <p>给兼容层用：要往这个罐里灌气体，就得先把里面的流体清掉——「一个仓只能有一种东西」。
     * 它必须是**真的**清空，而不是让它读起来是空的：后者会在气体被抽空之后把旧液体露出来。
     */
    public void clearFluidForGas() {
        if (this.tank instanceof FluidTankWithGasPriority hybrid) {
            hybrid.clearForGas();
        }
    }

    /** 这个仓里**真的**有多少流体（绕过「有气体时看起来是空的」）。给兼容层判断要不要先清罐。 */
    public int realFluidAmount() {
        return this.tank instanceof FluidTankWithGasPriority hybrid ? hybrid.realFluidAmount() : 0;
    }

    /** 气体那一半的量或容量，交给兼容层回答；没有气体那一半时是 0。 */
    private int gasMenuValue(boolean amount) {
        String raw = com.reborn.modularmachinery.compat.ModCompat.namedValues(this)
                .get(amount ? "gas.amount" : "gas.capacity");
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    public void setMenuValue(int index, int value) {
        switch (index) {
            case 0 -> this.clientEnergyStored = value;
            case 1 -> this.clientEnergyCapacity = value;
            case 2 -> this.clientFluidAmount = value;
            case 3 -> this.clientFluidCapacity = value;
            case 4 -> this.clientGasAmount = value;
            case 5 -> this.clientGasCapacity = value;
            default -> { }
        }
    }

    /**
     * The saved state doubles as the update tag, so the client knows which fluid a fluid hatch holds and can
     * draw its texture. Only fluid hatches ask for that update.
     */
    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getUpdatePacket() {
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    // ------------------------------------------------------- screen

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable(this.kind.itemTranslationKey(this.tier));
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id,
            net.minecraft.world.entity.player.Inventory inventory, net.minecraft.world.entity.player.Player player) {
        return new com.reborn.modularmachinery.menu.MachineHatchMenu(id, inventory, this);
    }

    /**
     * 兼容模块的能力从这里进来（波 5.3）。
     *
     * <p><b>本类不认识任何一种兼容能力</b>——它只知道 {@code ModCompat.mapPort} 这个通用入口，
     * 而那个入口背后的映射器由模块自己注册。这是「一个模块加一种端口不必改主模组」的落点，
     * 也是没装那个模组时本类**不会碰到它的任何类型**的保证（详见
     * {@code compat/BlockEntityPortMapper} 的类注释）。
     *
     * <p>映射器在 {@code FMLCommonSetupEvent} 里登记，而能力查询在那之后（玩家进世界才有方块实体），
     * 所以这里是「顺带问一句」，不需要自己判断时机。
     */
    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        // 记下每一次查询的能力名：这是「管道到底问了没有、问的是不是那个能力」的唯一低成分证据。
        // 记在静态集合里而不打印，因为这条路径每 tick 每个方块都会走，打印会把日志淹掉；
        // 要看的时候用 /mm-diagnose。
        com.reborn.modularmachinery.compat.ModCompat.recordCapability(capability);
        if (capability == ForgeCapabilities.ITEM_HANDLER) {
            return this.itemCapability.cast();
        }
        if (capability == ForgeCapabilities.FLUID_HANDLER) {
            return this.fluidCapability.cast();
        }
        if (capability == ForgeCapabilities.ENERGY) {
            return this.energyCapability.cast();
        }
        LazyOptional<?> compat = com.reborn.modularmachinery.compat.ModCompat.mapPort(capability, this);
        if (compat != null) {
            return compat.cast();
        }
        return super.getCapability(capability, side);
    }

    /**
     * 兼容层的内容变了：存盘，并（流体仓需要）同步给客户端。
     *
     * <p>{@code public} 而不是包内可见：它由 {@code compat} 包里的气体罐作为回调调用，
     * 而那个包不是本包。
     */
    public void markStorageChanged() {
        // 气体让客户端的方块实体数据过期（与流体同一条理由：界面要画出装的是什么），所以 syncToClient = true。
        onStorageChanged(true);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        this.itemCapability.invalidate();
        this.fluidCapability.invalidate();
        this.energyCapability.invalidate();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.items != null) {
            tag.put(TAG_ITEMS, this.items.serializeNBT());
        }
        if (this.tank != null) {
            tag.put(TAG_TANK, this.tank.writeToNBT(new CompoundTag()));
        }
        if (this.energy != null) {
            tag.putInt(TAG_ENERGY, this.energy.getEnergyStored());
        }
        // 兼容层的内容（例如流体仓上的气体）由各模块自己序列化，本类不知道里面是什么。
        com.reborn.modularmachinery.compat.ModCompat.savePorts(tag, this);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (this.items != null && tag.contains(TAG_ITEMS)) {
            this.items.deserializeNBT(tag.getCompound(TAG_ITEMS));
        }
        if (this.tank != null && tag.contains(TAG_TANK)) {
            this.tank.readFromNBT(tag.getCompound(TAG_TANK));
        }
        if (this.energy != null && tag.contains(TAG_ENERGY)) {
            // EnergyStorage has no direct setter; fill it back up to the stored amount.
            this.energy.receiveEnergy(tag.getInt(TAG_ENERGY), false);
        }
        com.reborn.modularmachinery.compat.ModCompat.loadPorts(tag, this);
    }
}
