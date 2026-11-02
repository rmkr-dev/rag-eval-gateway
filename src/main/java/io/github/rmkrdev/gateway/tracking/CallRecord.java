package io.github.rmkrdev.gateway.tracking;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One stored call: who asked, what served it, the tokens, the cost and the latency. Never holds
 * prompt or answer text.
 *
 * @param id gateway call id
 * @param startedAt when the gateway received the call
 * @param client caller identity
 * @param model alias the caller asked for
 * @param provider provider that answered, or {@code null}
 * @param providerModel model name on that provider, or {@code null}
 * @param streamed whether the answer was streamed
 * @param status {@code ok} or {@code error}
 * @param error failure label, or {@code null}
 * @param finishReason why generation stopped, or {@code null}
 * @param promptTokens prompt tokens, cache reads and writes included
 * @param completionTokens completion tokens
 * @param cacheReadTokens prompt tokens read from the provider cache, or {@code null}
 * @param cacheWriteTokens prompt tokens written to the provider cache, or {@code null}
 * @param cost cost in {@code currency}, or {@code null} when no price matched
 * @param currency ISO 4217 code, or {@code null} when not priced
 * @param priceVersion {@code effective-from} of the price used, or {@code null}
 * @param timeToFirstTokenMs milliseconds to the first token, or {@code null}
 * @param totalMs milliseconds from arrival to the end of the answer
 * @param failedAttempts provider calls that failed before the answer
 * @param piiRedactions personal data values found in the prompt; never the values themselves
 */
public record CallRecord(
        String id,
        @JsonProperty("started_at") Instant startedAt,
        String client,
        String model,
        String provider,
        @JsonProperty("provider_model") String providerModel,
        boolean streamed,
        String status,
        String error,
        @JsonProperty("finish_reason") String finishReason,
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("cache_read_tokens") Integer cacheReadTokens,
        @JsonProperty("cache_write_tokens") Integer cacheWriteTokens,
        BigDecimal cost,
        String currency,
        @JsonProperty("price_version") String priceVersion,
        @JsonProperty("ttft_ms") Long timeToFirstTokenMs,
        @JsonProperty("total_ms") long totalMs,
        @JsonProperty("failed_attempts") int failedAttempts,
        @JsonProperty("pii_redactions") int piiRedactions) {

    /** A record without personal data found in the prompt. */
    public CallRecord(String id, Instant startedAt, String client, String model, String provider,
            String providerModel, boolean streamed, String status, String error, String finishReason,
            int promptTokens, int completionTokens, Integer cacheReadTokens, Integer cacheWriteTokens,
            BigDecimal cost, String currency, String priceVersion, Long timeToFirstTokenMs, long totalMs,
            int failedAttempts) {
        this(id, startedAt, client, model, provider, providerModel, streamed, status, error, finishReason,
                promptTokens, completionTokens, cacheReadTokens, cacheWriteTokens, cost, currency, priceVersion,
                timeToFirstTokenMs, totalMs, failedAttempts, 0);
    }

    public static final String OK = "ok";
    public static final String ERROR = "error";
}
