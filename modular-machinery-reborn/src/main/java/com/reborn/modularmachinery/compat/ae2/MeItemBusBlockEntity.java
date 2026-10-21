package com.reborn.modularmachinery.compat.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.GridFlags;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageHelper;
import appeng.api.util.AECableType;
import com.reborn.modularmachinery.block.HatchKind;
import com.reborn.modularmachinery.block.MeBusAccess;
import com.reborn.modularmachinery.block.MachineHatchBlockEntity;
import com.reborn.modularmachinery.block.MeItemBusLogic;
import com.reborn.modularmachinery.block.ModBlocks;
import com.reborn.modularmachinery.menu.MeItemBusMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * C4（0.73.0）：AE2 ME 物品输入/输出总线的方块实体。
 *
 * <p>这里只做三件事：挂一个 AE2 受管节点、在服务端按固定间隔把内部槽与网络之间搬物品、
 * 把输入总线的 16 个配置槽存进 NBT。搬运的判定全部在
 * {@link MeItemBusLogic} 里（那个类不 import AE2，因此可以在离线复现器里直接测）。
 *
 * <p>旧版是 {@code github.kasuminova.mmce.common.tile.MEItemInputBus} /
 * {@code MEItemOutputBus}，基于 {@code AENetworkProxy}。1.20.1 的等价物是
 * {@link IManagedGridNode}：节点只在服务端创建，客户端只负责显示菜单里的同步值。
 */
public final class MeItemBusBlockEntity extends MachineHatchBlockEntity
        implements MeBusAccess, IInWorldGridNodeHost, IActionHost {

    private static final String TAG_CONFIG = "configInventory";
    private static final String TAG_NODE = "ae_node";

    private final IManagedGridNode mainNode;

    /** 输入总线才有；输出总线为 {@code null}。 */
    @Nullable
    private final ItemStackHandler config;

    private final int[] failures;
    private int ticks;

    public MeItemBusBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.ME_ITEM_BUS_ENTITY.get(), pos, state);

        ItemStackHandler itemHandler = itemHandler();
        this.failures = new int[itemHandler == null ? 0 : itemHandler.getSlots()];

        if (kind() == HatchKind.ME_ITEM_INPUT) {
            this.config = new ItemStackHandler(16) {
                @Override
                protected void onContentsChanged(int slot) {
                    MeItemBusBlockEntity.this.setChanged();
                    Arrays.fill(MeItemBusBlockEntity.this.failures, 0);
                }
            };
        } else {
            this.config = null;
        }

        this.mainNode = GridHelper.createManagedNode(this, new IGridNodeListener<MeItemBusBlockEntity>() {
            @Override
            public void onSaveChanges(MeItemBusBlockEntity nodeOwner, IGridNode node) {
                nodeOwner.setChanged();
            }
        })
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(1.0)
                .setInWorldNode(true)
                .setTagName(TAG_NODE)
                .setExposedOnSides(EnumSet.allOf(Direction.class))
                .setVisualRepresentation(state.getBlock());
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null) {
            this.mainNode.create(this.level, this.worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        this.mainNode.destroy();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        this.mainNode.destroy();
        super.onChunkUnloaded();
    }

    @Nullable
    @Override
    public ItemStackHandler configHandler() {
        return this.config;
    }

    @Override
    public boolean meOnline() {
        return this.mainNode.isActive() && this.mainNode.isOnline();
    }

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return this.mainNode.getNode();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        // 旧版 AENetworkProxy 用 AECableType.SMART；默认的 GLASS 会让线缆连接表现不同。
        return AECableType.SMART;
    }

    @Override
    public IGridNode getActionableNode() {
        return this.mainNode.getNode();
    }

    @Override
    public void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (this.config != null) {
            tag.put(TAG_CONFIG, this.config.serializeNBT());
        }
        this.mainNode.saveToNBT(tag);
    }

    /**
     * 0.73.3：chunk update tag 只需要同步配置/库存，不需要 AE2 节点数据。
     *
     * <p>父类的 {@code getUpdateTag()} 会走 {@code saveWithoutMetadata()}，进而调用本类的
     * {@code saveAdditional}，把 {@code mainNode.saveToNBT} 写进去。客户端节点由 {@code onLoad()}
     * 创建，这份数据本来就不会被读取（见 {@link #load(CompoundTag)}），不发可以减少每个总线的包体。
     */
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.remove(TAG_NODE);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (this.config != null && tag.contains(TAG_CONFIG)) {
            this.config.deserializeNBT(tag.getCompound(TAG_CONFIG));
        }
        // 0.73.3: 客户端收到 chunk update tag 时，BlockEntity.onLoad 已经 create 了 node；
        // AE2 的 ManagedGridNode.loadFromNBT 在「节点已初始化」时会抛
        // IllegalStateException: The node has already been initialized. Initialization data cannot be changed anymore.
        // 这个异常会让 LevelChunk 处理 chunk 包时中断，表现为整个区块不可见。
        // 节点数据只在服务端恢复；客户端节点由 onLoad 创建，不需要读 NBT。
        if (this.level == null || !this.level.isClientSide) {
            this.mainNode.loadFromNBT(tag);
        }
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id,
            net.minecraft.world.entity.player.Inventory inventory,
            net.minecraft.world.entity.player.Player player) {
        return new MeItemBusMenu(id, inventory, this);
    }

    @Override
    public void serverTick() {
        if (this.level == null || this.level.isClientSide) {
            return;
        }
        this.ticks++;
        if (!this.mainNode.isActive() || !this.mainNode.isOnline()) {
            return;
        }

        boolean input = kind() == HatchKind.ME_ITEM_INPUT;
        int interval = input ? 10 : 5;
        if (this.ticks % interval != 0) {
            return;
        }
        boolean fullCheck = this.ticks % 100 == 0;
        ItemStackHandler inventory = itemHandler();
        if (inventory == null) {
            return;
        }

        boolean moved;
        if (input && this.config != null) {
            moved = MeItemBusLogic.tickInput(inventory, this.config, new NetworkStorage(), this.failures, fullCheck);
        } else {
            moved = MeItemBusLogic.tickOutput(inventory, new NetworkStorage(), this.failures, fullCheck);
        }
        if (moved) {
            this.setChanged();
        }
    }

    /**
     * {@link MeItemBusLogic.Storage} 的 AE2 适配器。
     *
     * <p>每次调用都从 {@code mainNode} 重新取 grid：节点可能在运行中掉线/重连，
     * 把 grid 缓存进字段会让「掉线后仍在往旧网络塞物品」成为可能。
     */
    private final class NetworkStorage implements MeItemBusLogic.Storage {

        @Override
        public int insert(ItemStack stack) {
            if (stack.isEmpty()) {
                return 0;
            }
            IGrid grid = mainNode.getGrid();
            if (grid == null) {
                return 0;
            }
            AEItemKey key = AEItemKey.of(stack);
            if (key == null) {
                return 0;
            }
            long moved = StorageHelper.poweredInsert(
                    grid.getEnergyService(),
                    grid.getStorageService().getInventory(),
                    key,
                    stack.getCount(),
                    IActionSource.ofMachine(MeItemBusBlockEntity.this),
                    Actionable.MODULATE);
            return clamp(moved, stack.getCount());
        }

        @Override
        public int extract(ItemStack prototype, int amount) {
            if (prototype.isEmpty() || amount <= 0) {
                return 0;
            }
            IGrid grid = mainNode.getGrid();
            if (grid == null) {
                return 0;
            }
            AEItemKey key = AEItemKey.of(prototype);
            if (key == null) {
                return 0;
            }
            long moved = StorageHelper.poweredExtraction(
                    grid.getEnergyService(),
                    grid.getStorageService().getInventory(),
                    key,
                    amount,
                    IActionSource.ofMachine(MeItemBusBlockEntity.this),
                    Actionable.MODULATE);
            return clamp(moved, amount);
        }
    }

    private static int clamp(long moved, int requested) {
        if (moved <= 0) {
            return 0;
        }
        return (int) Math.min(moved, requested);
    }
}
