package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.block.ModBlocks;
import com.reborn.modularmachinery.menu.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

public final class ClientSetup {
    private ClientSetup() {}
    public static void onClientSetup(FMLClientSetupEvent event) {
        onRegisterRenderers(event);
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.MACHINE_CONTROLLER.get(), MachineControllerScreen::new);
            MenuScreens.register(ModMenus.MACHINE_HATCH.get(), MachineHatchScreen::new);
            MenuScreens.register(ModMenus.PARALLEL_CONTROLLER.get(), ParallelControllerScreen::new);
            // A2：输入组配置窗口（旧版 GuiContainerGroupInputConfig）。
            MenuScreens.register(ModMenus.HATCH_GROUP.get(), HatchGroupScreen::new);
            MenuScreens.register(ModMenus.UPGRADE_BUS.get(), UpgradeBusScreen::new);
            // M6e-2: the factory controller gets its own screen (280×213 panel plus the elements sheet), so it
            // needs a registration of its own on top of its own menu type. Nothing else is needed — the resource
            // pack loader discovers the two new PNGs by path, exactly as it does for the other twelve.
            MenuScreens.register(ModMenus.FACTORY_CONTROLLER.get(), FactoryControllerScreen::new);
            // The parallel controller draws a full-cube overlay texture, and the original rendered the block in
            // the CUTOUT layer (BlockParallelController.java:66-71). 1.20.1 Forge has no BlockRenderLayer; the
            // equivalent is the render-layer registry. It is genuinely required here: the five
            // overlay_parallel_controller_*.png sheets are mostly transparent (the `normal` sheet is 75% fully
            // transparent pixels), so the default solid layer would draw those pixels as opaque black.
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.PARALLEL_CONTROLLER.get(), RenderType.cutout());
            // Same reason, same source: BlockUpgradeBus.getRenderLayer() returned CUTOUT (:86-90), and the
            // `normal` tier's overlay is 44% fully transparent pixels.
            ItemBlockRenderTypes.setRenderLayer(ModBlocks.UPGRADE_BUS.get(), RenderType.cutout());
            // 0.29.0: the construct tool's selection highlight, which is drawn in the world. It is a FORGE-bus
            // listener rather than a mod-lifecycle one (world rendering is not a lifecycle event), and it is
            // registered from here so this client-only class is never loaded on a server. The preview renderer is
            // untouched: this is a separate class drawing its own outlines.
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    SelectionHighlight::onRenderLevelStage);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(SelectionHighlight::onLoggingOut);
        });
    }

    /**
     * C7：控制器自定义模型的渲染器注册。
     *
     * <p>geckolib 是**可选**依赖（mods.toml 里 mandatory=false）。没有它时这里直接返回，
     * {@link com.reborn.modularmachinery.client.geo.GeoRenderers} 不会被加载，因此它里面的
     * GeckoLib 类型不会触发 NoClassDefFoundError；机器照常显示普通方块模型。
     */
    public static void onRegisterRenderers(FMLClientSetupEvent event) {
        if (!ModList.get().isLoaded("geckolib")) {
            return;
        }
        event.enqueueWork(com.reborn.modularmachinery.client.geo.GeoRenderers::register);
    }
}
