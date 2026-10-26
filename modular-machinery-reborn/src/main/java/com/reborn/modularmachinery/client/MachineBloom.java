package com.reborn.modularmachinery.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.reborn.modularmachinery.ModularMachineryReborn;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** Deferred emissive mask, depth tested against the world, followed by two blur passes and composition. */
public final class MachineBloom {
    private static final ResourceLocation CHAIN = new ResourceLocation(ModularMachineryReborn.MOD_ID, "shaders/post/machine_bloom.json");
    private static final Map<ResourceLocation, MultiBufferSource.BufferSource> BUFFERS = new LinkedHashMap<>();
    private static final List<BufferBuilder> POOL = new ArrayList<>();
    private static PostChain chain;
    private static RenderTarget main;
    private static int width, height;
    private static boolean pending, failed;
    private static Matrix4f projection, modelView;
    private static VertexSorting sorting;
    private MachineBloom() {}

    public static VertexConsumer capture(ResourceLocation texture) {
        if (failed) return null;
        if (!BUFFERS.containsKey(texture) && BUFFERS.size() >= 64) return null;
        if (!pending) {
            projection = new Matrix4f(RenderSystem.getProjectionMatrix());
            modelView = new Matrix4f(RenderSystem.getModelViewMatrix());
            sorting = RenderSystem.getVertexSorting();
            pending = true;
        }
        // One buffer per texture prevents an automatic flush into the world's current framebuffer.
        MultiBufferSource.BufferSource buffers = BUFFERS.computeIfAbsent(texture, ignored -> {
            int index = BUFFERS.size();
            if (POOL.size() <= index) POOL.add(new BufferBuilder(256));
            return MultiBufferSource.immediate(POOL.get(index));
        });
        return buffers.getBuffer(com.reborn.modularmachinery.client.geo.MachineModelRenderTypes.get(texture,
                new com.reborn.modularmachinery.client.geo.BoneRenderStyle(true, true)));
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !pending) return;
        Minecraft minecraft = Minecraft.getInstance();
        RenderTarget target = minecraft.getMainRenderTarget();
        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting oldSorting = RenderSystem.getVertexSorting();
        var stack = RenderSystem.getModelViewStack();
        stack.pushPose();
        RenderTarget mask = null;
        boolean depthCopied = false;
        try {
            if (chain == null || main != target) {
                if (chain != null) chain.close();
                chain = new PostChain(minecraft.getTextureManager(), minecraft.getResourceManager(), target, CHAIN);
                main = target; width = height = 0;
            }
            if (width != target.width || height != target.height) {
                chain.resize(target.width, target.height); width = target.width; height = target.height;
            }
            mask = chain.getTempTarget("mask");
            mask.setClearColor(0, 0, 0, 0);
            mask.clear(Minecraft.ON_OSX);
            mask.copyDepthFrom(target);
            depthCopied = true;
            mask.bindWrite(true);
            RenderSystem.setProjectionMatrix(projection, sorting);
            stack.last().pose().set(modelView);
            RenderSystem.applyModelViewMatrix();
            BUFFERS.values().forEach(MultiBufferSource.BufferSource::endBatch);
            chain.process(event.getPartialTick());
        } catch (Exception | LinkageError error) {
            failed = true;
            ModularMachineryReborn.LOGGER.warn("Machine bloom disabled until resource reload; emissive lighting remains available", error);
        } finally {
            // PostPass clears output depth. Restore it for later renderers and shader pipelines.
            if (depthCopied) target.copyDepthFrom(mask);
            target.bindWrite(true);
            RenderSystem.setProjectionMatrix(oldProjection, oldSorting);
            stack.popPose();
            RenderSystem.applyModelViewMatrix();
            pending = false;
            discard();
            if (failed) { if (chain != null) chain.close(); chain = null; main = null; }
        }
    }
    private static void discard() {
        // Retain bounded native buffers across reloads; release unfinished batches without drawing them.
        for (BufferBuilder builder : POOL) if (builder.building()) builder.end().release();
        BUFFERS.clear();
    }
    public static void reset() {
        RenderSystem.recordRenderCall(() -> {
            if (chain != null) chain.close();
            chain = null; main = null; discard(); pending = failed = false;
        });
    }
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) { reset(); }
}
