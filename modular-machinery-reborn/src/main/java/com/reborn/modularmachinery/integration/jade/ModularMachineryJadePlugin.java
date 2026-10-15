package com.reborn.modularmachinery.integration.jade;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * 本模组的 Jade 插件：把流体仓的流体内容交给 Jade 标准容量条显示，并调整仓名颜色。
 *
 * <h2>为什么放在这里、以及为什么这个类里没有客户端代码</h2>
 *
 * <p>Jade 用 {@code @WailaPlugin} 扫描并**在两侧都加载插件类**（Mekanism 的
 * {@code MekanismJadePlugin} 就是把 {@code register} 与 {@code registerClient} 放在同一个类里，
 * 而它的 {@code register} 只引用服务端类）。所以这个类本身**只引用 Jade 的 API 与服务端的类**：
 * 客户端容量条 provider 只在 {@link #registerClient} 里被引用，而那个方法只在客户端调用。
 *
 * <p>服务端 storage provider 从流体仓自身读取容量与内容，避免通用 capability 查询拿到其他兼容层的空罐；
 * 客户端只负责让 Jade 用标准容量条渲染这份数据。
 */
@WailaPlugin
public final class ModularMachineryJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerFluidStorage(HatchFluidStorageProvider.SERVER,
                com.reborn.modularmachinery.block.MachineHatchBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerFluidStorageClient(HatchFluidStorageProvider.client());
        registration.addTooltipCollectedCallback(FluidStorageSuppressor.INSTANCE);
    }
}
