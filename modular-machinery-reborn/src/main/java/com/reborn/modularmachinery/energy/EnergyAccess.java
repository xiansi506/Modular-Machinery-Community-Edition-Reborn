package com.reborn.modularmachinery.energy;

import net.minecraftforge.energy.IEnergyStorage;

public final class EnergyAccess {
    private EnergyAccess() {}
    public static int asInt(long value) { return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value)); }
    public static long add(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
    public static long stored(IEnergyStorage storage) { return storage instanceof LongEnergyAccess wide ? wide.getEnergyStoredL() : storage.getEnergyStored(); }
    public static long capacity(IEnergyStorage storage) { return storage instanceof LongEnergyAccess wide ? wide.getMaxEnergyStoredL() : storage.getMaxEnergyStored(); }
    public static long receive(IEnergyStorage storage, long amount, boolean simulate) {
        if (amount <= 0) return 0;
        long received = storage instanceof LongEnergyAccess wide ? wide.receiveEnergyL(amount, simulate) : storage.receiveEnergy(asInt(amount), simulate);
        return Math.max(0, Math.min(amount, received));
    }
    public static long extract(IEnergyStorage storage, long amount, boolean simulate) {
        if (amount <= 0) return 0;
        long extracted = storage instanceof LongEnergyAccess wide ? wide.extractEnergyL(amount, simulate) : storage.extractEnergy(asInt(amount), simulate);
        return Math.max(0, Math.min(amount, extracted));
    }
    /** One output budget, including long APIs; a destination's short execute is refunded. */
    public static long push(IEnergyStorage source, IEnergyStorage target, long limit) {
        if (target == null || source == target || !target.canReceive() || limit <= 0) return 0;
        long offered = Math.min(limit, stored(source));
        long accepted = receive(target, offered, true);
        long extracted = extract(source, accepted, false);
        long moved = receive(target, extracted, false);
        if (moved < extracted && receive(source, extracted - moved, false) != extracted - moved)
            throw new IllegalStateException("Energy destination refused transfer and source refused its refund");
        return moved;
    }
}
