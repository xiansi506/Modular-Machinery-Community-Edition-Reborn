package com.reborn.modularmachinery.energy;

/** Container data is signed 16-bit on the wire; four words carry one non-negative long exactly. */
public final class EnergyMenuValues {
    private EnergyMenuValues() {}
    public static int word(long value, int index) { return (int) (value >>> (index * 16)) & 0xFFFF; }
    public static long combine(int a, int b, int c, int d) {
        return (a & 0xFFFFL) | (b & 0xFFFFL) << 16 | (c & 0xFFFFL) << 32 | (d & 0x7FFFL) << 48;
    }
}
