package com.reborn.modularmachinery.magic;

public enum MagicFamily {
    MANA("botania", 0xFF33BCE8), AURA("naturesaura", 0xFF58BA66),
    LIFEESSENCE("bloodmagic", 0xFFB82635), WILL("bloodmagic", 0xFF82B4DD);
    public final String modId;
    public final int color;
    MagicFamily(String modId, int color) { this.modId = modId; this.color = color; }
    public String path() { return name().toLowerCase(java.util.Locale.ROOT); }
}
