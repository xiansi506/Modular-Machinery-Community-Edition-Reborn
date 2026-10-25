package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.compat.ae2.MePatternProviderBlockEntity;
import com.reborn.modularmachinery.menu.MePatternProviderMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * C4-II（0.96.0）：样板供应器的窗口——3×3 样板格 + 背包。样板格是**真实交互**（与总线配置格的
 * ghost 不同）：左键放手上的样板（一次一份）、右键取回该格样板；渲染直接读方块实体的样板列表
 * （服务端权威，经普通菜单同步之外的自定义包不需要——放入/取出的即时反馈由点击后重新拉取的
 * \*方块实体数据\*完成：\`setPattern\` 发方块更新，窗口随 chunk 数据刷新）。
 *
 * <p>背景是**原版**发射器面板（container/dispenser.png，176×166）——所有者拍板「GUI 要用原版的」。
 */
public final class MePatternProviderScreen extends AbstractContainerScreen<MePatternProviderMenu> {

    private static final ResourceLocation PANEL =
            new ResourceLocation("modular_machinery_reborn", "textures/gui/mepatternprovider.png");

    private static final int GRID_X = 8;
    private static final int GRID_Y = 28;
    private static final int COLS = 9;
    private static final int ROWS = 4;

    // 0.102.0 工作模式按钮（旧版 GuiMEPatternProvider workModeSetting 的对应物）：
    // 屏幕位 (233,7) 与旧版一致（旧版同一行 (215,7) 是「清空物品」按钮——它依赖供应器内部
    // 缓冲，直推架构无物可清，不在本片）；图标与状态框取自旧贴图同区域（贴图原样搬运过）。
    private static final int MODE_BTN_X = 233;
    private static final int MODE_BTN_Y = 7;
    /** 各模式图标在 mepatternprovider.png 上的区域（照旧版 ButtonElements 的 addElement 坐标）。 */
    private static final int[][] MODE_ICON_UV = {
            {140, 196}, // DEFAULT
            {176, 196}, // CRAFTING_LOCK_MODE
            {122, 196}, // ENHANCED_BLOCKING_MODE
    };
    private static final int FRAME_HOVER_U = 212;
    private static final int FRAME_DOWN_U = 230;
    private static final int FRAME_V = 196;

    public MePatternProviderScreen(MePatternProviderMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 256;
        this.imageHeight = 196;
        this.inventoryLabelY = 104;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderPatterns(graphics, mouseX, mouseY);
        this.renderModeButton(graphics, mouseX, mouseY);
    }

    /** 0.102.0：模式按钮绘制 + 悬停 tooltip（照旧版 tooltip 结构：总述 + 各模式行，当前行加「当前启用」标）。 */
    private void renderModeButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.leftPos + MODE_BTN_X;
        int y = this.topPos + MODE_BTN_Y;
        boolean hovered = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
        MePatternProviderBlockEntity.WorkMode mode = this.menu.clientWorkMode();
        int[] uv = MODE_ICON_UV[mode.ordinal()];
        graphics.blit(PANEL, x, y, 16, 16, uv[0], uv[1], 16, 16, 256, 256);
        if (hovered) {
            // 旧版 ButtonElements 的悬停/按下状态框；按下态省略（点击瞬间即发包）。
            graphics.blit(PANEL, x, y, 16, 16, FRAME_HOVER_U, FRAME_V, 16, 16, 256, 256);
            graphics.renderTooltip(this.font, modeTooltip(), java.util.Optional.empty(), mouseX, mouseY);
        }
    }

    private java.util.List<Component> modeTooltip() {
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.translatable("gui.modular_machinery_reborn.mepatternprovider.work_mode.desc"));
        MePatternProviderBlockEntity.WorkMode current = this.menu.clientWorkMode();
        String currentMark = Component
                .translatable("gui.modular_machinery_reborn.mepatternprovider.current").getString();
        lines.add(Component.translatable("gui.modular_machinery_reborn.mepatternprovider.default.desc")
                .append(current == MePatternProviderBlockEntity.WorkMode.DEFAULT
                        ? Component.literal(currentMark) : Component.empty()));
        lines.add(Component.translatable("gui.modular_machinery_reborn.mepatternprovider.crafting_lock_mode.desc")
                .append(current == MePatternProviderBlockEntity.WorkMode.CRAFTING_LOCK_MODE
                        ? Component.literal(currentMark) : Component.empty()));
        lines.add(Component.translatable("gui.modular_machinery_reborn.mepatternprovider.enhanced_blocking_mode.desc")
                .append(current == MePatternProviderBlockEntity.WorkMode.ENHANCED_BLOCKING_MODE
                        ? Component.literal(currentMark) : Component.empty()));
        lines.add(Component.translatable("gui.modular_machinery_reborn.mepatternprovider.not_ported.desc"));
        return lines;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(PANEL, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight);
    }

    private void renderPatterns(GuiGraphics graphics, int mouseX, int mouseY) {
        var provider = this.menu.provider();
        if (provider == null) {
            return;
        }
        // C4-II（0.97.2）：读菜单镜像（服务端经 MePatternSyncPacket 推送），不再依赖 BE 镜像。
        var patterns = this.menu.clientPatterns();
        int hovered = -1;
        for (int slot = 0; slot < MePatternProviderMenu.PATTERN_SLOTS; slot++) {
            int x = this.leftPos + GRID_X + (slot % COLS) * 18;
            int y = this.topPos + GRID_Y + (slot / COLS) * 18;
            if (slot < patterns.size() && !patterns.get(slot).isEmpty()) {
                graphics.renderItem(patterns.get(slot), x, y);
                graphics.renderItemDecorations(this.font, patterns.get(slot), x, y);
            }
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                hovered = slot;
            }
        }
        if (hovered >= 0 && hovered < patterns.size() && !patterns.get(hovered).isEmpty()) {
            graphics.renderTooltip(this.font, patterns.get(hovered), mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 0.102.0：模式按钮（旧版点击 = 按元素序推进一档并发包；本版三个模式循环
        // 默认 → 合成锁定 → 强化阻挡）。
        int btnRelX = (int) mouseX - this.leftPos - MODE_BTN_X;
        int btnRelY = (int) mouseY - this.topPos - MODE_BTN_Y;
        if (button == 0 && btnRelX >= 0 && btnRelY >= 0 && btnRelX < 16 && btnRelY < 16) {
            var provider = this.menu.provider();
            if (provider != null) {
                MePatternProviderBlockEntity.WorkMode[] modes = MePatternProviderBlockEntity.WorkMode.values();
                int next = (this.menu.clientWorkMode().ordinal() + 1) % modes.length;
                com.reborn.modularmachinery.network.MePatternProviderActionPacket.sendSetMode(
                        provider.getBlockPos(), next);
            }
            return true;
        }
        int relX = (int) mouseX - this.leftPos - GRID_X;
        int relY = (int) mouseY - this.topPos - GRID_Y;
        if (relX >= 0 && relY >= 0 && relX < COLS * 18 && relY < ROWS * 18) {
            int col = relX / 18;
            int row = relY / 18;
            if (relX - col * 18 < 16 && relY - row * 18 < 16) {
                // 0.96.9 修复：网格改 4×9 后这行还是 3 列时代——第 2 行起格号全错。
                int slot = col + row * COLS;
                com.reborn.modularmachinery.ModularMachineryReborn.LOGGER.info("[MM-PP] client click: slot={} button={} carried={}", slot, button, this.menu.getCarried());
                ItemStack carried = this.menu.getCarried();
                var provider = this.menu.provider();
                if (provider != null) {
                    if (!carried.isEmpty()) {
                        // 左键：把手上的样板**真实放入**格子（服务端 swap：原格内容回到手上）。
                        com.reborn.modularmachinery.network.MePatternPacket.sendSet(
                                provider.getBlockPos(), slot, carried);
                    } else if (button == 1) {
                        // 右键空手：把格里的样板**取回**手上。
                        com.reborn.modularmachinery.network.MePatternPacket.sendClear(
                                provider.getBlockPos(), slot);
                    }
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 安静未用的覆写占位：父类要求，无需逻辑。 */
    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderLabels(graphics, mouseX, mouseY);
    }
}
