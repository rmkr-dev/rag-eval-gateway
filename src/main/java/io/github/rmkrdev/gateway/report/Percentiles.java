package io.github.rmkrdev.gateway.report;

import java.util.Arrays;

/**
 * Latency percentiles in milliseconds, nearest-rank: the smallest value with at least p% of the
 * samples at or below it, so every value is one a real call took.
 */
public record Percentiles(long p50, long p95, long p99) {

    /** Percentiles of {@code values}, or {@code null} when there are none. Sorts in place. */
    static Percentiles of(long[] values, int count) {
        if (count == 0) {
            return null;
        }
        Arrays.sort(values, 0, count);
        return new Percentiles(rank(values, count, 50), rank(values, count, 95), rank(values, count, 99));
    }

    private static long rank(long[] sorted, int count, int percent) {
        int index = (int) Math.ceil(percent / 100.0 * count) - 1;
        return sorted[Math.max(0, index)];
    }
}
