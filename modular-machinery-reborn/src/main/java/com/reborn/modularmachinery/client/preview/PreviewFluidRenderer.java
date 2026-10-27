package com.reborn.modularmachinery.client.preview;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import com.reborn.modularmachinery.machine.StructurePreview;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraftforge.client.model.pipeline.VertexConsumerWrapper;

/** LiquidBlockRenderer emits section-local coordinates instead of transformed GUI vertices. */
public final class PreviewFluidRenderer extends RenderType {
    private PreviewFluidRenderer() {
        super("preview_fluid", DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 256, false, true, () -> {}, () -> {});
    }

    // The chunk translucent type can target the world's Fabulous framebuffer. A GUI always uses MAIN_TARGET.
    public static final RenderType TYPE = create("preview_fluid", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, 256, false, true, CompositeState.builder()
                    .setShaderState(RENDERTYPE_TRANSLUCENT_SHADER)
                    .setTextureState(new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY).setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP).setOutputState(MAIN_TARGET).createCompositeState(false));

    static void render(BlockRenderDispatcher dispatcher, BlockAndTintGetter level, StructurePreview.Block block,
                       int variant, PoseStack pose, MultiBufferSource buffers) {
        var state = block.variantState(variant);
        if (state.getFluidState().isEmpty()) return;
        pose.pushPose();
        try {
            pose.translate(block.pos().getX(), block.pos().getY(), block.pos().getZ());
            dispatcher.renderLiquid(block.pos(), level, transformed(buffers.getBuffer(TYPE), pose.last(), block.pos()),
                    state, state.getFluidState());
        } finally { pose.popPose(); }
    }

    public static VertexConsumer transformed(VertexConsumer target, PoseStack.Pose pose, BlockPos pos) {
        return new VertexConsumerWrapper(target) {
            @Override public VertexConsumer vertex(double x, double y, double z) {
                parent.vertex(pose.pose(), (float) (x - (pos.getX() & 15)),
                        (float) (y - (pos.getY() & 15)), (float) (z - (pos.getZ() & 15)));
                return this;
            }
            @Override public VertexConsumer normal(float x, float y, float z) {
                parent.normal(pose.normal(), x, y, z);
                return this;
            }
            @Override public VertexConsumer uv2(int u, int v) {
                parent.uv2(LightTexture.FULL_BRIGHT);
                return this;
            }
        };
    }
}
