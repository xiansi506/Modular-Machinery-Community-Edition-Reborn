package com.reborn.modularmachinery.menu;

import com.reborn.modularmachinery.compat.ae2.MePatternProviderBlockEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * C4-II（0.96.0）：样板供应器的容器——9 个样板格 + 玩家背包。样板格是**只读显示**：放入/取出
 * 走窗口侧拦截 + 服务端方法（与总线配置格的 ghost 交互同一套架构，交互细节在屏幕类），
 * 菜单只占位对齐索引。
 */
public final class MePatternProviderMenu extends AbstractContainerMenu {

    public static final String TITLE_KEY = "block.modular_machinery_reborn.me_pattern_provider";
    public static final int PATTERN_SLOTS = MePatternProviderBlockEntity.PATTERNS;
    private static final int PLAYER_SLOTS = 36;

    private final MePatternProviderBlockEntity provider;
    private final Inventory inventory;
    private String lastSnapshot = "";

    /**
     * C4-II（0.97.2）：客户端持有的样板列表镜像——服务端经 MePatternSyncPacket 推送，
     * 构造时先从客户端方块实体（chunk 数据已含样板）初始化一份。窗口渲染与交互只读这份镜像；
     * 服务端权威列表在方块实体上。
     */
    private java.util.List<ItemStack> clientPatterns = new java.util.ArrayList<>();

    /** 0.102.0：工作模式镜像（与样板列表同一张同步包；构造时从 BE 初始化，两端同源）。 */
    private MePatternProviderBlockEntity.WorkMode clientWorkMode = MePatternProviderBlockEntity.WorkMode.DEFAULT;

    public MePatternProviderMenu(int windowId, Inventory inventory, MePatternProviderBlockEntity provider) {
        super(ModMenus.ME_PATTERN_PROVIDER.get(), windowId);
        this.provider = provider;
        this.inventory = inventory;
        // 客户端构造（网络侧）从 chunk 数据镜像初始化；服务端构造读权威列表。两端同源。
        this.clientPatterns = new java.util.ArrayList<>(provider.patternsView());
        this.clientWorkMode = provider.workMode();
        // C4-II（0.96.7）：照旧版 ContainerMEPatternProvider（:47-53）——36 格 4×9、8/28 起，
        // 只收编码样板（限制在包与窗口两端的 isEncodedPattern 里）。
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(new net.minecraft.world.SimpleContainer(PATTERN_SLOTS),
                        col + row * 9, 8 + col * 18, 28 + row * 18) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false;
                    }

                    @Override
                    public boolean mayPickup(Player player) {
                        return false;
                    }
                });
            }
        }
        this.addPlayerSlots(inventory, 114);
    }

    public MePatternProviderMenu(int windowId, Inventory inventory, FriendlyByteBuf data) {
        this(windowId, inventory, (MePatternProviderBlockEntity) inventory.player.level()
                .getBlockEntity(data.readBlockPos()));
    }

    private void addPlayerSlots(Inventory inventory, int top) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(inventory, col + row * 9 + 9, 8 + col * 18, top + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(inventory, col, 8 + col * 18, top + 58));
        }
    }

    public MePatternProviderBlockEntity provider() {
        return this.provider;
    }

    public java.util.List<ItemStack> clientPatterns() {
        return this.clientPatterns;
    }

    public void setClientPatterns(java.util.List<ItemStack> patterns) {
        this.clientPatterns = new java.util.ArrayList<>(patterns);
    }

    public MePatternProviderBlockEntity.WorkMode clientWorkMode() {
        return this.clientWorkMode;
    }

    public void setClientWorkMode(int ordinal) {
        MePatternProviderBlockEntity.WorkMode[] modes = MePatternProviderBlockEntity.WorkMode.values();
        this.clientWorkMode = ordinal >= 0 && ordinal < modes.length ? modes[ordinal] : MePatternProviderBlockEntity.WorkMode.DEFAULT;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // 样板格只读；背包内整理没有意义（9 格全是占位），quickMove 直接空操作。
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return !this.provider.isRemoved()
                && player.level() == this.provider.getLevel()
                && player.distanceToSqr(Vec3.atCenterOf(this.provider.getBlockPos())) <= 64.0;
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (inventory.player instanceof net.minecraft.server.level.ServerPlayer player) {
            String snapshot = provider.patternsView().stream()
                    .map(stack -> stack.save(new net.minecraft.nbt.CompoundTag()).toString())
                    .collect(java.util.stream.Collectors.joining(";")) + provider.workMode().ordinal();
            if (!snapshot.equals(lastSnapshot)) {
                lastSnapshot = snapshot;
                com.reborn.modularmachinery.network.MePatternSyncPacket.sendTo(player, provider.getBlockPos(),
                        provider.patternsView(), provider.workMode().ordinal());
            }
        }
    }
}
