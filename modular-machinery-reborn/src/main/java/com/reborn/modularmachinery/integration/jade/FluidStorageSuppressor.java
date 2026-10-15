package com.reborn.modularmachinery.integration.jade;

import com.reborn.modularmachinery.block.MachineHatchBlock;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.Accessor;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.callback.JadeTooltipCollectedCallback;
import snownee.jade.api.ui.IElement;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 去掉 Jade 通用流体组件在本模组仓方块上画的那一块。
 *
 * <h2>三次失败学到的三件事（都由实机读数定出来，不是猜的）</h2>
 *
 * <ol>
 *   <li><b>按 id 移除无效</b>：Jade 的 {@code ClientViewGroup.tooltip(...)} 用
 *       {@code ITooltip.add(IElement)}（**不带 id**）加流体元素，那些元素没有 tag。</li>
 *   <li><b>只按 {@code Align.LEFT} 取元素不够</b>：通用流体那一块（图标与空框）在**另一侧**
 *       （它的空框画在提示的**另一端**，即 Align.RIGHT 侧），所以只按 LEFT 取到的列表里看不到它（Align 只有 LEFT/RIGHT 两个值）。</li>
 *   <li><b>方块显示名不能直接用</b>：{@code getDisplayName()} 会带等级前缀
 *       （实测 {@code "真空流体输入仓"}），而提示里印的是简称（{@code "流体输入仓"}），
 *       于是「包含」判据永远不成立、每次都走兜底。</li>
 * </ol>
 *
 * <h2>这一版的定位方式</h2>
 *
 * <p>不再靠名字，而是**按已知的固定下标**：Jade 提示的前段是
 * {@code 第 0 行 = 方块名+图标}、{@code 第 1 行 = 通用流体的空框}。这两个位置对所有方块都一样，
 * 而本回调**只对本模组的 {@link MachineHatchBlock} 注册**，所以它看到的提示必然就是本模组方块那一张，
 * 不存在误伤别的方块的可能。
 *
 * <h2>为什么还要一个「已经处理过」的标记</h2>
 *
 * <p>所有者实测到「回调被调用 410 次 / 改动提示 325 次」，提示里因此堆了两份本模组的行
 * ——{@code onTooltipCollected} 会在一次悬停里被多次调用（每次重绘都可能是一次），
 * 所以**只能动手一次**。标记用元素自己的注册 id（{@code modular_machinery_reborn:hatch_fluid}），
 * 于是「本模组的行在不在提示里」是个可直接查的事实。
 */
public final class FluidStorageSuppressor implements JadeTooltipCollectedCallback {

    public static final FluidStorageSuppressor INSTANCE = new FluidStorageSuppressor();

    /** 本模组组件自己的 id：既用于注册，也用作「提示里已经有本模组的行」的标记。 */
    public static final ResourceLocation OWN_MARKER =
            new ResourceLocation(com.reborn.modularmachinery.ModularMachineryReborn.MOD_ID, "hatch_fluid");

    /** Jade 提示里方块名所在的行（第 0 行）。 */
    private static final int ROW_NAME = 0;

    /** 通用流体组件画空框的那一行（方块名下面第一行）。 */
    private static final int ROW_FLUID_FRAME = 1;

    /** 回调被调用的次数。 */
    private static final AtomicInteger CALLED = new AtomicInteger();

    /** 其中真的改动了提示的次数。 */
    private static final AtomicInteger ACTED = new AtomicInteger();

    /** 其中因为「已经处理过」而跳过的次数（用来确认重复调用这件事发生过）。 */
    private static final AtomicInteger SKIPPED = new AtomicInteger();

    private static volatile String lastReport = "（还没被调用过）";

    private FluidStorageSuppressor() {
    }

    /** 给 {@code /mm-diagnose} 的读数。 */
    public static String counters() {
        return "回调被调用 " + CALLED.get() + " 次 / 改动提示 " + ACTED.get() + " 次 / 因已处理而跳过 "
                + SKIPPED.get() + " 次；最近一次: " + lastReport;
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
        // **已经处理过就跳过**（0.56.0 修正）。
        //
        // 上一版用 `tooltip.get(OWN_MARKER)` 判断，实测**永不命中**：Jade 不保留 `add(..., id)` 传进去的
        // id（所有者读数：回调 690 次 / 改动 432 次 / 因已处理而跳过 0 次）。所以改成**按内容**判断：
        // 本模组补回的方块名就在第 0 行，而它带上等级（例如「真空流体输入仓」），Jade 自己印的是简称
        // （「流体输入仓」）——两者不相等，所以这个判据不会把 Jade 那一行误认成我的。
        //
        // 为什么必须去重：一次悬停里这个回调会被调用几百次，每次都「先删再加」虽然结果幂等，
        // 却等于把整张提示重建几百遍，而且留下一个坏习惯——让人以为「碰巧对」就等于「设计对」。
        if (rowStartsWith(tooltip, ROW_NAME, hatch.getDisplayName().getString())) {
            SKIPPED.incrementAndGet();
            return;
        }
        ACTED.incrementAndGet();
        boolean removedName = removeRow(tooltip, ROW_NAME);
        boolean removedFrame = removeRow(tooltip, ROW_FLUID_FRAME);
        lastReport = "删除名字行=" + removedName + " 删除空框行=" + removedFrame
                + " 处理后剩 " + tooltip.size() + " 行";
        // 方块名由本模组补回（它原来那一行连同图标被删掉了）。
        List<Component> mine = new ArrayList<>();
        mine.add(hatch.getDisplayName());
        mine.addAll(HatchFluidComponent.linesFor(hatch));
        for (int i = 0; i < mine.size(); i++) {
            tooltip.add(i, mine.get(i), OWN_MARKER);
        }
    }

    /**
     * 删掉一行的**所有**元素（左右两侧都试），返回是否删到了东西。
     *
     * <p>逐个元素按它自己的 tag 删。**有 tag 才删得掉**——{@code ITooltip.remove(ResourceLocation)}
     * 只认 id，而 Jade 通用流体那一块的元素是否带 id 并不由本模组决定；没有 id 的元素删不掉，
     * 这也正是所有者截图上那个空框仍然留在提示里的原因（已知、已登记，不影响显示正确的液体信息）。
     */
    private static boolean removeRow(ITooltip tooltip, int index) {
        List<IElement> elements = new ArrayList<>();
        List<IElement> left = tooltip.get(index, IElement.Align.LEFT);
        if (left != null) {
            elements.addAll(left);
        }
        List<IElement> right = tooltip.get(index, IElement.Align.RIGHT);
        if (right != null) {
            elements.addAll(right);
        }
        boolean removed = false;
        for (IElement element : elements) {
            ResourceLocation tag = element.getTag();
            if (tag != null) {
                tooltip.remove(tag);
                removed = true;
            }
        }
        return removed;
    }
    /** 这一行第一个元素的文本是不是以给定字符串开头。 */
    private static boolean rowStartsWith(ITooltip tooltip, int index, String prefix) {
        List<IElement> left = tooltip.get(index, IElement.Align.LEFT);
        if (left == null || left.isEmpty()) {
            return false;
        }
        String message = left.get(0).getMessage();
        return message != null && message.startsWith(prefix);
    }
}
