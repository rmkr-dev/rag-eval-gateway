package io.github.rmkrdev.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class InMemoryRateLimitStoreTests {

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final InMemoryRateLimitStore store = new InMemoryRateLimitStore(nanos::get);

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    @Test
    void startsFullAndRefusesOnceEmpty() {
        for (int i = 0; i < 60; i++) {
            assertThat(store.tryTake("rpm:a", 60, 1)).isZero();
        }

        assertThat(store.tryTake("rpm:a", 60, 1)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void refillsEvenlyOverAMinute() {
        for (int i = 0; i < 60; i++) {
            store.tryTake("rpm:a", 60, 1);
        }
        advance(Duration.ofMillis(500));
        assertThat(store.tryTake("rpm:a", 60, 1)).isEqualTo(Duration.ofMillis(500));

        advance(Duration.ofMillis(500));
        assertThat(store.tryTake("rpm:a", 60, 1)).isZero();
    }

    @Test
    void neverRefillsAboveCapacity() {
        advance(Duration.ofHours(1));
        for (int i = 0; i < 10; i++) {
            assertThat(store.tryTake("rpm:b", 10, 1)).isZero();
        }
        assertThat(store.tryTake("rpm:b", 10, 1)).isPositive();
    }

    @Test
    void takeCanGoBelowZeroAndWaitsForTheDebt() {
        store.take("tpm:a", 1_000, 1_500);

        assertThat(store.untilAvailable("tpm:a", 1_000)).isGreaterThan(Duration.ofSeconds(29))
                .isLessThanOrEqualTo(Duration.ofSeconds(30).plusMillis(1));
        advance(Duration.ofSeconds(30));
        assertThat(store.untilAvailable("tpm:a", 1_000)).isLessThan(Duration.ofMillis(100));
        advance(Duration.ofMillis(100));
        assertThat(store.untilAvailable("tpm:a", 1_000)).isZero();
    }

    @Test
    void keysAreIndependent() {
        store.take("rpm:a", 1, 1);

        assertThat(store.tryTake("rpm:a", 1, 1)).isPositive();
        assertThat(store.tryTake("rpm:b", 1, 1)).isZero();
    }

    @Test
    void aChangedRateStartsAFreshBucket() {
        store.take("rpm:a", 1, 1);

        assertThat(store.tryTake("rpm:a", 5, 1)).isZero();
    }

    @Test
    void dropsFullBucketsWhenThereAreMany() {
        for (int i = 0; i <= 10_001; i++) {
            store.tryTake("rpm:client-" + i, 100, 1);
        }
        advance(Duration.ofMinutes(1));

        store.tryTake("rpm:late", 100, 1);

        assertThat(store.size()).isLessThan(10);
    }
}
