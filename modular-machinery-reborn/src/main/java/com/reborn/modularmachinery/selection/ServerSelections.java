package com.reborn.modularmachinery.selection;

import com.reborn.modularmachinery.network.SelectionSyncPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-owned selections are scoped to one player and one dimension. */
public final class ServerSelections {
    private record Bound(ResourceKey<Level> dimension, ConstructSession session) {}
    private static final Map<UUID, Bound> SELECTIONS = new HashMap<>();
    private ServerSelections() {}

    public static StructureSelection get(Player player) {
        return get(player.getUUID(), player.level().dimension());
    }

    static StructureSelection get(UUID player, ResourceKey<Level> dimension) {
        Bound bound = SELECTIONS.get(player);
        if (bound == null || !bound.dimension().equals(dimension)) {
            bound = new Bound(dimension, new ConstructSession());
            SELECTIONS.put(player, bound);
        }
        return bound.session().selection;
    }

    public static ConstructSession session(Player player) {
        get(player); return SELECTIONS.get(player.getUUID()).session();
    }

    public static StructureSelection toggle(Player player, BlockPos pos) {
        StructureSelection selection = get(player);
        selection.toggle(pos.immutable());
        return selection;
    }

    public static void purge(Player player) {
        if (player != null) SELECTIONS.remove(player.getUUID());
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) { purge(event.getEntity()); }
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) { clearAndSync(event.getEntity()); }
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) { clearAndSync(event.getEntity()); }
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) { SELECTIONS.clear(); }

    private static void clearAndSync(Player player) {
        purge(player);
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            ConstructToolService.sync(serverPlayer, false, 0, "", "");
        }
    }
}
