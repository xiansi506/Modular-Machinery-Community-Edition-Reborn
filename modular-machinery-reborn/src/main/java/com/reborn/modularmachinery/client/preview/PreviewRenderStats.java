package com.reborn.modularmachinery.client.preview;

/** A bounded window of actual preview draw timings, shared by the blueprint and JEI hosts. */
public final class PreviewRenderStats {
    private static final int WINDOW = 64;
    private static final long[] durations = new long[WINDOW];
    private static int samples, next, blocks;
    private static long lastRenderMillis;
    private PreviewRenderStats() { }

    public static synchronized void record(int visibleBlocks, long nanos, long nowMillis) {
        durations[next] = Math.max(0, nanos);
        next = (next + 1) % WINDOW;
        samples = Math.min(WINDOW, samples + 1);
        blocks = Math.max(0, visibleBlocks);
        lastRenderMillis = nowMillis;
    }

    public record Snapshot(int samples, int blocks, double averageMillis, double maximumMillis, long ageMillis) { }
    public static synchronized Snapshot snapshot(long nowMillis) {
        double total = 0, max = 0;
        for (int i = 0; i < samples; i++) { total += durations[i]; max = Math.max(max, durations[i]); }
        return new Snapshot(samples, blocks, samples == 0 ? 0 : total / samples / 1_000_000,
                max / 1_000_000, samples == 0 ? Long.MAX_VALUE : Math.max(0, nowMillis - lastRenderMillis));
    }

    public static synchronized void clear() { samples = next = blocks = 0; lastRenderMillis = 0; }
}
