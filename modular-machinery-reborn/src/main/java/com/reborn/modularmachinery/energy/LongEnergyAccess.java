package com.reborn.modularmachinery.energy;

import net.minecraftforge.energy.IEnergyStorage;

/** Exact long values for native recipe ports and optional long-energy APIs; FE remains int per call. */
public interface LongEnergyAccess extends IEnergyStorage {
    long receiveEnergyL(long amount, boolean simulate);
    long extractEnergyL(long amount, boolean simulate);
    long getEnergyStoredL();
    long getMaxEnergyStoredL();
    @Override default int receiveEnergy(int amount, boolean simulate) { return (int) receiveEnergyL(Math.max(0, amount), simulate); }
    @Override default int extractEnergy(int amount, boolean simulate) { return (int) extractEnergyL(Math.max(0, amount), simulate); }
    @Override default int getEnergyStored() { return EnergyAccess.asInt(getEnergyStoredL()); }
    @Override default int getMaxEnergyStored() { return EnergyAccess.asInt(getMaxEnergyStoredL()); }
}
