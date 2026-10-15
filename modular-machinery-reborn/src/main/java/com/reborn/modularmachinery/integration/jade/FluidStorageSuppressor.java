package com.reborn.modularmachinery.integration.jade;

import com.reborn.modularmachinery.block.MachineHatchBlock;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.callback.JadeTooltipCollectedCallback;
import snownee.jade.api.ui.IElement;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 去掉 Jade 通用流体组件在本模组仓方块上画的那一块。
 *
 * <h2>这个类为什么带计数器</h2>
 *
 * <p>它已经失败过两次，而两次失败在游戏里的表现**完全一样**（提示没有任何变化）：
 * 可能是这个回调压根没被调用，也可能调用了但没定位到目标那一行。两者要的修法完全不同，
 * 而它们从外面看不出来——本项目规矩 5 说的就是这一类。所以这里把两件事都记成持久计数：
 * 回调被调用了几次、其中几次真的动手改了提示。计数由 {@code /mm-diagnose} 读出来。
 *
 * <h2>与 Jade 的实际行为有关的两条事实（都取自它的字节码）</h2>
 *
 * <ul>
 *   <li>{@code ClientViewGroup.tooltip(...)} 用 {@code ITooltip.add(IElement)}（**不带 id**）把通用流体
 *       元素加进提示 —— 所以 {@code ITooltip.remove(ResourceLocation)} 找不到它（第一次失败的原因）。</li>
 *   <li>方块名那一行的元素文本与 {@code getDisplayName()} 不相等（第二次失败的原因：定位没命中），
 *       所以这一版改用「文本包含」这一更松的判据，并保留一个只对**本模组方块**生效的兜底下标。</li>
 * </ul>
 */
public final class FluidStorageSuppressor implements JadeTooltipCollectedCallback {

    public static final FluidStorageSuppressor INSTANCE = new FluidStorageSuppressor();

    /** 通用流体组件的注册 id（{@code Identifiers.UNIVERSAL_FLUID_STORAGE}）。 */
    private static final ResourceLocation UNIVERSAL_FLUID_STORAGE =
            new ResourceLocation("jade", "fluid_storage");

    /** 定位全部失败时的兜底：方块名是 Jade 提示的第一行。 */
    private static final int FALLBACK_ROW = 0;

    /** 回调被调用的次数（每一次「看」本模组方块都会 +1）。 */
    private static final AtomicInteger CALLED = new AtomicInteger();

    /** 其中真的改动了提示的次数。 */
    private static final AtomicInteger ACTED = new AtomicInteger();

    /** 最近一次定位结果，供诊断读取（-1 表示没定位到、走了兜底）。 */
    private static volatile String lastLocate = "（还没被调用过）";

    private FluidStorageSuppressor() {
    }

    /** 给 {@code /mm-diagnose} 的读数。 */
    public static String counters() {
        return "回调被调用 " + CALLED.get() + " 次 / 其中改动提示 " + ACTED.get() + " 次"
                + "；最近一次定位: " + lastLocate;
    }

    @Override
    public void onTooltipCollected(ITooltip tooltip, Accessor<?> accessor) {
        CALLED.incrementAndGet();
        if (!(accessor instanceof BlockAccessor block)
                || !(block.getBlockState().getBlock() instanceof MachineHatchBlock)
                || !(block.getBlockEntity()
                        instanceof com.reborn.modularmachinery.block.MachineHatchBlockEntity hatch)) {
            return;
        }
        String name = hatch.getDisplayName().getString();
        int index = locateNameRow(tooltip, name);
        lastLocate = "name=\"" + name + "\" 定位行=" + index + " 提示共 " + tooltip.size() + " 行";
        if (index < 0) {
            index = Math.min(FALLBACK_ROW, Math.max(0, tooltip.size() - 1));
        }
        ACTED.incrementAndGet();
        List<Component> mine = HatchFluidComponent.linesFor(hatch);
        int at = Math.min(index, tooltip.size());
        for (int i = 0; i < mine.size(); i++) {
            tooltip.add(at + i, mine.get(i));
        }
    }

    /** 找「方块名那一行」；判据依次放宽，全不中返回 -1。 */
    private static int locateNameRow(ITooltip tooltip, String name) {
        int tagged = rowOfTag(tooltip);
        if (tagged >= 0) {
            return tagged;
        }
        for (int i = 0; i < tooltip.size(); i++) {
            if (rowMentions(tooltip, i, name)) {
                return i;
            }
        }
        return -1;
    }

    /** 带通用流体 id 的元素在第几行；找不到返回 -1。 */
    private static int rowOfTag(ITooltip tooltip) {
        for (int i = 0; i < tooltip.size(); i++) {
            for (IElement element : leftAligned(tooltip, i)) {
                if (UNIVERSAL_FLUID_STORAGE.equals(element.getTag())) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** 这一行里有没有哪个元素的文本提到这个方块名。 */
    private static boolean rowMentions(ITooltip tooltip, int index, String name) {
        for (IElement element : leftAligned(tooltip, index)) {
            String message = element.getMessage();
            if (message != null && message.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<IElement> leftAligned(ITooltip tooltip, int index) {
        List<IElement> elements = tooltip.get(index, IElement.Align.LEFT);
        return elements == null ? List.of() : elements;
    }
}
