package io.github.rmkrdev.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.call.CallOutcome;
import io.github.rmkrdev.gateway.limits.RateLimitProperties.Limit;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTests {

    private final AtomicLong nanos = new AtomicLong();
    private final InMemoryRateLimitStore store = new InMemoryRateLimitStore(nanos::get);

    private RateLimiter limiter(boolean enabled, Limit defaults, Map<String, Limit> clients) {
        return new RateLimiter(new RateLimitProperties(enabled, defaults, clients), store);
    }

    private static CallOutcome finished(String client, int tokens) {
        return new CallOutcome("gw-1", Instant.now(), client, "chat", "openai", "gpt-4o-mini", false, List.of(),
                new Usage(tokens, 0), "stop", Duration.ZERO, Duration.ZERO, null);
    }

    @Test
    void refusesTheRequestOverTheDefaultRate() {
        RateLimiter limiter = limiter(true, new Limit(2L, null, false), Map.of());
        limiter.admit("billing-api");
        limiter.admit("billing-api");

        LimitExceededException e = catchThrowableOfType(LimitExceededException.class,
                () -> limiter.admit("billing-api"));

        assertThat(e.code()).isEqualTo(LimitExceededException.RATE_LIMITED);
        assertThat(e.limit()).isEqualTo("requests-per-minute");
        assertThat(e.client()).isEqualTo("billing-api");
        assertThat(e.retryAfterSeconds()).isEqualTo(30);
        assertThat(e.resetsAt()).isNull();
        assertThat(e.getMessage()).isEqualTo("Client 'billing-api' is over its rate limit of 2 requests per minute");
    }

    @Test
    void eachClientHasItsOwnBucket() {
        RateLimiter limiter = limiter(true, new Limit(1L, null, false), Map.of());
        limiter.admit("a");

        assertThatNoException().isThrownBy(() -> limiter.admit("b"));
    }

    @Test
    void clientEntryReplacesTheDefaultAndMatchesAnyCase() {
        RateLimiter limiter = limiter(true, new Limit(1L, null, false),
                Map.of("Batch-Indexer", new Limit(null, null, true), "search", new Limit(3L, null, false)));

        for (int i = 0; i < 5; i++) {
            limiter.admit("batch-indexer");
        }
        limiter.admit("search");
        limiter.admit("search");
        limiter.admit("search");
        assertThat(catchThrowableOfType(LimitExceededException.class, () -> limiter.admit("search"))).isNotNull();
    }

    @Test
    void tokensAreCountedWhenTheCallEndsAndRefuseUntilRefilled() {
        RateLimiter limiter = limiter(true, new Limit(null, 1_000L, false), Map.of());
        limiter.admit("a");
        limiter.completed(finished("a", 1_000));

        LimitExceededException e = catchThrowableOfType(LimitExceededException.class, () -> limiter.admit("a"));
        assertThat(e.limit()).isEqualTo("tokens-per-minute");

        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThatNoException().isThrownBy(() -> limiter.admit("a"));
    }

    @Test
    void aTokenRefusalDoesNotUseARequest() {
        RateLimiter limiter = limiter(true, new Limit(1L, 10L, false), Map.of());
        limiter.completed(finished("a", 20));

        assertThat(catchThrowableOfType(LimitExceededException.class, () -> limiter.admit("a")).limit())
                .isEqualTo("tokens-per-minute");
        assertThat(store.tryTake("requests-per-minute:a", 1, 1)).isZero();
    }

    @Test
    void disabledOrUnsetLimitsNothing() {
        RateLimiter off = limiter(false, new Limit(1L, null, false), Map.of());
        RateLimiter unset = limiter(true, new Limit(null, null, false), Map.of());
        for (int i = 0; i < 5; i++) {
            off.admit("a");
            unset.admit("a");
        }
        assertThat(new RateLimitProperties(false, new Limit(1L, null, false), Map.of()).active()).isFalse();
        assertThat(new RateLimitProperties(true, new Limit(null, null, false), Map.of()).active()).isFalse();
        assertThat(new RateLimitProperties(true, new Limit(null, null, false),
                Map.of("a", new Limit(5L, null, false))).active()).isTrue();
    }
}
