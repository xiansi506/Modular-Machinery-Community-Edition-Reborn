package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.block.HatchKind;
import com.reborn.modularmachinery.menu.MachineHatchMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;

import java.util.List;
import java.util.Locale;

/**
 * The hatch screens, reproduced from the original {@code GuiContainerItemBus}, {@code GuiContainerFluidHatch}
 * and {@code GuiContainerEnergyHatch}.
 *
 * <p>Every hatch is a 176×166 window with the player's inventory at (8, 84) and the hotbar at (8, 142). Item
 * hatches swap in {@code inventory_<tier>.png}, whose slot holes are cut for that tier only — which is why the
 * slot coordinates in {@link MachineHatchMenu} cannot be adjusted on their own. Fluid and energy hatches share
 * {@code guibar.png}, which carries both the frame (u = 176) and the red fill (u = 196) for the bar at
 * (15, 10, 20×61).
 *
 * <p>The fluid screen draws the fluid's own atlas texture, tinted with its colour, and then lays the frame over
 * it — the original's order. Clicking the tank with something in hand asks the server to run that container
 * against the tank, so buckets can be filled and emptied from here.
 */
public final class MachineHatchScreen extends AbstractContainerScreen<MachineHatchMenu> {

    private static final ResourceLocation BAR_TEXTURE =
            new ResourceLocation(ModularMachineryReborn.MOD_ID, "textures/gui/guibar.png");

    /** The bar's rectangle inside the window, which is also the tooltip's hover area in the original. */
    private static final int BAR_X = 15;
    private static final int BAR_Y = 10;
    private static final int BAR_WIDTH = 20;
    private static final int BAR_HEIGHT = 61;
    /** guibar.png: the empty frame is at u = 176, the filled bar at u = 196. */
    private static final int FRAME_U = 176;
    private static final int FILL_U = 196;

    private static final int LABEL_COLOR = 0x404040;

    public MachineHatchScreen(MachineHatchMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 166;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    private HatchKind.Family family() {
        return menu.hatch().kind().family();
    }

    /** Item hatches select their texture by tier; fluid and energy share the bar texture. */
    private ResourceLocation panelTexture() {
        if (family() != HatchKind.Family.ITEM) {
            return BAR_TEXTURE;
        }
        return new ResourceLocation(ModularMachineryReborn.MOD_ID,
                "textures/gui/inventory_" + menu.hatch().tier().name().toLowerCase(Locale.ROOT) + ".png");
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(panelTexture(), leftPos, topPos, 0, 0, imageWidth, imageHeight);
        switch (family()) {
            case ENERGY -> drawEnergyBar(graphics);
            case FLUID -> drawFluid(graphics);
            case ITEM -> { }
        }
    }

    /** The original drew the fill from the bottom up: v runs from {@code 61 - pxFilled} to 61. */
    private void drawEnergyBar(GuiGraphics graphics) {
        int filled = filledPixels((float) menu.energyStored() / menu.energyCapacity());
        if (filled <= 0) {
            return;
        }
        graphics.blit(BAR_TEXTURE, leftPos + BAR_X, topPos + BAR_Y + BAR_HEIGHT - filled,
                FILL_U, BAR_HEIGHT - filled, BAR_WIDTH, filled);
    }

    private void drawFluid(GuiGraphics graphics) {
        // 流体与气体**共用同一个罐的空间**（旧版 HybridGasTank 的语义：一个罐要么装流体要么装气体，
        // 但这个仓的**容量**是两者共用的）。所以两者各自按比例画，**气体在上、流体在下**，
        // 合计不超过满格——总占用恰好等于 (流体量 + 气体量) / 容量，与仓的真实状态一致。
        int fluidFilled = filledPixels((float) menu.fluidAmount() / menu.fluidCapacity());
        int gasFilled = menu.gasCapacity() > 0
                ? filledPixels((float) menu.gasAmount() / menu.gasCapacity())
                : 0;
        int gasTop = BAR_HEIGHT - fluidFilled - gasFilled;
        if (gasFilled > 0) {
            drawGas(graphics, gasFilled, gasTop);
        }
        FluidStack fluid = menu.hatch().fluidTank().getFluid();
        if (!fluid.isEmpty() && fluidFilled > 0) {
            IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid.getFluid());
            TextureAtlasSprite sprite = Minecraft.getInstance()
                    .getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
                    .apply(extensions.getStillTexture(fluid));
            int tint = extensions.getTintColor(fluid);
            graphics.setColor(((tint >> 16) & 0xFF) / 255F, ((tint >> 8) & 0xFF) / 255F, (tint & 0xFF) / 255F, 1F);
            graphics.blit(leftPos + BAR_X, topPos + BAR_Y + BAR_HEIGHT - fluidFilled, 0, BAR_WIDTH, fluidFilled, sprite);
            graphics.setColor(1F, 1F, 1F, 1F);
        }
        // The frame is drawn over the contents, as in the original.
        // 只要这个仓可能有气体那一半（装了 Mekanism），就画框——否则「空」与「没有气体那一半」
        // 在界面上完全一样，而这两种状态的含义不同。
        if (fluidFilled > 0 || gasFilled > 0 || menu.gasCapacity() > 0) {
            graphics.blit(BAR_TEXTURE, leftPos + BAR_X, topPos + BAR_Y, FRAME_U, 0, BAR_WIDTH, BAR_HEIGHT);
        }
    }

    /**
     * 画气体那一截。
     *
     * <p><b>为什么用颜色块而不是气体自己的贴图。</b>Mekanism 的气体**没有方块图集贴图**
     * （流体的 {@code getStillTexture} 那种东西它没有），所以画不出一张「气体纹理」。
     * 用气体自己的颜色（{@code ChemicalStack#getChemicalTint()}）铺一层纯色，是能做且不假的做法：
     * 颜色由 Mekanism 自己给出，不是这里编的；而 tooltip 里写着气体的注册名，
     * 所以「这是什么气体」有权威答案。
     *
     * <p>颜色查不到时（客户端没有那个气体的注册项——理论上不会，但显示路径不该抛异常）
     * 退回中性灰，并**不假装**那是某种气体。
     */
    private void drawGas(GuiGraphics graphics, int filled, int top) {
        int tint = gasTint();
        float alpha = 0.85F;
        graphics.fill(leftPos + BAR_X + 1, topPos + BAR_Y + top,
                leftPos + BAR_X + BAR_WIDTH - 1, topPos + BAR_Y + BAR_HEIGHT,
                ((int) (alpha * 255) << 24) | (tint & 0xFFFFFF));
    }

    /** 这个仓里气体的颜色，由 Mekanism 的注册表给出；查不到时中性灰。 */
    private int gasTint() {
        String[] gas = com.reborn.modularmachinery.compat.mekanism.MekanismModule
                .gasTooltip(menu.hatch());
        if (gas == null || gas.length == 0) {
            return 0x9E9E9E;
        }
        return com.reborn.modularmachinery.compat.mekanism.MekanismModule.gasTint(gas[0]);
    }

    private static int filledPixels(float percent) {
        return Mth.ceil(Mth.clamp(percent, 0F, 1F) * BAR_HEIGHT);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // The original hatch panels carry no title, so only the player inventory label is drawn.
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL_COLOR, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (isOverBar(mouseX, mouseY)) {
            List<Component> lines = tooltipLines();
            if (!lines.isEmpty()) {
                graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
        }
    }

    private boolean isOverBar(int mouseX, int mouseY) {
        if (family() == HatchKind.Family.ITEM) {
            return false;
        }
        return mouseX >= leftPos + BAR_X && mouseX <= leftPos + BAR_X + BAR_WIDTH
                && mouseY >= topPos + BAR_Y && mouseY <= topPos + BAR_Y + BAR_HEIGHT;
    }

    private List<Component> tooltipLines() {
        if (family() == HatchKind.Family.ENERGY) {
            // The one energy line the original scaled, abbreviated <b>and</b> labelled from the config, all three in
            // the same place (`GuiContainerEnergyHatch.java:56-64`): it scaled both numbers with
            // `formatEnergyForDisplay`, printed them with `MiscUtils.formatNumber` and took the unit from
            // `EnergyDisplayUtil.type.getUnlocalizedFormat()`. This port had none of the three.
            com.reborn.modularmachinery.config.EnergyDisplay display =
                    com.reborn.modularmachinery.config.ModConfig.energyDisplay();
            return List.of(Component.translatable("tooltip.modular_machinery_reborn.energyhatch.charge",
                    com.reborn.modularmachinery.config.DisplayNumbers.abbreviated(
                            display.display(menu.energyStored())),
                    com.reborn.modularmachinery.config.DisplayNumbers.abbreviated(
                            display.display(menu.energyCapacity())),
                    display.labelComponent()));
        }
        // 工具提示的形态**由所有者定**（2026-10-20 实测截图）：气体仓与液体仓一样，三行——
        // 内容名、`量 mB / 容量 mB`、坐标。一个仓里只有一种内容（互斥，见
        // MachineHatchBlockEntity#FluidTankWithGasPriority），所以有气体时不再额外报一行 0 mB 的流体，
        // 也不再用「气体：」前缀：那只是把同一件事说两遍，而且第一行会是「空（气体占着这个仓）」。
        //
        // 为什么游戏内界面非要有这一份：Jade 那条路**不受本模组控制**——所有者这台实例上
        // SkyResources3 的 Jade 插件会对所有方块注册、且不判断是不是自家方块，把方块名那一段替换成
        // 它自己的空机器（全是 0 / 空闲）。所以游戏内界面才是玩家能依赖的那一份（规矩 6）。
        //
        // 取值走 MekanismModule：读的是**方块实体里同步过来的存档 NBT**（gas 标签），不是内存里的气体罐
        // ——后者只在「这条路上被走到过」时才存在，而客户端可能从没发生过能力查询（查询来自管道与机器，
        // 在服务端）。没装 Mekanism 时它返回 null，走流体那一条分支。
        String[] gas = com.reborn.modularmachinery.compat.mekanism.MekanismModule
                .gasTooltip(menu.hatch());
        FluidStack fluid = menu.hatch().fluidTank().getFluid();
        List<Component> lines = new java.util.ArrayList<>(3);
        if (gas != null) {
            // 名字用 Mekanism 自己的翻译（zh_cn「氢氟酸」/ en_us "Hydrofluoric Acid"）；查不到这种气体时
            // 退回注册名，至少不会显示一个空行。
            Component name = com.reborn.modularmachinery.compat.mekanism.MekanismModule
                    .gasDisplayName(gas[0]);
            lines.add(name == null ? Component.literal(gas[0]) : name);
            lines.add(Component.translatable("tooltip.modular_machinery_reborn.fluidhatch.tank",
                    gas[1], gas[2]));
        } else if (fluid.isEmpty() || menu.fluidAmount() <= 0) {
            lines.add(Component.translatable("tooltip.modular_machinery_reborn.fluidhatch.empty"));
            lines.add(Component.translatable("tooltip.modular_machinery_reborn.fluidhatch.tank", 0,
                    menu.fluidCapacity()));
        } else {
            lines.add(fluid.getDisplayName());
            lines.add(Component.translatable("tooltip.modular_machinery_reborn.fluidhatch.tank",
                    menu.fluidAmount(), menu.fluidCapacity()));
        }
        // 最后一行写**这个方块的位置**。
        //
        // 为什么值得写在 tooltip 里：排查「界面显示得不一致」时，最大的干扰是**看的不是同一个方块**
        // （这台机器周围有好几个流体仓）。`/mm-diagnose` 每行都带坐标，而 Jade 与界面不带——
        // 于是两边对不上时无法判断是「读数不同」还是「看的不是同一个仓」。这一行让两者可比。
        lines.add(Component.literal(menu.hatch().getBlockPos().toShortString())
                .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        return lines;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && family() == HatchKind.Family.FLUID
                && isOverBar((int) mouseX, (int) mouseY)
                && minecraft != null && minecraft.gameMode != null && minecraft.player != null
                && !minecraft.player.getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
            // The original sent a packet here; a menu button is the vanilla route for the same thing.
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, MachineHatchMenu.BUTTON_INTERACT_TANK);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
