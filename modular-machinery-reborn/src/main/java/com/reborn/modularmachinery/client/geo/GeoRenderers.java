package com.reborn.modularmachinery.client.geo;

import com.reborn.modularmachinery.block.ModBlocks;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

/**
 * GeckoLib 渲染器的注册入口。**这个类只在 geckolib 已加载时被调用**（见 ClientSetup），
 * 所以它可以直接引用 GeckoLib 的类型：没有 geckolib 的实例根本不会加载这个类。
 */
public final class GeoRenderers {

    private GeoRenderers() {
    }

    public static void register() {
        BlockEntityRenderers.register(ModBlocks.MACHINE_CONTROLLER_ENTITY.get(),
                context -> new MachineControllerGeoRenderer());
    }
}
