package com.reborn.modularmachinery.config;

import java.text.DecimalFormat;

/**
 * The two number formats this mod prints energy with, both copied from the original's {@code MiscUtils}.
 *
 * <p>The original had two and used them in different places, so keeping them apart is part of matching it:
 *
 * <ul>
 *   <li>{@link #abbreviated} is {@code MiscUtils.formatNumber} — a suffix ({@code K} / {@code M} / {@code G} / …)
 *       for anything at or above a thousand. It is what the energy hatch's own GUI, and the JEI recipe preview,
 *       printed their numbers with.</li>
 *   <li>{@link #grouped} is {@code MiscUtils.formatDecimal} — a thousands separator, no suffix. It is what the
 *       <b>block tooltip</b> used for a hatch's capacity and transfer rate.</li>
 * </ul>
 *
 * <p><b>The abbreviation's arithmetic is the original's, quirk included.</b> Its {@code M}, {@code G}, {@code T},
 * {@code P} and {@code E} branches divide by the prefix below them <i>and then</i> by 1000, so
 * {@code 5_000_000} prints as {@code 5.0M} while {@code 5_000_000_000} prints as {@code 5.0G} — the same string,
 * a factor of a thousand apart, because every branch lands on "value in thousands of the next unit". That is
 * upstream behaviour and is reproduced rather than corrected: this is a display string, the project's rule is to
 * mirror the original where it is observable, and a "fixed" version would print different text than the mod it
 * reimplements. Anything that needs the true magnitude reads the {@code long}, not this string.
 */
public final class DisplayNumbers {

    /** {@code MiscUtils.DECIMAL_FORMAT}: the original's own pattern. */
    private static final DecimalFormat GROUPED = new DecimalFormat("#,###");

    /**
     * {@code MiscUtils.formatNumber}: abbreviate at a thousand and above.
     *
     * <p>See the class comment for why the larger branches are kept as they are.
     */
    public static String abbreviated(long value) {
        if (value < 1_000L) {
            return String.valueOf(value);
        } else if (value < 1_000_000L) {
            return (double) Math.round((double) value) / 1000.0 + "K";
        } else if (value < 1_000_000_000L) {
            return (double) Math.round((double) (value / 1_000L)) / 1000.0 + "M";
        } else if (value < 1_000_000_000_000L) {
            return (double) Math.round((double) (value / 1_000_000L)) / 1000.0 + "G";
        } else if (value < 1_000_000_000_000_000L) {
            return (double) Math.round((double) (value / 1_000_000_000L)) / 1000.0 + "T";
        } else if (value < 1_000_000_000_000_000_000L) {
            return (double) Math.round((double) (value / 1_000_000_000_000L)) / 1000.0 + "P";
        }
        return (double) Math.round((double) (value / 1_000_000_000_000_000L)) / 1000.0 + "E";
    }

    /** {@code MiscUtils.formatDecimal}: a thousands separator, and no suffix. */
    public static String grouped(double value) {
        return GROUPED.format(value);
    }

    private DisplayNumbers() {
    }
}
