package com.reborn.modularmachinery.integration.jade;

import com.reborn.modularmachinery.ModularMachineryReborn;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.Accessor;
import snownee.jade.api.ITooltip;
import snownee.jade.api.callback.JadeTooltipCollectedCallback;

/**
 * 摘掉 Jade 通用流体组件在本模组仓方块上画的那一份。
 *
 * <h2>为什么必须摘</h2>
 *
 * <p>那个组件按 {@code BlockEntity.class} 注册，服务端向方块实体要流体能力、再把结果发给客户端。
 * 本模组这一侧的服务端读数**四条全部一致**（罐真值 / 本类交出 / Jade 流程模拟 / 界面 tooltip
 * 都是 32000 mB），而它在客户端画出来的是 `空 : 0 mB` 与「这一栏没有显示」两种错法
 * （所有者实例上两种都出现过；它读服务端却渲染成空这一点，本模组改不动）。
 *
 * <p>所以：本模组现在**自己**把流体内容报给 Jade（{@link HatchFluidComponent}，读客户端自己的罐，
 * 与界面 tooltip 同源），那就该把**同一件事的第二份、而且错的那一份**从本模组的方块上摘掉——
 * 否则玩家看到的是两个互相矛盾的数字。
 *
 * <h2>做法与边界</h2>
 *
 * <p>用 Jade 的「工具提示收集完成」回调，按它自己的 id（{@code jade:fluid_storage}）移除，
 * 且**只对 {@link com.reborn.modularmachinery.block.MachineHatchBlock} 生效**——
 * 绝不去动别的模组方块上的东西。
 *
 * <p>id 用 {@code ResourceLocation} 直接构造而不是引用 Jade 的 {@code Identifiers} 常量：
 * 那个常量是 {@code jade} 命名空间 + {@code fluid_storage}（已用 javap 取到其 {@code <clinit>} 的值）。
 * 直接写字符串少一处对 Jade 内部结构的依赖，也便于日后核对。
 */
public final class FluidStorageSuppressor implements JadeTooltipCollectedCallback {

    public static final FluidStorageSuppressor INSTANCE = new FluidStorageSuppressor();

    /**
     * Jade 通用流体组件的 id（{@code Identifiers.UNIVERSAL_FLUID_STORAGE}）。
     *
     * <p>只在 id 已被双方写进同一个命名空间时才可能撞车：本模组自己的组件是
     * {@code modular_machinery_reborn:hatch_fluid}，与它不同。
     */
    private static final ResourceLocation UNIVERSAL_FLUID_STORAGE =
            new ResourceLocation("jade", "fluid_storage");

    private FluidStorageSuppressor() {
    }

    @Override
    public void onTooltipCollected(ITooltip tooltip, Accessor<?> accessor) {
        // getBlockState() 在 BlockAccessor 上（实体方块的那一套不带它），所以这里判类型。
        if (accessor instanceof snownee.jade.api.BlockAccessor block
                && block.getBlockState().getBlock()
                        instanceof com.reborn.modularmachinery.block.MachineHatchBlock) {
            tooltip.remove(UNIVERSAL_FLUID_STORAGE);
        }
    }

    /** 供断言与日志引用的描述（本模组摘掉的那一项）。 */
    public static String suppressedId() {
        return ModularMachineryReborn.MOD_ID + " suppresses " + UNIVERSAL_FLUID_STORAGE;
    }
}
