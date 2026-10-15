package com.reborn.modularmachinery.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nullable;

/**
 * 让一个兼容模块把一个**它自己的**能力挂到主模组的方块实体上。
 *
 * <p><b>为什么需要这一层，而不是让 {@code MachineHatchBlockEntity#getCapability} 直接认识
 * Mekanism 的 {@code IGasHandler}。</b>那个类在主模组里，每个世界都会被加载，**包括没装 Mekanism 的进程**。
 * 它只要在方法签名或方法体里出现一次 {@code IGasHandler}，那个类型就必须能解析——没装 Mekanism 时
 * {@code getCapability} 一被调用就是 {@code NoClassDefFoundError}，而那是**每个方块、每次能力查询**都会走的路。
 * 把「认识某种能力」这件事交给模块注册的映射器之后，主模组只知道
 * {@link Capability}、{@link LazyOptional} 与 {@link CompoundTag}（都是 Forge/原版自己的类型）。
 *
 * <p><b>不只是「读」。</b>端口里的东西要存进存档，而主模组不知道那个东西是什么（气体？灵气？），
 * 所以 {@link #save} / {@link #load} 也在这里：主模组只负责在存档读写时把机会交给模块，
 * 内容由模块自己序列化。
 *
 * <p><b>谁负责注册，什么时候。</b>模块在 {@link CompatModule#ports} 里注册；那个方法在
 * {@code ModCompat.register}（即 {@code FMLCommonSetupEvent}）里被调用。能力**查询**与**存档**
 * 都发生在那之后（玩家进世界才有方块实体），所以顺序是安全的。
 */
public interface BlockEntityPortMapper {

    /**
     * 这个能力是不是本映射器负责的；是则返回该实体上的处理器，否则返回 {@code null}。
     *
     * <p>返回 {@code null} 与返回 {@link LazyOptional#empty()} 的区别是有意的：{@code null} 表示
     * 「不是我的能力」，调用方继续问下一个映射器；空 optional 表示「是我的能力，但这个实体此刻没有」，
     * 调用方应当据此结束（那与 Forge 的语义一致）。
     *
     * @param capability 被查询的能力
     * @param entity     被查询的方块实体，非空
     */
    @Nullable
    LazyOptional<?> map(Capability<?> capability, Object entity);

    /**
     * 这个实体上**属于本模块**的端口处理器，供**端口收集**使用（与 {@link #map} 的 Forge 能力查询不同）。
     *
     * <p>为什么需要单独一个方法：端口收集一次要拿到「这个实体上你所有的端口」，而不是回答
     * 「某个能力是不是你的」。那时没有 {@code Capability} 对象可比——主模组根本不知道那种能力是什么，
     * 所以只能由模块自己认出自己的实体。
     *
     * <p>返回的处理器是**长期持有**的引用（存进 {@code HatchCollection}，配方生命周期反复读它），
     * 所以不要返回一个每次调用都新建的包装对象：那会让「配方看到的那一份」与「能力查询拿到的那一份」
     * 变成两个对象、两份状态。
     *
     * <p>默认空列表：只加能力、不参与配方结算的模块不需要它。
     */
    /**
     * 这个映射器认领的是哪些能力，给**诊断**用（可读的名字，而不是 {@code Capability@25f549c}）。
     *
     * <p>为什么值得有这个方法：{@code Capability#toString()} 只给对象身份，于是
     * 「管道问的到底是不是气体能力」这件事在诊断输出里读不出来——而那是这类缺陷最关键的一个分岔。
     * 默认返回空列表：不实现它的映射器在诊断里只会显示成「非本模块的能力」。
     */
    default java.util.Map<String, String> capabilityNames() {
        return java.util.Map.of();
    }

    /**
     * 这个实体上**属于本模块**的端口处理器，供**端口收集**使用（与 {@link #map} 的 Forge 能力查询不同）。
     *
     * <p>为什么需要单独一个方法：端口收集一次要拿到「这个实体上你所有的端口」，而不是回答
     * 「某个能力是不是你的」。那时没有 {@code Capability} 对象可比——主模组根本不知道那种能力是什么，
     * 所以只能由模块自己认出自己的实体。
     *
     * <p>返回的处理器是**长期持有**的引用（存进 {@code HatchCollection}，配方生命周期反复读它），
     * 所以不要返回一个每次调用都新建的包装对象：那会让「配方看到的那一份」与「能力查询拿到的那一份」
     * 变成两个对象、两份状态。
     *
     * <p>默认空列表：只加能力、不参与配方结算的模块不需要它。
     */
    default java.util.List<?> handlers(Object entity) {
        return java.util.List.of();
    }

    /**
     * 把这个实体上属于本模块的内容写进 {@code tag}；没有内容时**什么都不写**。
     *
     * <p>实现必须用**自己的**子标签（例如 {@code tag.put("gas", …)}）而不是往根上写键：
     * 同一个实体的存档会被多个模块与主模组自己共用，往根上写就是抢别人的键名。
     */
    default void save(CompoundTag tag, Object entity) {
    }

    /**
     * 从 {@code tag} 读回这个实体上属于本模块的内容；没有内容时**什么都不做**。
     *
     * <p>读到不认识的东西（例如存档是在装了某个模组时写下的、之后那个模组被卸掉）应当**安静地跳过**，
     * 而不是抛异常：一份存不开的世界比丢一个仓里的内容糟糕得多。
     */
    default void load(CompoundTag tag, Object entity) {
    }
}
