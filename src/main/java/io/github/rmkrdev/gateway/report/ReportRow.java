package io.github.rmkrdev.gateway.report;

import java.math.BigDecimal;
import java.time.Instant;

/** The columns of one stored call that reports need. */
record ReportRow(
        Instant startedAt,
        String client,
        String model,
        String provider,
        boolean ok,
        int promptTokens,
        int completionTokens,
        Integer cacheReadTokens,
        Integer cacheWriteTokens,
        BigDecimal cost,
        String currency,
        Long timeToFirstTokenMs,
        long totalMs) {
}
