package com.reborn.modularmachinery.client;

import com.reborn.modularmachinery.block.ModBlocks;
import com.reborn.modularmachinery.client.preview.WorldGhostPreview;
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
            MenuScreens.register(ModMenus.MAGIC_HATCH.get(), MagicHatchScreen::new);
            for (var block : com.reborn.modularmachinery.magic.MagicBlocks.blocks()) ItemBlockRenderTypes.setRenderLayer(block, RenderType.cutout());
            // C4（0.73.0）：AE2 ME 物品总线。这个屏幕类不引用任何 appeng 类型，所以即使没装 AE2
            // 也可以安全注册；方块与方块实体只在 AE2 存在时才注册。
            MenuScreens.register(ModMenus.ME_ITEM_BUS.get(), MeItemBusScreen::new);
            // C4-II（0.96.0）：样板供应器窗口。屏幕类不引用 appeng 类型，无 AE2 时注册无害。
            MenuScreens.register(ModMenus.ME_PATTERN_PROVIDER.get(), MePatternProviderScreen::new);
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
            // 0.86.1（C4 复查）：ME 总线漏了同一件事——旧版 BlockMEMachineComponent.getRenderLayer()
            // 返回 CUTOUT（:53-54），BlockMEItemBus 继承它；本版 0.73.0 交付时只给并行控制器与升级总线
            // 设了渲染层。overlay_meinputbus/meoutputbus 各有 44% 全透明像素，solid 层把它们画成不透明
            // 黑块——世界里与物品栏里的总线都是花的。没装 AE2 时这两个 RegistryObject 是 null，必须先判。
            if (ModBlocks.AE2_LOADED) {
                ItemBlockRenderTypes.setRenderLayer(ModBlocks.ME_ITEM_INPUT_BLOCK.get(), RenderType.cutout());
                ItemBlockRenderTypes.setRenderLayer(ModBlocks.ME_ITEM_OUTPUT_BLOCK.get(), RenderType.cutout());
                // 0.96.5（C4-II）：样板供应器同病第四例——overlay_mepatternprovider 44% 全透明
                // （16×16 里 112 像素），solid 层画成黑块。名单与测量见迁移日志。
                ItemBlockRenderTypes.setRenderLayer(ModBlocks.ME_PATTERN_PROVIDER_BLOCK.get(), RenderType.cutout());
                ItemBlockRenderTypes.setRenderLayer(ModBlocks.ME_PATTERN_MIRROR_BLOCK.get(), RenderType.cutout());
            }
            // 0.29.0: the construct tool's selection highlight, which is drawn in the world. It is a FORGE-bus
            // listener rather than a mod-lifecycle one (world rendering is not a lifecycle event), and it is
            // registered from here so this client-only class is never loaded on a server. The preview renderer is
            // untouched: this is a separate class drawing its own outlines.
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    SelectionHighlight::onRenderLevelStage);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(SelectionHighlight::onLoggingOut);
            // 0.104.0（C6 第一片）：投影仪的世界幽灵预览，与选区高亮同一注册纪律——Forge 总线 +
            // 仅从本客户端入口注册，服务器永远不加载 WorldGhostPreview。
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    WorldGhostPreview::onRenderLevelStage);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(WorldGhostPreview::onClientTick);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(WorldGhostPreview::onLoggingOut);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(com.reborn.modularmachinery.client.preview.PreviewDebugOverlay::onDebugText);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(com.reborn.modularmachinery.client.preview.PreviewDebugOverlay::onLoggingOut);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(com.reborn.modularmachinery.client.preview.PreviewBlockEntities::onLoggingOut);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(WorldComponentHider::onClientTick);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(WorldComponentHider::onLoggingOut);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(MachineBloom::onRenderLevelStage);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(MachineBloom::onLoggingOut);
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
