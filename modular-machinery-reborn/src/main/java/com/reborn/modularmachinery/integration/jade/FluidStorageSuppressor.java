package com.reborn.modularmachinery.integration.jade;

import com.reborn.modularmachinery.block.MachineHatchBlock;
import net.minecraft.network.chat.Component;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.callback.JadeTooltipCollectedCallback;
import snownee.jade.api.ui.IElement;

import java.util.List;

/**
 * 把 Jade 通用流体组件在本模组仓方块上画的那一份整块去掉，并在这个位置放上本模组自己的两行。
 *
 * <h2>为什么不能用 {@code ITooltip.remove(id)}</h2>
 *
 * <p>那个 id 版本试过，**无效**：Jade 的 {@code ClientViewGroup.tooltip(...)} 是用
 * {@code ITooltip.add(IElement)}（**不带 id** 的那个重载）把流体元素加进提示的，
 * 所以按 id 移除什么也找不到。这是从 Jade 的字节码里读出来的，不是猜的
 * （{@code ClientViewGroup} 里只有 {@code add(IElement)} 与 {@code add(IElement, Align)}）。
 *
 * <h2>所以按「内容」定位</h2>
 *
 * <p>用 {@code ITooltip.get(int, Align)} 把该行的元素枚举出来，找**那个就是方块显示名**的元素
 * （与 {@code BlockAccessor.getBlockEntity().getDisplayName()} 相等）——那就是「流体输入仓」这一行的起点。
 * 从它开始、到下一行之前的内容就是通用流体组件的那一块（图标 + 名字 + 空框），删掉，
 * 再在同一个下标插入本模组自己的「液体」与「气体」两行。
 *
 * <p>只对本模组的 {@link MachineHatchBlock} 生效；别的模组方块一个字都不动。
 */
public final class FluidStorageSuppressor implements JadeTooltipCollectedCallback {

    public static final FluidStorageSuppressor INSTANCE = new FluidStorageSuppressor();

    private FluidStorageSuppressor() {
    }

    @Override
    public void onTooltipCollected(ITooltip tooltip, Accessor<?> accessor) {
        if (!(accessor instanceof BlockAccessor block)
                || !(block.getBlockState().getBlock() instanceof MachineHatchBlock)
                || !(block.getBlockEntity()
                        instanceof com.reborn.modularmachinery.block.MachineHatchBlockEntity hatch)) {
            return;
        }
        int index = nameRowIndex(tooltip, block, hatch);
        if (index < 0) {
            // 找不到名字那一行就什么都不动：宁可留着通用组件那一份，也不要凭空删掉别的东西。
            return;
        }
        // 删掉名字那一行及其后面「同一个元素锚点」下的内容（通用组件的图标与数字画在那一行上）。
        // size 会随 remove 变化，所以每次都重新取第一个元素来判断。
        while (tooltip.size() > index && isNameRow(tooltip, index, block, hatch)) {
            tooltip.remove(firstElementTag(tooltip, index));
        }
        // 插到原位：这样两行紧贴在方块名下面，与之前看到的布局一致。
        List<Component> mine = HatchFluidComponent.linesFor(hatch);
        for (int i = 0; i < mine.size(); i++) {
            tooltip.add(index + i, mine.get(i));
        }
    }

    /** 方块名那一行的下标；找不到返回 -1。 */
    private static int nameRowIndex(ITooltip tooltip, BlockAccessor block,
            com.reborn.modularmachinery.block.MachineHatchBlockEntity hatch) {
        for (int i = 0; i < tooltip.size(); i++) {
            if (isNameRow(tooltip, i, block, hatch)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 这一行是不是「方块名」那一行。
     *
     * <p>判据用**显示名相等**（{@code Component} 实现了 {@code equals}），而显示名取自方块实体自己
     * ——与 Jade 画在提示里的那一个同源，所以不会因为语言或大小写而对不上。
     */
    private static boolean isNameRow(ITooltip tooltip, int index, BlockAccessor block,
            com.reborn.modularmachinery.block.MachineHatchBlockEntity hatch) {
        List<IElement> elements = tooltip.get(index, IElement.Align.LEFT);
        if (elements == null || elements.isEmpty()) {
            return false;
        }
        String message = elements.get(0).getMessage();
        return message != null && message.equals(hatch.getDisplayName().getString());
    }

    /** 这一行第一个元素的注册 id；取不到时用一个占位 id（remove 对不存在的 id 是空操作）。 */
    private static net.minecraft.resources.ResourceLocation firstElementTag(ITooltip tooltip, int index) {
        List<IElement> elements = tooltip.get(index, IElement.Align.LEFT);
        if (elements != null && !elements.isEmpty() && elements.get(0).getTag() != null) {
            return elements.get(0).getTag();
        }
        return new net.minecraft.resources.ResourceLocation("modular_machinery_reborn", "hatch_row");
    }
}
