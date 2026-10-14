package com.reborn.modularmachinery.compat;

import com.reborn.modularmachinery.recipe.RequirementType;
import com.reborn.modularmachinery.recipe.RequirementTypes;

/**
 * 兼容模块能往主模组里放东西的那只手——旧版没有这个东西，它直接往 Forge 注册表里写。
 *
 * <p><b>为什么要有这一层而不是让模块直接调 {@link RequirementTypes#register}。</b>
 * 模块需要的不只是「注册」：它还要知道主模组现在允许它放什么，而「允许放什么」在这个版本里还会变
 * （方块与物品还没有入口，见 {@link CompatModule}）。有一个入口对象，将来加一类可注册的东西
 * 就是给这个类加一个方法，而不是让每个模块各自去找对应的注册表。
 *
 * <p>它现在只转交需求类型，**这是已知的、有意的窄**，不是「框架已完成」。
 */
public final class CompatRegistry {

    /**
     * 由 {@link ModCompat} 每个启动周期建**一个**给所有模块用。
     *
     * <p>不是单例（没有静态实例）：它什么都不持有，状态全在 {@link RequirementTypes} 里，
     * 所以再造一个也不会出现两份状态。留成 public 是为了让复现器能自己造一个来驱动
     * {@code CompatModule#register}，而不必去调 {@link ModCompat} 的 FML 入口。
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
}
