package com.reborn.modularmachinery.client.preview;

import com.mojang.blaze3d.vertex.PoseStack;
import com.reborn.modularmachinery.ModularMachineryReborn;
import com.reborn.modularmachinery.machine.StructurePreview;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.client.model.data.ModelData;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.*;

/** Builds detached tiles in two passes so renderers can query the other blocks of the visible scene. */
public final class PreviewBlockEntities {
    private static final int MAX_SCENES = 8;
    private static final Map<StructurePreview, PreviewBlockEntities> CACHE = new LinkedHashMap<>(8, .75F, true);
    private final WeakReference<Level> context;
    private final PreviewLevel level;
    private final List<StructurePreview.Block> blocks;
    private final List<BlockState> states;
    private final Set<BlockPos> failedRenders = new HashSet<>();

    public PreviewBlockEntities(PreviewLevel level, List<StructurePreview.Block> blocks, int variant) {
        this(level, null, blocks, variant);
    }

    private PreviewBlockEntities(PreviewLevel level, @Nullable Level context,
                                 List<StructurePreview.Block> blocks, int variant) {
        this.level = level;
        this.context = new WeakReference<>(context);
        this.blocks = List.copyOf(blocks);
        this.states = blocks.stream().map(block -> block.variantState(variant)).toList();
        level.setScene(blocks, variant);
        // Register every tile before loading any tag: some modded load methods query a neighboring tile.
        for (int i = 0; i < blocks.size(); i++) {
            BlockState state = states.get(i);
            if (!(state.getBlock() instanceof EntityBlock factory)) continue;
            try {
                BlockEntity tile = factory.newBlockEntity(blocks.get(i).pos(), state);
                if (tile == null || !tile.getType().isValid(state)) continue;
                tile.setLevel(level);
                level.addPreviewEntity(tile);
            } catch (RuntimeException | LinkageError error) {
                report("create", blocks.get(i).pos(), error);
            }
        }
        for (var block : blocks) {
            BlockEntity tile = level.getBlockEntity(block.pos());
            CompoundTag nbt = block.previewNbt();
            if (tile == null || nbt == null) continue;
            try {
                // Type and coordinates belong to the chosen state, not to metadata copied from a save.
                CompoundTag data = tile.saveWithFullMetadata();
                String id = data.getString("id");
                data.merge(nbt);
                data.putString("id", id);
                data.putInt("x", block.pos().getX());
                data.putInt("y", block.pos().getY());
                data.putInt("z", block.pos().getZ());
                tile.load(data);
            } catch (RuntimeException | LinkageError error) {
                level.discardPreviewEntity(block.pos());
                report("load NBT", block.pos(), error);
            }
        }
    }

    public PreviewLevel level() { return level; }
    @Nullable public BlockEntity entity(BlockPos pos) { return level.getBlockEntity(pos); }

    /** Reuse entities across frames; rebuild only on a changed scene, visible slice or effective variant. */
    public static PreviewBlockEntities prepare(StructurePreview preview, List<StructurePreview.Block> blocks,
                                               int variant, Level context) {
        PreviewBlockEntities scene = CACHE.get(preview);
        boolean matches = scene != null && scene.context.get() == context && scene.blocks.equals(blocks);
        if (matches) for (int i = 0; i < blocks.size(); i++) {
            if (!scene.states.get(i).equals(blocks.get(i).variantState(variant))) { matches = false; break; }
        }
        if (!matches) {
            scene = new PreviewBlockEntities(new PreviewLevel(context), context, blocks, variant);
            CACHE.put(preview, scene);
            if (CACHE.size() > MAX_SCENES) CACHE.remove(CACHE.keySet().iterator().next());
        }
        return scene;
    }

    public ModelData modelData(BlockPos pos) {
        BlockEntity tile = entity(pos);
        if (tile == null || failedRenders.contains(pos)) return ModelData.EMPTY;
        try { return tile.getModelData(); }
        catch (RuntimeException | LinkageError error) { failRender(pos, error); return ModelData.EMPTY; }
    }

    public boolean hasRenderer(BlockPos pos, BlockEntityRenderDispatcher dispatcher) {
        BlockEntity tile = entity(pos);
        return tile != null && !failedRenders.contains(pos) && dispatcher.getRenderer(tile) != null;
    }

    /** GUI rendering must not apply the real world's camera distance culling or invoke entity tickers. */
    public void render(BlockPos pos, PoseStack pose, MultiBufferSource buffers, int light,
                       BlockEntityRenderDispatcher dispatcher) {
        if (!hasRenderer(pos, dispatcher)) return;
        // A third-party renderer may throw after pushing a pose. Give it its own stack so even an
        // unbalanced failure cannot corrupt the caller's camera or the next block's transform.
        PoseStack entityPose = new PoseStack();
        entityPose.last().pose().set(pose.last().pose());
        entityPose.last().normal().set(pose.last().normal());
        try {
            dispatcher.renderItem(entity(pos), entityPose, buffers, light, OverlayTexture.NO_OVERLAY);
        } catch (RuntimeException | LinkageError error) {
            failRender(pos, error);
        }
    }

    private void failRender(BlockPos pos, Throwable error) {
        if (failedRenders.add(pos.immutable())) report("render", pos, error);
    }
    private static void report(String action, BlockPos pos, Throwable error) {
        ModularMachineryReborn.LOGGER.warn("Preview could not {} block entity at {}; using block-model fallback", action, pos, error);
    }
    public static void clear() { CACHE.clear(); }
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) { clear(); }

    @Mod.EventBusSubscriber(modid = ModularMachineryReborn.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ReloadListener {
        @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> clear());
        }
    }
}
