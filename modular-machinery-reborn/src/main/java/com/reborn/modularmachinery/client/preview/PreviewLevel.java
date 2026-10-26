package com.reborn.modularmachinery.client.preview;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.*;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** An unticked, read-only scene. No chunk, entity or world mutation reaches the player's level. */
public final class PreviewLevel extends Level {
    private final Map<BlockPos, BlockState> states = new HashMap<>();
    private final Map<BlockPos, BlockEntity> tiles = new HashMap<>();
    private final Scoreboard scoreboard = new Scoreboard();
    private final RecipeManager recipes = new RecipeManager();
    private final LevelEntityGetter<Entity> entities = new LevelEntityGetterAdapter<>(new EntityLookup<>(),
            new EntitySectionStorage<>(Entity.class, ignored -> Visibility.HIDDEN));
    private final ChunkSource chunks;

    public PreviewLevel(Level context) {
        this(context.registryAccess(), context.dimensionTypeRegistration(), context.dimension());
    }

    /** Registry-only constructor also permits testing scenes without Minecraft or a live world. */
    public PreviewLevel(RegistryAccess registries, Holder<DimensionType> type, ResourceKey<Level> dimension) {
        super(new ClientLevel.ClientLevelData(Difficulty.PEACEFUL, false, false), dimension, registries,
                type, () -> InactiveProfiler.INSTANCE, true, false, 0L, 0);
        chunks = new ChunkSource() {
            private final LevelLightEngine light = new LevelLightEngine(this, false, false);
            private final Map<Long, LevelChunk> views = new java.util.LinkedHashMap<>(16, .75F, true);
            @Override public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean create) {
                long key = ChunkPos.asLong(x, z);
                LevelChunk cached = views.get(key);
                if (cached != null) return cached;
                // Views contain no terrain storage and query only this scene's maps, including neighbors.
                LevelChunk view = new EmptyLevelChunk(PreviewLevel.this, new ChunkPos(x, z),
                        registries.registryOrThrow(Registries.BIOME).holders().findFirst().orElseThrow()) {
                    @Override public BlockState getBlockState(BlockPos pos) { return PreviewLevel.this.getBlockState(pos); }
                    @Override public FluidState getFluidState(BlockPos pos) { return PreviewLevel.this.getFluidState(pos); }
                    @Override public BlockEntity getBlockEntity(BlockPos pos, LevelChunk.EntityCreationType mode) {
                        return PreviewLevel.this.getBlockEntity(pos);
                    }
                };
                views.put(key, view);
                if (views.size() > 32) views.remove(views.keySet().iterator().next());
                return view;
            }
            @Override public void tick(BooleanSupplier budget, boolean tickChunks) {}
            @Override public String gatherStats() { return "preview"; }
            @Override public int getLoadedChunksCount() { return 0; }
            @Override public LevelLightEngine getLightEngine() { return light; }
            @Override public net.minecraft.world.level.BlockGetter getLevel() { return PreviewLevel.this; }
        };
    }

    void setScene(List<com.reborn.modularmachinery.machine.StructurePreview.Block> blocks, int variant) {
        tiles.clear(); states.clear();
        for (var block : blocks) states.put(block.pos(), block.variantState(variant));
    }
    void addPreviewEntity(BlockEntity entity) { tiles.put(entity.getBlockPos(), entity); }
    void discardPreviewEntity(BlockPos pos) { tiles.remove(pos); }

    @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
    @Override @Nullable public BlockEntity getBlockEntity(BlockPos pos) { return tiles.get(pos); }
    @Override public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursion) { return false; }
    @Override public boolean removeBlock(BlockPos pos, boolean moving) { return false; }
    @Override public boolean destroyBlock(BlockPos pos, boolean drops, Entity entity, int recursion) { return false; }
    @Override public void setBlockEntity(BlockEntity entity) {}
    @Override public void removeBlockEntity(BlockPos pos) {}
    @Override public void blockEntityChanged(BlockPos pos) {}
    @Override public void addBlockEntityTicker(net.minecraft.world.level.block.entity.TickingBlockEntity ticker) {}
    @Override public void addFreshBlockEntities(java.util.Collection<BlockEntity> entities) {}
    @Override public void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flags) {}
    @Override public void updateNeighborsAt(BlockPos pos, Block block) {}
    @Override public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, net.minecraft.core.Direction direction) {}
    @Override public void neighborChanged(BlockPos pos, Block block, BlockPos from) {}
    @Override public void playSeededSound(Player player, double x, double y, double z, Holder<SoundEvent> sound,
                                          SoundSource source, float volume, float pitch, long seed) {}
    @Override public void playSeededSound(Player player, Entity entity, Holder<SoundEvent> sound,
                                          SoundSource source, float volume, float pitch, long seed) {}
    @Override public void levelEvent(Player player, int event, BlockPos pos, int data) {}
    @Override public void gameEvent(GameEvent event, Vec3 pos, GameEvent.Context context) {}
    @Override public void blockEvent(BlockPos pos, Block block, int event, int data) {}
    @Override public String gatherChunkSourceStats() { return "preview"; }
    @Override public ChunkSource getChunkSource() { return chunks; }
    @Override public LevelTickAccess<Block> getBlockTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public LevelTickAccess<Fluid> getFluidTicks() { return BlackholeTickAccess.emptyLevelList(); }
    @Override public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) { return new DifficultyInstance(Difficulty.PEACEFUL, 0, 0, 0); }
    @Override public List<? extends Player> players() { return List.of(); }
    @Override public Entity getEntity(int id) { return null; }
    @Override protected LevelEntityGetter<Entity> getEntities() { return entities; }
    @Override public MapItemSavedData getMapData(String id) { return null; }
    @Override public void setMapData(String id, MapItemSavedData data) {}
    @Override public int getFreeMapId() { return 0; }
    @Override public void destroyBlockProgress(int id, BlockPos pos, int progress) {}
    @Override public Scoreboard getScoreboard() { return scoreboard; }
    @Override public RecipeManager getRecipeManager() { return recipes; }
    @Override public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos pos) { return 15; }
    @Override public int getRawBrightness(BlockPos pos, int darken) { return 15; }
    @Override public float getShade(net.minecraft.core.Direction face, boolean shade) { return 1.0F; }
    @Override public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() { return net.minecraft.world.flag.FeatureFlags.REGISTRY.allFlags(); }
    @Override public Holder<net.minecraft.world.level.biome.Biome> getUncachedNoiseBiome(int x, int y, int z) {
        return registryAccess().registryOrThrow(Registries.BIOME).holders().findFirst().orElseThrow();
    }
}
