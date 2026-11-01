package io.github.rmkrdev.gateway.limits;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * Per-client request and token rates, as token buckets that refill every minute. Nothing is
 * limited until {@code default} or a client sets a rate.
 *
 * <pre>
 * gateway:
 *   rate-limits:
 *     default:
 *       requests-per-minute: 60
 *     clients:
 *       billing-api:
 *         requests-per-minute: 600
 *         tokens-per-minute: 400000
 * </pre>
 *
 * @param enabled check rates at all; off skips every check
 * @param defaults rates for every client not listed under {@code clients}
 * @param clients rates by client name, as recorded for calls; an entry replaces the default
 *     completely
 */
@Validated
@ConfigurationProperties("gateway.rate-limits")
public record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @Name("default") @Valid @DefaultValue Limit defaults,
        @DefaultValue Map<String, @Valid Limit> clients) {

    /**
     * Rates for one client. Leave a field out for no limit of that kind. A full bucket allows
     * a burst of one minute's worth.
     *
     * @param requestsPerMinute chat calls per minute
     * @param tokensPerMinute prompt plus completion tokens per minute, counted when each call
     *     ends; a call is refused while the bucket is empty
     * @param unlimited no rate limit at all, even when the default has one
     */
    public record Limit(
            @Positive Long requestsPerMinute,
            @Positive Long tokensPerMinute,
            boolean unlimited) {

        public boolean limitsAnything() {
            return !unlimited && (requestsPerMinute != null || tokensPerMinute != null);
        }
    }

    /** Whether any client can be refused for its rate. */
    public boolean active() {
        return enabled && (defaults.limitsAnything()
                || clients.values().stream().anyMatch(Limit::limitsAnything));
    }
}
