package com.reborn.modularmachinery.compat.magic;

import com.reborn.modularmachinery.magic.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import static com.reborn.modularmachinery.compat.magic.ApiReflection.*;

/** Reads actual environmental Aura. Full chunk NBT snapshots preserve drain-spot distribution on rollback. */
public final class AuraBackend implements MagicBackend {
    private final MagicHatchBlockEntity hatch;
    private final Class<?> api = type("de.ellpeck.naturesaura.api.aura.chunk.IAuraChunk");
    private final Class<?> auraType = type("de.ellpeck.naturesaura.api.aura.type.IAuraType");
    private static final Class<?>[] LOCATION = {Level.class, BlockPos.class};
    public AuraBackend(MagicHatchBlockEntity hatch) { this.hatch = hatch; }
    public MagicFamily family() { return MagicFamily.AURA; }
    public boolean input() { return hatch.input(); }
    private Object chunk() { return call(api, null,"getAuraChunk",LOCATION,hatch.getLevel(),hatch.getBlockPos()); }
    private String name(Object chunk) {
        if (chunk == null) return "";
        Object type = call(api,chunk,"getType",new Class<?>[0]);
        return type == null ? "" : call(auraType,type,"getName",new Class<?>[0]).toString();
    }
    public String[] variants() { String name = hatch.getLevel() == null ? "" : name(chunk()); return name.isEmpty() ? new String[0] : new String[]{name}; }
    @SuppressWarnings("unchecked") private static net.minecraftforge.common.util.INBTSerializable<CompoundTag> serializer(Object chunk) {
        return (net.minecraftforge.common.util.INBTSerializable<CompoundTag>) chunk;
    }
    private record ChunkKey(Level level, long pos) {}
    public Access access(String variant) {
        Level level = hatch.getLevel(); BlockPos pos = hatch.getBlockPos();
        if (level == null || level.isClientSide || !level.hasChunkAt(pos)) return null;
        Object chunk = chunk(); if (!name(chunk).equals(variant)) return null;
        return new Access() {
            public Object identity() { return new ChunkKey(level, new net.minecraft.world.level.ChunkPos(pos).toLong()); }
            public boolean valid() { return !hatch.isRemoved() && hatch.getLevel() == level && level.hasChunkAt(pos) && AuraBackend.this.chunk() == chunk && name(chunk).equals(variant); }
            public double amount() { return ((Number)call(api,null,"getAuraInArea",new Class<?>[]{Level.class,BlockPos.class,int.class},level,pos,1)).intValue(); }
            public double minimum() { return Integer.MIN_VALUE; }
            public double maximum() { return Integer.MAX_VALUE; }
            public double move(double delta, boolean simulate) {
                if (!valid() || !Double.isFinite(delta)) return 0;
                double n = delta >= 0 ? Math.min(Math.floor(delta),maximum()-amount()) : -Math.min(Math.floor(-delta),amount()-minimum());
                int count = (int)Math.min(Integer.MAX_VALUE,Math.abs(n));
                if (count <= 0) return 0;
                // Explicit false avoids the convenience storeAura overload stopping at zero.
                int moved = ((Number)call(api,chunk,n >= 0 ? "storeAura" : "drainAura",new Class<?>[]{BlockPos.class,int.class,boolean.class,boolean.class},pos,count,false,simulate)).intValue();
                return n >= 0 ? moved : -moved;
            }
            public Object snapshot() { return serializer(chunk).serializeNBT().copy(); }
            public boolean unchanged(Object snapshot) { return valid() && snapshot.equals(serializer(chunk).serializeNBT()); }
            public void restore(Object snapshot) { serializer(chunk).deserializeNBT(((CompoundTag)snapshot).copy()); call(api,chunk,"markDirty",new Class<?>[0]); }
        };
    }
}
