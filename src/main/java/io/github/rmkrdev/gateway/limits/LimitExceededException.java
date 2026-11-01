package io.github.rmkrdev.gateway.limits;

import java.time.Duration;
import java.time.Instant;

/**
 * A call refused before any provider was asked, because the client is over its budget or its
 * rate. Answered with {@code 429} and {@code Retry-After}.
 */
public class LimitExceededException extends RuntimeException {

    /** Problem {@code code} for a used-up budget. */
    public static final String BUDGET_EXCEEDED = "budget_exceeded";

    /** Problem {@code code} for a rate limit. */
    public static final String RATE_LIMITED = "rate_limited";

    private final String code;
    private final String limit;
    private final String client;
    private final Duration retryAfter;
    private final Instant resetsAt;

    public LimitExceededException(String code, String limit, String client, Duration retryAfter,
            Instant resetsAt, String message) {
        super(message);
        this.code = code;
        this.limit = limit;
        this.client = client;
        this.retryAfter = retryAfter;
        this.resetsAt = resetsAt;
    }

    /** {@link #BUDGET_EXCEEDED} or {@link #RATE_LIMITED}. */
    public String code() {
        return code;
    }

    /** The limit that refused the call, for example {@code daily-cost} or {@code requests-per-minute}. */
    public String limit() {
        return limit;
    }

    public String client() {
        return client;
    }

    /** How long until a retry can succeed. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** When a budget period starts again, or {@code null} for rate limits. */
    public Instant resetsAt() {
        return resetsAt;
    }

    /** Whole seconds for the {@code Retry-After} header, at least one. */
    public long retryAfterSeconds() {
        long seconds = retryAfter.toSeconds() + (retryAfter.toNanosPart() > 0 ? 1 : 0);
        return Math.max(1, seconds);
    }
}
