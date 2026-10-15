package com.reborn.modularmachinery.integration.jade;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * 本模组的 Jade 插件：只做一件事——把流体仓的流体内容交给 Jade 显示。
 *
 * <h2>为什么放在这里、以及为什么这个类里没有客户端代码</h2>
 *
 * <p>Jade 用 {@code @WailaPlugin} 扫描并**在两侧都加载插件类**（Mekanism 的
 * {@code MekanismJadePlugin} 就是把 {@code register} 与 {@code registerClient} 放在同一个类里，
 * 而它的 {@code register} 只引用服务端类）。所以这个类本身**只引用 Jade 的 API 与服务端的类**：
 * 客户端那一段（{@link HatchFluidComponent}）只在 {@link #registerClient} 里被引用，
 * 而那个方法只在客户端调用——于是专用服务端不会因为缺客户端类而炸。
 *
 * <p>插件不注册任何**服务端**数据提供者：流体内容在客户端由同步过来的存档 NBT 直接读
 * （理由见 {@link HatchFluidComponent}）。少一次网络往返，也少一处可能被别的插件压掉的地方。
 */
@WailaPlugin
public final class ModularMachineryJadePlugin implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // 只注册给本模组的流体仓方块类，而不是 Block.class：Jade 的 provider 是按方块类分发的，
        // 精确注册既省开销，也避免与别的模组的全局 provider 相互干扰。
        registration.registerBlockComponent(HatchFluidComponent.INSTANCE,
                com.reborn.modularmachinery.block.MachineHatchBlock.class);
        // 再把 Jade 通用流体组件**在本模组方块上的那一份**摘掉：它服务端读得对、客户端却画成空
        // （所有者实例上实测到「空 : 0 mB」与「整栏不显示」两种错法），而本模组已自己给出正确的一份。
        // 两份并存会让玩家看到两个互相矛盾的数字。
        registration.addTooltipCollectedCallback(FluidStorageSuppressor.INSTANCE);
    }
}
