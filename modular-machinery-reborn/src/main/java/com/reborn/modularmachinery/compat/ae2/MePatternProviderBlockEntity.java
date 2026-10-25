package com.reborn.modularmachinery.compat.ae2;

import appeng.api.crafting.IPatternDetails;
import net.minecraftforge.common.extensions.IForgeBlockEntity;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.AECableType;
import com.reborn.modularmachinery.block.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * C4-II 第三片（0.95.0）：AE2 样板供应器——旧版 {@code MEPatternProvider}（792 行）的首片最小闭环。
 *
 * <h2>与旧版的架构差异（有意，写明理由）</h2>
 *
 * <p>旧版把供应器收到的材料先落进自己的「无限子槽」（{@code InfItemFluidHandler}，物品+流体+气体三合一），
 * 再由机器的端口从子槽取——两层中转，材料在供应器里停留。本版不复制这层中转：供应器作为
 * {@link com.reborn.modularmachinery.block.MachineHatchBlockEntity} 的 AE2 附属方块进入结构
 * （端口收集照常把它收成物品输入端口），{@link #pushPattern} 时直接把样板的物品输入
 * <b>塞进所在结构已收集的输入端口</b>（经主机控制器）——材料落地即机器可见，少一层状态、少一次搬运。
 *
 * <p>首片范围：9 个样板格（36 是旧版值，首片取 9——一个供应器 9 样板在合成体系里已可用，扩到 36 是
 * 纯数字改动）、{@code pushPattern}/{@code isBusy}/{@code provideCrafting} 的最小闭环。
 * 后续片已补：专用 GUI（0.96.0 起）、工作模式（0.102.0，五选三——BLOCKING/ISOLATION 有据暂缓）、
 * 流体样板（0.103.0，本版 AE2 样板原生携带流体成分，不需要旧版 AE2FCR 假物品层）。
 * 仍未做：气体样板（等 MekEng 类附属口径，Q9 同级）、影子方块。
 *
 * <p>阻塞与回退：样板输入塞不进任何端口（端口全满/主机未成型）时，{@code pushPattern} 返回 false
 * ——AE2 会把这次合成请求留给别的供应器或挂起，材料<b>不会丢</b>（与旧版语义一致）。
 */
public final class MePatternProviderBlockEntity extends BlockEntity
        implements ICraftingProvider, IActionHost, appeng.api.networking.IInWorldGridNodeHost {

    /** 旧版 MEPatternProvider.PATTERNS（0.96.7 GUI 照旧版后对齐）。 */
    public static final int PATTERNS = 36;

    private static final String TAG_PATTERNS = "patterns";
    private static final String TAG_NODE = "ae_node";
    private static final String TAG_WORK_MODE = "work_mode";
    private static final String TAG_LOCK_ACTIVE = "lock_active";
    private static final String TAG_LOCK_HOST = "lock_host";
    private static final String TAG_ACTIVE_PATTERN = "active_pattern";

    /**
     * 工作模式（0.102.0，旧版 {@code MEPatternProvider.WorkModeSetting} 的对应物；枚举顺序是本版自己的
     * NBT 序，不复刻旧版序——本版存档没有旧序负担）。
     *
     * <p><b>五个模式只移植三个，两个有据暂缓</b>：旧版全部模式都围绕它的「无限子槽」内部缓冲建模，
     * 本版是直推架构（0.95.0 有意分歧，材料直接进机器端口）——
     * <ul>
     *   <li>{@link #DEFAULT}：无门控，照常收单（旧版 DEFAULT）。</li>
     *   <li>{@link #CRAFTING_LOCK_MODE}：收单后忙到所属机器的配方<b>完成</b>（旧版 isBusy =
     *       {@code !machineCompleted}）。解锁信号 = 本版 C2 已交付的 {@code RecipeFinishEvent} /
     *       {@code FactoryRecipeFinishEvent}（由记录的 host 控制器发出才解锁）。</li>
     *   <li>{@link #ENHANCED_BLOCKING_MODE}：忙窗口内只收与进行中样板<b>相同</b>的配方（旧版
     *       acceptsPattern：缓冲非空时拒绝不同样板），同样板可继续收（并行同配方场景）。</li>
     *   <li>BLOCKING_MODE（旧版「阻挡模式：供应器内含有物品时不继续存储」）——「供应器内有物品」
     *       是内部缓冲的观测量，直推架构不可观测，<b>暂缓</b>。</li>
     *   <li>ISOLATION_INPUT（旧版「为每个样板槽位设置独立容器」）——本质需要缓冲层，<b>暂缓</b>。</li>
     * </ul>
     * 与旧版的另一条边界（照旧版，不自造）：合成锁只听<b>完成</b>事件——配方中途失败/取消没有完成
     * 事件，锁会保持到模式切换（旧版同样只听完成）。
     */
    public enum WorkMode {
        DEFAULT,
        CRAFTING_LOCK_MODE,
        ENHANCED_BLOCKING_MODE
    }

    private WorkMode workMode = WorkMode.DEFAULT;
    /** 锁窗口：CRAFTING_LOCK 下 = 未完成；ENHANCED 下 = 上批材料仍在机器里（旧版 handler 非空的对应物）。 */
    private boolean lockActive = false;
    /** ENHANCED 模式记住的进行中样板（照旧版 currentPattern，用 AEItemKey 比较）。 */
    @Nullable
    private AEItemKey activePattern;
    /** 收单时记录的所属结构控制器（完成事件按它匹配解锁）。 */
    @Nullable
    private BlockPos lockHost;

    /** 活跃锁登记表：完成事件按 host 匹配解锁（供应器不是机器端口，收不到机器自己的事件）。 */
    private static final Set<MePatternProviderBlockEntity> ACTIVE_LOCKS = ConcurrentHashMap.newKeySet();
    private static volatile boolean unlockListenerRegistered = false;

    private final IManagedGridNode mainNode;
    /** 已放进格里的样板（物品形态，键用于重扫；逐格保存原始栈以还原 NBT）。 */
    private final List<ItemStack> patterns = new ArrayList<>(java.util.Collections.nCopies(PATTERNS, ItemStack.EMPTY));

    public MePatternProviderBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlocks.ME_PATTERN_PROVIDER_ENTITY.get(), pos, state);
        this.mainNode = appeng.api.networking.GridHelper.createManagedNode(this, new appeng.api.networking.IGridNodeListener<MePatternProviderBlockEntity>() {
            @Override
            public void onSaveChanges(MePatternProviderBlockEntity nodeOwner, IGridNode node) {
                nodeOwner.setChanged();
            }
        })
                .addService(ICraftingProvider.class, this)
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(1.0)
                .setInWorldNode(true)
                .setTagName(TAG_NODE)
                .setExposedOnSides(java.util.EnumSet.allOf(Direction.class))
                .setVisualRepresentation(state.getBlock());
    }

    // ------------------------------------------------------------ AE2 节点生命周期（照总线同款）

    @Override
    public void onLoad() {
        super.onLoad();
        if (this.level != null && !this.level.isClientSide) {
            this.mainNode.create(this.level, this.worldPosition);
            ICraftingProvider.requestUpdate(this.mainNode);
            registerUnlockListenerOnce();
            if (this.lockActive) ACTIVE_LOCKS.add(this);
        }
    }

    @Override
    public void setRemoved() {
        ACTIVE_LOCKS.remove(this);
        this.mainNode.destroy();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        ACTIVE_LOCKS.remove(this);
        this.mainNode.destroy();
        super.onChunkUnloaded();
    }

    @Nullable
    @Override
    public IGridNode getGridNode(Direction dir) {
        return this.mainNode.getNode();
    }

    @Override
    public AECableType getCableConnectionType(Direction dir) {
        return AECableType.SMART;
    }

    @Override
    public IGridNode getActionableNode() {
        return this.mainNode.getNode();
    }

    // ------------------------------------------------------------ ICraftingProvider

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        List<IPatternDetails> details = new ArrayList<>();
        if (this.level == null || this.level.isClientSide) {
            return details;
        }
        var grid = this.mainNode.getGrid();
        var server = grid == null ? null : grid.getCraftingService();
        if (server == null) {
            return details;
        }
        for (ItemStack stack : this.patterns) {
            if (stack.isEmpty()) {
                continue;
            }
            // 样板物品 → 详情：AE2 的解码器单例（签名经 javap 核对——IPatternDetailsDecoder 是
            // 实例接口，AE2 没有静态入口；单例在 appeng.crafting.pattern.AEPatternDecoder.INSTANCE）。
            IPatternDetails detail = appeng.crafting.pattern.AEPatternDecoder.INSTANCE
                    .decodePattern(stack, this.level, false);
            if (detail instanceof appeng.crafting.pattern.AEProcessingPattern) {
                details.add(detail);
            }
        }
        return details;
    }

    @Override
    public boolean isBusy() {
        // 旧版 isBusy（MEPatternProvider.java:306-309）的对应物：合成锁模式下锁窗口未关 = 忙。
        // 默认/强化阻挡不忙——强化阻挡的拒绝发生在 pushPattern 门控里（旧版 acceptsPattern 同款）。
        return this.workMode == WorkMode.CRAFTING_LOCK_MODE && this.lockActive;
    }

    /** 旧版 acceptsPattern（:275-281）的直推对应物：强化阻挡忙窗口内只收进行中样板。 */
    private boolean acceptsPattern(IPatternDetails patternDetails) {
        if (!(patternDetails instanceof appeng.crafting.pattern.AEProcessingPattern) || isBusy()) return false;
        if (this.workMode != WorkMode.ENHANCED_BLOCKING_MODE) {
            return true;
        }
        return !this.lockActive || this.activePattern == null
                || this.activePattern.equals(patternDetails.getDefinition());
    }

    /** 收单成功后的模式记账（旧版 handleNewPattern + machineCompleted 语义）。 */
    private void handlePushAccepted(IPatternDetails patternDetails, com.reborn.modularmachinery.block.MachineControllerBlockEntity host) {
        if (this.workMode == WorkMode.DEFAULT) {
            return;
        }
        this.lockActive = true;
        this.lockHost = host.getBlockPos();
        ACTIVE_LOCKS.add(this);
        if (this.workMode == WorkMode.ENHANCED_BLOCKING_MODE) {
            this.activePattern = patternDetails.getDefinition();
        }
        setChanged();
    }

    private static void registerUnlockListenerOnce() {
        if (MePatternProviderBlockEntity.unlockListenerRegistered) {
            return;
        }
        MePatternProviderBlockEntity.unlockListenerRegistered = true;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                MePatternProviderBlockEntity::onRecipeFinish);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                MePatternProviderBlockEntity::onFactoryRecipeFinish);
    }

    private static void onRecipeFinish(com.reborn.modularmachinery.api.event.RecipeFinishEvent event) {
        unlockByHost(event.controller());
    }

    private static void onFactoryRecipeFinish(com.reborn.modularmachinery.api.event.FactoryRecipeFinishEvent event) {
        unlockByHost(event.controller());
    }

    /** 完成事件 → 按「收单时记录的 host」匹配解锁（旧版 onMachineEvent 只听完成事件）。 */
    private static void unlockByHost(com.reborn.modularmachinery.block.MachineControllerBlockEntity controller) {
        for (MePatternProviderBlockEntity provider : ACTIVE_LOCKS) {
            if (provider.lockHost != null
                    && provider.lockHost.equals(controller.getBlockPos())
                    && provider.level == controller.getLevel()) {
                provider.lockActive = false;
                provider.activePattern = null;
                provider.lockHost = null;
                ACTIVE_LOCKS.remove(provider);
                provider.setChanged();
            }
        }
    }

    /** 服务端：切换工作模式（旧版 setWorkMode 的状态复位语义）。 */
    public void setWorkMode(WorkMode mode) {
        this.workMode = mode;
        // 旧版：非锁定模式 machineCompleted 恒 true；非强化阻挡清进行中样板。换模式一律开新窗口。
        this.lockActive = false;
        this.activePattern = null;
        this.lockHost = null;
        ACTIVE_LOCKS.remove(this);
        setChanged();
    }

    public WorkMode workMode() {
        return this.workMode;
    }

    /** 客户端镜像（MePatternSyncPacket 落点；仅客户端内存写，服务端权威在 setWorkMode）。 */
    public void setClientWorkModeMirror(int ordinal) {
        if (this.level == null || !this.level.isClientSide) {
            return;
        }
        WorkMode[] modes = WorkMode.values();
        this.workMode = ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : WorkMode.DEFAULT;
    }

    @Override
    public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) {
        if (level == null || level.isClientSide || !mainNode.isActive() || !acceptsPattern(details)) return false;
        var host = findHost();
        if (host == null || !PatternInputTransfer.push(host.hatches(), inputs)) return false;
        handlePushAccepted(details, host);
        return true;
    }

    @Nullable
    private com.reborn.modularmachinery.block.MachineControllerBlockEntity findHost() {
        if (level == null) return null;
        com.reborn.modularmachinery.block.MachineControllerBlockEntity result = null;
        for (var controller : com.reborn.modularmachinery.block.MachineControllerBlockEntity.loadedControllers()) {
            if (controller.getLevel() != level || !controller.containsStructurePart(worldPosition)) continue;
            if (result != null) return null;
            result = controller;
        }
        return result;
    }
    // ------------------------------------------------------------ 样板格（首片：NBT + provideCrafting）

    /**
     * 0.97.4：客户端镜像列表的直接写入（仅客户端、纯内存）——MePatternSyncPacket 用它更新
     * 客户端这份列表，使**重开窗口**（菜单从客户端 BE 初始化）也能读到样板。
     * 初始区块镜像由 getUpdateTag 提供；开窗与后续修改另发菜单快照。
     * 服务端权威列表由 setPattern 写入。
     */
    public void setClientPatternsMirror(java.util.List<ItemStack> patterns) {
        if (this.level == null || !this.level.isClientSide) {
            return;
        }
        this.patterns.clear();
        for (int i = 0; i < PATTERNS; i++) this.patterns.add(i < patterns.size() ? patterns.get(i).copy() : ItemStack.EMPTY);
    }

    /** 0.97.3：某格当前样板（真实物品；越界/无 = EMPTY）——取出语义需要它。 */
    public ItemStack getPatternAt(int slot) {
        return slot >= 0 && slot < this.patterns.size() ? this.patterns.get(slot).copy() : ItemStack.EMPTY;
    }

    public List<ItemStack> patternsView() {
        return this.patterns.stream().map(ItemStack::copy).toList();
    }

    /** 放入/移除一格样板（服务端）。首片由调试与后续 GUI 片使用。 */
    public void setPattern(int slot, ItemStack stack) {
        if (slot < 0 || slot >= PATTERNS) throw new IndexOutOfBoundsException("Pattern slot " + slot);
        while (this.patterns.size() <= slot) {
            this.patterns.add(ItemStack.EMPTY);
        }
        this.patterns.set(slot, stack == null ? ItemStack.EMPTY : stack.copy());
        com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.info("[MM-PP] server setPattern slot={} stack={}", slot, stack);
        setChanged();
        if (this.level != null && !this.level.isClientSide) {
            // 0.96.8 修复「放样板没反应」：缺这行 sendBlockUpdated——服务端已写入、AE2 已注册，
            // 但窗口镜像（update tag → 客户端 load）永远不刷新，看起来就是放不进去。
            // 与总线配置格的 afterConfigWrite 同一条规矩：任何写入都要发方块更新。
            this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
            ICraftingProvider.requestUpdate(this.mainNode);
        }
    }

    // ------------------------------------------------------------ NBT

    /**
     * 0.97.0 修复（四环节日志定位）：sendBlockUpdated 之后服务端只为**覆写了本方法**的方块实体
     * 发 ClientboundBlockEntityDataPacket——BlockEntity 默认返回 null（不发）。
     * 缺它 = 样板服务端已写、客户端镜像永不刷新（日志证实：server setPattern 有、client load 无）。
     * 总线的 MeItemBusBlockEntity 同缺，本轮一起修。
     */
    @Override
    @Nullable
    public net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket getUpdatePacket() {
        com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.info("[MM-PP] server getUpdatePacket called (broadcast)");
        return net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = saveWithoutMetadata();
        tag.remove(TAG_NODE);
        return tag;
    }

    /**
     * 0.97.1 诊断：客户端收到数据包的端点日志——与 getUpdatePacket 的服务端日志配对，
     * 把「包没发」与「包发了没处理」两个假设切开。复验完成后与其它 [MM-PP] 日志一起移除。
     */
    @Override
    public void onDataPacket(net.minecraft.network.Connection connection,
            net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket packet) {
        com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.info("[MM-PP] client onDataPacket received");
        // 0.97.2：null tag = vanilla 的「移除方块实体」语义（实测日志捕获过），照 IForgeBlockEntity
        // default 的守卫跳过——我们的镜像改走 MePatternSyncPacket，不再依赖这条路径。
        if (packet.getTag() != null) {
            this.load(packet.getTag());
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (ItemStack stack : this.patterns) {
            list.add(stack.save(new CompoundTag()));
        }
        tag.put(TAG_PATTERNS, list);
        // 0.102.0 工作模式（照旧版 writeProviderHandlerNBT 的「非默认才写」节省习惯——本版字段少，全写）。
        tag.putByte(TAG_WORK_MODE, (byte) this.workMode.ordinal());
        if (this.workMode != WorkMode.DEFAULT) {
            tag.putBoolean(TAG_LOCK_ACTIVE, this.lockActive);
        }
        if (this.workMode == WorkMode.ENHANCED_BLOCKING_MODE && this.activePattern != null) {
            tag.put(TAG_ACTIVE_PATTERN, this.activePattern.toStack().save(new CompoundTag()));
        }
        if (this.lockActive && this.lockHost != null) {
            tag.putLong(TAG_LOCK_HOST, this.lockHost.asLong());
        }
        this.mainNode.saveToNBT(tag);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        this.patterns.clear();
        net.minecraft.nbt.ListTag list = tag.getList(TAG_PATTERNS, net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < PATTERNS; i++) this.patterns.add(i < list.size() ? ItemStack.of(list.getCompound(i)) : ItemStack.EMPTY);
        // 0.102.0：模式字段照旧版 readProviderHandlerNBT 的守卫读法——进行中样板只在强化阻挡下恢复，
        // 两种有锁模式都恢复锁窗口；默认模式不继承旧锁。
        WorkMode[] modes = WorkMode.values();
        int ordinal = tag.contains(TAG_WORK_MODE) ? tag.getByte(TAG_WORK_MODE) : 0;
        this.workMode = ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : WorkMode.DEFAULT;
        this.lockActive = false;
        this.activePattern = null;
        this.lockHost = null;
        if (this.workMode == WorkMode.CRAFTING_LOCK_MODE) {
            this.lockActive = tag.getBoolean(TAG_LOCK_ACTIVE);
        } else if (this.workMode == WorkMode.ENHANCED_BLOCKING_MODE) {
            this.lockActive = tag.getBoolean(TAG_LOCK_ACTIVE);
            if (tag.contains(TAG_ACTIVE_PATTERN)) {
                ItemStack patternStack = ItemStack.of(tag.getCompound(TAG_ACTIVE_PATTERN));
                if (!patternStack.isEmpty()) {
                    this.activePattern = AEItemKey.of(patternStack);
                }
            }
        }
        if (this.lockActive && tag.contains(TAG_LOCK_HOST)) {
            this.lockHost = BlockPos.of(tag.getLong(TAG_LOCK_HOST));
        }
        if (this.level != null && this.level.isClientSide) {
            com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.info("[MM-PP] client load: {} pattern(s) mirrored", this.patterns.size());
        }
        if (this.level == null || !this.level.isClientSide) {
            this.mainNode.loadFromNBT(tag);
        } else {
            // 与总线同一条 0.73.3 规矩：客户端不读节点数据（onLoad 已创建节点）。
            tag.remove(TAG_NODE);
        }
    }
}
