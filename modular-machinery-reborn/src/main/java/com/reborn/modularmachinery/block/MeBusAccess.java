package com.reborn.modularmachinery.block;

import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;

/**
 * C4（0.73.0）：菜单与方块共享的 ME 总线视图。
 *
 * <p>这个接口刻意不 import 任何 {@code appeng} 类型：菜单和 {@code MeItemBusBlock} 只需要
 * 「有没有配置槽」和「网络在不在线」两件事，网络节点本身留在
 * {@code compat.ae2.MeItemBusBlockEntity} 里。
 */
public interface MeBusAccess {

    /** 输入总线的 16 个配置槽；输出总线没有配置槽，返回 {@code null}。 */
    @Nullable
    ItemStackHandler configHandler();

    /** 节点是否已连接并有频道；没有 AE2 时这个实现根本不会被创建。 */
    boolean meOnline();
}
