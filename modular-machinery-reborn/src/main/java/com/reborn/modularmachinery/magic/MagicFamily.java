package com.reborn.modularmachinery.magic;

public enum MagicFamily {
    MANA("botania", 0xFF33BCE8), AURA("naturesaura", 0xFF58BA66),
    LIFEESSENCE("bloodmagic", 0xFFB82635), WILL("bloodmagic", 0xFF82B4DD),
    AIR("pneumaticcraft", 0xFF72CADD), EMBER("embers", 0xFFFF9A32);
    public final String modId;
    public final int color;
    MagicFamily(String modId, int color) { this.modId = modId; this.color = color; }
    public String path() { return this == AIR ? "compressed_air" : name().toLowerCase(java.util.Locale.ROOT); }
    public boolean integerUnits() { return this != WILL && this != EMBER; }
}
