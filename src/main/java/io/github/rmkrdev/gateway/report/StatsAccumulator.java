package io.github.rmkrdev.gateway.report;

import java.math.BigDecimal;
import java.util.Arrays;

/** Adds calls up into {@link Stats}, keeping latencies so percentiles are exact. */
final class StatsAccumulator {

    private final String currency;
    private long calls;
    private long errors;
    private long promptTokens;
    private long completionTokens;
    private long cacheReadTokens;
    private long cacheWriteTokens;
    private long unpriced;
    private BigDecimal cost = BigDecimal.ZERO;
    private final Samples total = new Samples();
    private final Samples ttft = new Samples();

    StatsAccumulator(String currency) {
        this.currency = currency;
    }

    void add(ReportRow row) {
        calls++;
        promptTokens += row.promptTokens();
        completionTokens += row.completionTokens();
        cacheReadTokens += row.cacheReadTokens() == null ? 0 : row.cacheReadTokens();
        cacheWriteTokens += row.cacheWriteTokens() == null ? 0 : row.cacheWriteTokens();
        if (row.cost() != null && currency.equals(row.currency())) {
            cost = cost.add(row.cost());
        } else if (row.cost() != null || row.promptTokens() + row.completionTokens() > 0) {
            // used tokens but has no price, or was priced in another currency
            unpriced++;
        }
        if (!row.ok()) {
            errors++;
            return;
        }
        total.add(row.totalMs());
        if (row.timeToFirstTokenMs() != null) {
            ttft.add(row.timeToFirstTokenMs());
        }
    }

    long calls() {
        return calls;
    }

    Stats stats() {
        return new Stats(calls, errors, promptTokens, completionTokens, cacheReadTokens, cacheWriteTokens,
                cost.signum() == 0 ? BigDecimal.ZERO : cost.stripTrailingZeros(), unpriced,
                total.percentiles(), ttft.percentiles());
    }

    /** Growable array of latencies. */
    private static final class Samples {

        private long[] values = new long[16];
        private int size;

        void add(long value) {
            if (size == values.length) {
                values = Arrays.copyOf(values, size * 2);
            }
            values[size++] = value;
        }

        Percentiles percentiles() {
            return Percentiles.of(values, size);
        }
    }
}
