package com.reborn.modularmachinery.compat;

import com.reborn.modularmachinery.recipe.RequirementType;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 一个兼容模块（旧版那种「搭在主模组上的可选扩展」，例如 {@code kport.modularmagic}）。
 *
 * <p><b>形状照抄旧版，一处不同。</b>旧版的兼容扩展是一个 {@code @Mod} 加若干
 * {@code @ZenExpansion}/{@code RequirementType} 注册，靠 Forge 的注册表把东西塞进来；
 * 本版把它收成一个接口：模块实现这个接口，由 {@link ModCompat} 在
 * {@code FMLCommonSetupEvent} 里调用一次。
 *
 * <p><b>为什么不靠 Forge 注册表。</b>需求类型**不是** Forge 注册表内容：它是配方 JSON 的解析器，
 * 只有本模组自己在读。旧版把它塞进 Forge 注册表，代价是必须处理「注册表冻结后还能不能改」
 * （{@code IntegrationTypeHelper.java:56-57} 真的在 {@code unfreeze()} 之后 {@code remove()}）。
 * 本版不需要付这笔代价，所以注册表就是一个普通的 Map，见
 * {@link com.reborn.modularmachinery.recipe.RequirementTypes}。
 *
 * <p><b>还没做的事，写清楚免得被当成已交付</b>：旧版的兼容模块还往注册表里加**方块**与**物品**
 * （30 个方块家族，见 {@code 移植方案-v2.md} D6）。方块只能在 mod 构造阶段注册（D8：注册表随后冻结），
 * 所以在没有真模块可写之前，本接口**只服务需求类型**。加方块需要的是另一条路（构造期的
 * {@code DeferredRegister} + 一份「哪些模块在」的前置判断），而那要等波 5.3 拿到第一个真模组的
 * 1.20.1 API jar 才能定形状——现在写一个空的方块入口，只是给将来留一个必须被推翻的坑。
 */
public interface CompatModule {

    /**
     * 这个模块是谁，全限定，形如 {@code modular_machinery_reborn:ae2}。
     *
     * <p>它出现在日志里（哪几个模块注册了、哪个依赖没装），也是 {@link #requiresModid()} 缺失时的身份。
     * **不以模块自己的 modid 当命名空间**：注册键的命名空间在本版没有区分力
     * （配方文件里写的命名空间会被丢弃，见 {@link com.reborn.modularmachinery.recipe.RequirementTypes#byName}），
     * 所以用一个命名空间只会让人以为它有意义。
     */
    ResourceLocation id();

    /**
     * 往注册表里放这个模块的东西。**在 {@code FMLCommonSetupEvent} 里被调用一次**，那时所有模组都构造完了。
     *
     * <p>实现里可以做任何「本模组的 API 已就绪」为前提的事，但不该碰 Forge 注册表：那个门在 mod 构造阶段
     * 就关了（D8）。要注册方块/物品，见 {@link CompatModule} 的类注释。
     */
    void register(CompatRegistry registry);

    /**
     * 让这个模块把一个**它自己的**能力挂到主模组的方块实体上（波 5.3 起）。
     *
     * <p>什么时候需要它：模块要给机器加一种**端口**。旧版的气体仓就是这么做的——它挂在已有的流体仓上
     * （{@code HybridGasTank extends HybridTank}），不是新方块；那条路与本项目的 D8 不冲突。
     * 主模组不认识那种能力，所以由模块注册一个 {@link BlockEntityPortMapper}，主模组只负责在能力查询时
     * 按顺序问它们（见 {@code MachineHatchBlockEntity#getCapability}）。
     *
     * <p>默认空实现，因为**大多数模块不需要**：一个只加需求类型的模块什么都不用挂。
     */
    default void ports(CompatRegistry registry) {
    }

    /**
     * 注册这个模块**自己的** Forge 能力（波 5.3 起）。
     *
     * <p>什么时候需要它：模块的端口是一种**别的模组声明的能力**（旧版气体就是 Mekanism 的
     * {@code IGasHandler}）。自己的能力必须自己注册，否则 Forge 的 {@code getCapability} 不会把查询
     * 转给方块实体——那个能力就永远问不到。主模组不该认识它，所以这一步只能由模块做。
     *
     * <p><b>时机是 mod 构造期</b>（{@code RegisterCapabilitiesEvent}），比 {@link #register} 与
     * {@link #ports} 都早。因此实现里**不能**依赖「本模块已经注册过需求类型」这类前提。
     *
     * <p>默认空实现：只加需求类型、不加端口的模块用不到它。
     */
    default void registerCapabilities(net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent event) {
    }

    /**
     * 这个模块需要哪个模组在场，{@code null} 表示不需要（或由模块自己判断）。
     *
     * <p>与 {@link RequirementType#requiresModid()} 是两件事而不是重复：这一条决定
     * 「{@link #register} 要不要被调用」（在注册之前筛），而那一条是**注册之后的兜底**
     * （照旧版 {@code IntegrationTypeHelper.filterModIdRequirementTypes}，把漏进来的摘掉）。
     * 前者是效率与清晰，后者是正确性——只做前者，一次时机判断错误就会留下一种永远不可用的需求。
     */
    @Nullable
    default String requiresModid() {
        return null;
    }
}
