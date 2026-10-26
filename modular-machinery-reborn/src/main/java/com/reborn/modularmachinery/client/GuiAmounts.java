package com.reborn.modularmachinery.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Quantities used by small gauges and labels. Hover text always keeps the exact value. */
public final class GuiAmounts {
    private static final String[] SUFFIXES = {"", "k", "M", "G", "T", "P", "E"};
    private GuiAmounts() { }

    public static String compact(long amount) {
        if (amount < 1000) return Long.toString(Math.max(0, amount));
        int unit = 0;
        BigDecimal value = BigDecimal.valueOf(amount);
        while (value.compareTo(BigDecimal.valueOf(1000)) >= 0 && unit < SUFFIXES.length - 1) {
            value = value.movePointLeft(3);
            unit++;
        }
        return value.setScale(value.compareTo(BigDecimal.TEN) < 0 ? 1 : 0, RoundingMode.DOWN)
                .stripTrailingZeros().toPlainString() + SUFFIXES[unit];
    }

    public static String exact(long amount) { return String.format(Locale.ROOT, "%,d", amount); }

    public static int filledPixels(long amount, long capacity, int height) {
        if (amount <= 0 || capacity <= 0 || height <= 0) return 0;
        return Math.max(1, (int) Math.floor(Math.min(1.0, (double) amount / capacity) * height));
    }
}
