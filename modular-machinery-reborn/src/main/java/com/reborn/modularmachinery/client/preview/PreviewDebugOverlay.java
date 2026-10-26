package com.reborn.modularmachinery.client.preview;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import java.util.Locale;

/** F3 statistics for this renderer; no legacy metadata or nonexistent private buffer pool is reported. */
public final class PreviewDebugOverlay {
    private PreviewDebugOverlay() { }
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (!Minecraft.getInstance().options.renderDebug) return;
        var sample = PreviewRenderStats.snapshot(Util.getMillis());
        if (sample.samples() == 0) return;
        event.getLeft().add("");
        event.getLeft().add(Component.translatable("gui.modular_machinery_reborn.preview.debug.blocks", sample.blocks(), sample.samples()).getString());
        event.getLeft().add(Component.translatable("gui.modular_machinery_reborn.preview.debug.time",
                String.format(Locale.ROOT, "%.2f", sample.averageMillis()), String.format(Locale.ROOT, "%.2f", sample.maximumMillis())).getString());
        if (sample.ageMillis() > 2000) event.getLeft().add(Component.translatable("gui.modular_machinery_reborn.preview.debug.last").getString());
    }
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) { PreviewRenderStats.clear(); }
}
