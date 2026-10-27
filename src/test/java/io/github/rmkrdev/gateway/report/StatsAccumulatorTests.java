package io.github.rmkrdev.gateway.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class StatsAccumulatorTests {

    private static ReportRow ok(long totalMs, Long ttftMs, String cost, String currency) {
        return new ReportRow(Instant.EPOCH, "c", "m", "p", true, 100, 20, 10, null,
                cost == null ? null : new BigDecimal(cost), currency, ttftMs, totalMs);
    }

    @Test
    void nearestRankPercentilesUseRealValues() {
        long[] values = LongStream.rangeClosed(1, 100).map(i -> 101 - i).toArray();

        Percentiles p = Percentiles.of(values, values.length);

        assertThat(p).isEqualTo(new Percentiles(50, 95, 99));
        assertThat(Percentiles.of(new long[] {7}, 1)).isEqualTo(new Percentiles(7, 7, 7));
        assertThat(Percentiles.of(new long[0], 0)).isNull();
    }

    @Test
    void sumsTokensAndCostAndKeepsLatencyToSuccessfulCalls() {
        StatsAccumulator acc = new StatsAccumulator("USD");
        for (int i = 1; i <= 40; i++) {
            acc.add(ok(i * 10L, i % 2 == 0 ? (long) i : null, "0.001", "USD"));
        }
        acc.add(ok(5, 5L, null, null));
        acc.add(ok(5, 5L, "1.0", "EUR"));
        acc.add(new ReportRow(Instant.EPOCH, "c", "m", null, false, 0, 0, null, null, null, null, null, 99_999));

        Stats stats = acc.stats();

        assertThat(stats.calls()).isEqualTo(43);
        assertThat(stats.errors()).isEqualTo(1);
        assertThat(stats.errorRate()).isEqualTo(1 / 43.0);
        assertThat(stats.promptTokens()).isEqualTo(4200);
        assertThat(stats.completionTokens()).isEqualTo(840);
        assertThat(stats.cacheReadTokens()).isEqualTo(420);
        assertThat(stats.cacheWriteTokens()).isZero();
        assertThat(stats.cost()).isEqualByComparingTo("0.04");
        // the unpriced call and the EUR call; the failed call used no tokens
        assertThat(stats.unpricedCalls()).isEqualTo(2);
        // 42 successful calls: 5, 5, 10, 20 ... 400; the failed call's 99,999 ms is left out
        assertThat(stats.totalMs().p99()).isEqualTo(400);
        assertThat(stats.totalMs().p50()).isEqualTo(190);
        assertThat(stats.ttftMs().p50()).isEqualTo(18);
    }

    @Test
    void emptyStatsHaveNoPercentiles() {
        Stats stats = new StatsAccumulator("USD").stats();

        assertThat(stats.calls()).isZero();
        assertThat(stats.errorRate()).isZero();
        assertThat(stats.cost()).isEqualByComparingTo("0");
        assertThat(stats.totalMs()).isNull();
        assertThat(stats.ttftMs()).isNull();
    }
}
