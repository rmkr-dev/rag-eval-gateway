package io.github.rmkrdev.gateway.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Totals for a set of calls.
 *
 * @param calls calls that started in the range
 * @param errors calls that ended in an error
 * @param promptTokens prompt tokens, cache reads and writes included
 * @param completionTokens completion tokens
 * @param cacheReadTokens prompt tokens read from provider caches
 * @param cacheWriteTokens prompt tokens written to provider caches
 * @param cost total cost in the report currency
 * @param unpricedCalls calls that used tokens but have no cost in the report currency
 * @param totalMs percentiles of total time over successful calls, or {@code null}
 * @param ttftMs percentiles of time to first token over successful calls, or {@code null}
 */
public record Stats(
        long calls,
        long errors,
        @JsonProperty("prompt_tokens") long promptTokens,
        @JsonProperty("completion_tokens") long completionTokens,
        @JsonProperty("cache_read_tokens") long cacheReadTokens,
        @JsonProperty("cache_write_tokens") long cacheWriteTokens,
        BigDecimal cost,
        @JsonProperty("unpriced_calls") long unpricedCalls,
        @JsonProperty("total_ms") Percentiles totalMs,
        @JsonProperty("ttft_ms") Percentiles ttftMs) {

    /** Share of calls that failed, between 0 and 1. */
    public double errorRate() {
        return calls == 0 ? 0 : (double) errors / calls;
    }
}
