package com.reborn.modularmachinery.render;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.HashMap;
import java.util.Map;

/** Client-thread writes, immutable snapshots for the terrain worker threads. No client or optional mod types. */
public final class WorldVisibilityIndex {
    public record Snapshot(Object world, LongSet models, LongSet components) {
        public boolean matches(Object candidate) { return world != null && world == candidate; }
    }

    private volatile Snapshot snapshot = new Snapshot(null, LongSets.EMPTY_SET, LongSets.EMPTY_SET);
    private Map<Long, LongSet> owners = Map.of();

    public Snapshot snapshot() { return snapshot; }

    /** Each owner keeps its custom renderer; its ordinary block model is hidden. Parts are absolute positions. */
    public LongSet replace(Object world, Map<Long, LongSet> contributions) {
        Snapshot before = snapshot;
        if (before.world() == world && owners.equals(contributions)) return LongSets.EMPTY_SET;
        Map<Long, LongSet> frozen = new HashMap<>();
        LongSet models = new LongOpenHashSet();
        LongSet components = new LongOpenHashSet();
        contributions.forEach((owner, parts) -> {
            LongSet copy = LongSets.unmodifiable(new LongOpenHashSet(parts));
            frozen.put(owner, copy);
            models.add(owner.longValue());
            components.addAll(copy);
        });
        models.addAll(components);
        // A controller can also occupy another machine's pattern. Never suppress its custom renderer.
        for (long owner : frozen.keySet()) components.remove(owner);
        owners = Map.copyOf(frozen);
        Snapshot after = new Snapshot(world, LongSets.unmodifiable(models), LongSets.unmodifiable(components));
        snapshot = after;
        LongSet changed = new LongOpenHashSet();
        if (before.world() == world) {
            difference(before.models(), after.models(), changed);
            difference(before.components(), after.components(), changed);
        } else {
            changed.addAll(after.models());
        }
        LongSet sections = new LongOpenHashSet();
        for (long packed : changed) {
            int x = BlockPos.getX(packed), y = BlockPos.getY(packed), z = BlockPos.getZ(packed);
            // Rebuild adjacent sections too: hiding a block exposes its neighbors' faces.
            for (int sx = (x - 1) >> 4; sx <= (x + 1) >> 4; sx++)
                for (int sy = (y - 1) >> 4; sy <= (y + 1) >> 4; sy++)
                    for (int sz = (z - 1) >> 4; sz <= (z + 1) >> 4; sz++)
                        sections.add(SectionPos.asLong(sx, sy, sz));
        }
        return sections;
    }

    private static void difference(LongSet a, LongSet b, LongSet changed) {
        for (long pos : a) if (!b.contains(pos)) changed.add(pos);
        for (long pos : b) if (!a.contains(pos)) changed.add(pos);
    }
}
