package com.reborn.modularmachinery.compat;

import com.reborn.modularmachinery.recipe.RequirementType;
import com.reborn.modularmachinery.recipe.RequirementTypes;

/**
 * 兼容模块能往主模组里放东西的那只手——旧版没有这个东西，它直接往 Forge 注册表里写。
 *
 * <p><b>为什么要有这一层而不是让模块直接调 {@link RequirementTypes#register}。</b>
 * 模块需要的不只是「注册」：它还要知道主模组现在允许它放什么，而「允许放什么」在这个版本里还会变。
 * 有一个入口对象，将来加一类可注册的东西就是给这个类加一个方法，而不是让每个模块各自去找对应的注册表。
 *
 * <p>现在允许两样：**需求类型**（波 5.2 起）与**能力映射器**（波 5.3 起，让模块把一种自己的端口
 * 挂到主模组的方块实体上）。方块与物品仍然没有入口，因为方块只能在 mod 构造期注册（D8），
 * 那需要另一条「构造期就知道有哪些模块」的路。
 */
public final class CompatRegistry {

    /** 这个模块注册的能力映射器，按注册顺序；由 {@link ModCompat} 取走。 */
    private final java.util.List<BlockEntityPortMapper> mappers = new java.util.ArrayList<>();

    /**
     * 由 {@link ModCompat} 每个启动周期建**一个**给所有模块用。
     *
     * <p>不是单例（没有静态实例）：需求类型的状态全在 {@link RequirementTypes} 里，
     * 造第二个只会让映射器分成两份、而只有一份被 {@code ModCompat} 收走。留成 public 是为了让复现器
     * 能自己造一个来驱动 {@code CompatModule#register}，而不必去调 {@link ModCompat} 的 FML 入口。
     */
    public CompatRegistry() {
    }

    /**
     * 注册一种需求：配方文件里就能写 {@code "type": "<这个模块的 modid>:<path>"} 了。
     *
     * <p>模块可以按自己的 modid 注册（{@code new ResourceLocation("ae2", "gas")}），也可以按本模组的命名空间
     * 注册，两种都能被配方文件解析到——见 {@link RequirementTypes#byName} 的两次查找。
     * <b>但不要用旧模组的 id（{@code modularmachinery}）</b>：那个命名空间在第一次查找里会被归一到本模组的
     * 同名种类，于是你的注册看起来成功了，实际永远轮不到它。
     *
     * @return 注册进去的那一个，方便模块把它存成常量
     */
    public static RequirementType requirement(RequirementType type) {
        return RequirementTypes.register(type);
    }

    /**
     * 把一个**本模块自己的**能力挂到主模组的方块实体上。
     *
     * <p>映射器必须在**第一个端口被问起之前**登记，而端口在玩家进世界后才会被问——
     * 所以 {@code FMLCommonSetupEvent}（本方法被调用的时机）是够早的。
     *
     * <p>同一个能力登记两个映射器不会报错，但它们每次能力查询都会被问一遍；顺序即登记顺序。
     */
    public void port(BlockEntityPortMapper mapper) {
        this.mappers.add(mapper);
    }

    /** 已登记的映射器，按注册顺序。 */
    public java.util.List<BlockEntityPortMapper> portMappers() {
        return java.util.List.copyOf(this.mappers);
    }
}
