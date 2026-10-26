package com.reborn.modularmachinery.client.geo;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import java.util.HashMap;
import java.util.Map;

/** Transparent bones blend without writing depth; emissive bones also bypass directional lighting. */
public final class MachineModelRenderTypes extends RenderType {
    private record Key(ResourceLocation texture, BoneRenderStyle style) {}
    private static final Map<Key, RenderType> TYPES = new HashMap<>();
    private MachineModelRenderTypes() {
        super("machine_model", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, false, false, () -> {}, () -> {});
    }
    public static RenderType get(ResourceLocation texture, BoneRenderStyle style) {
        return TYPES.computeIfAbsent(new Key(texture, style), key -> create("machine_model_" + style,
                DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256, true, style.transparent(),
                CompositeState.builder()
                        .setShaderState(style.emissive() ? RENDERTYPE_ENTITY_TRANSLUCENT_EMISSIVE_SHADER : RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                        .setTextureState(new TextureStateShard(texture, false, false))
                        .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(style.emissive() ? NO_LIGHTMAP : LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .setLayeringState(style.emissive() ? POLYGON_OFFSET_LAYERING : NO_LAYERING)
                        .setWriteMaskState(style.transparent() ? COLOR_WRITE : COLOR_DEPTH_WRITE)
                        .createCompositeState(false)));
    }
    public static void clear() { TYPES.clear(); }
}
