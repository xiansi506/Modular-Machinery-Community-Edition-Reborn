package com.reborn.modularmachinery.machine;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.LevelEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Remembers which blocks changed since the last server tick, so a formed controller can skip its full
 * structure match when nothing inside its bounds moved.
 *
 * <p>This is the 1.20.1 counterpart of the original's {@code MMWorldEventListener}
 * ({@code github.kasuminova.mmce.common.world.MMWorldEventListener}). The original implemented
 * {@code IWorldEventListener} and recorded a bounding box per chunk on
 * {@code notifyBlockUpdate(world, pos, oldState, newState, flags)} when {@code (flags & 1) != 0} and the state
 * actually changed; 1.20.1 has no {@code IWorldEventListener}, so this class listens to the four Forge events
 * that carry a definite block position ({@link BlockEvent.NeighborNotifyEvent}, {@link BlockEvent.BreakEvent},
 * {@link BlockEvent.EntityPlaceEvent}, {@link BlockEvent.FluidPlaceBlockEvent}).
 *
 * <p><b>It records exact positions, not chunks.</b> A position outside every machine's bounds is a few bytes and
 * is dropped at the next {@link TickEvent.ServerTickEvent} START; a position inside a machine's bounds is
 * exactly what the original wanted. The difference from the original is therefore in the <i>shape</i> of the
 * superset, not in what a hit means: every recorded position was a real block change, so a hit is a real
 * reason to re-check. The one approximation is that {@code NeighborNotifyEvent} does not expose the original's
 * {@code flags & 1} filter, so a neighbour notification that would not have reached the original also records a
 * position — a false positive that costs one structure match, never a missed change. Client-side block events are
 * ignored: structure matching is authoritative on the server, and a {@code ClientLevel} shares the dimension key
 * with the server level in single player.
 *
 * <p>Cleared at the start of every server tick, like the original's
 * {@code ServerTickEvent(Phase.START) -> changedChunks.clear()}.
 */
public final class StructureChangeTracker {

    private static final Map<ResourceKey<Level>, Set<Long>> CHANGES = new ConcurrentHashMap<>();
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    private StructureChangeTracker() {
    }

    /**
     * Registers the four block listeners and the two lifecycle listeners on the Forge bus. Idempotent: the mod
     * constructor calls it once, and a future caller cannot install a second copy that doubles every mark.
     */
    public static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        MinecraftForge.EVENT_BUS.addListener(
                (BlockEvent.NeighborNotifyEvent event) -> record(event));
        MinecraftForge.EVENT_BUS.addListener(
                (BlockEvent.BreakEvent event) -> record(event));
        MinecraftForge.EVENT_BUS.addListener(
                (BlockEvent.EntityPlaceEvent event) -> record(event));
        MinecraftForge.EVENT_BUS.addListener(
                (BlockEvent.FluidPlaceBlockEvent event) -> record(event));
        MinecraftForge.EVENT_BUS.addListener(StructureChangeTracker::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(StructureChangeTracker::onLevelUnload);
    }

    /** Records one changed block. The dimension is part of the key because positions repeat across worlds. */
    public static void mark(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        CHANGES.computeIfAbsent(dimension, key -> ConcurrentHashMap.newKeySet()).add(pos.asLong());
    }

    /**
     * Whether any recorded change lies inside the inclusive box spanned by {@code a} and {@code b}. Both
     * corners may be given in either order; the loop compares the normalized bounds, so a caller does not have
     * to know which corner {@link MachinePattern#min()} returned.
     */
    public static boolean isAreaChanged(ResourceKey<Level> dimension, BlockPos a, BlockPos b) {
        if (dimension == null || a == null || b == null) {
            return false;
        }
        Set<Long> changed = CHANGES.get(dimension);
        if (changed == null || changed.isEmpty()) {
            return false;
        }
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX());
        int maxY = Math.max(a.getY(), b.getY());
        int maxZ = Math.max(a.getZ(), b.getZ());
        for (long packed : changed) {
            int x = BlockPos.getX(packed);
            int y = BlockPos.getY(packed);
            int z = BlockPos.getZ(packed);
            if (x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ) {
                return true;
            }
        }
        return false;
    }

    /** Forgets every dimension's changes. Called at {@link TickEvent.Phase#START}; also the test hook. */
    public static void clearAll() {
        CHANGES.clear();
    }

    /** Forgets one dimension's changes, used when that level unloads. */
    public static void clear(ResourceKey<Level> dimension) {
        if (dimension != null) {
            CHANGES.remove(dimension);
        }
    }

    private static void record(BlockEvent event) {
        // Structure matching is authoritative on the server. A ClientLevel shares the dimension key in single
        // player, so accepting it here would write into (and later clear) the same map the server controller reads.
        if (event.getLevel() instanceof ServerLevel level) {
            mark(level.dimension(), event.getPos());
        }
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            clearAll();
        }
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            clear(level.dimension());
        }
    }
}
