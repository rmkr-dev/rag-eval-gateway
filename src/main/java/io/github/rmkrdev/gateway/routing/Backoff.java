package io.github.rmkrdev.gateway.routing;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** Exponential backoff with optional jitter, capped at {@code max-backoff}. */
final class Backoff {

    private final GatewayProperties.Retry retry;
    private final DoubleSupplier random;

    Backoff(GatewayProperties.Retry retry) {
        this(retry, () -> ThreadLocalRandom.current().nextDouble());
    }

    Backoff(GatewayProperties.Retry retry, DoubleSupplier random) {
        this.retry = retry;
        this.random = random;
    }

    /**
     * @param retryNumber 1 for the wait before the first retry, 2 for the second, and so on
     * @param retryAfter delay the provider asked for, or {@code null}
     */
    Duration delay(int retryNumber, Duration retryAfter) {
        long cap = retry.maxBackoff().toMillis();
        if (retryAfter != null) {
            return Duration.ofMillis(Math.min(retryAfter.toMillis(), cap));
        }
        double base = retry.initialBackoff().toMillis() * Math.pow(retry.multiplier(), retryNumber - 1);
        double capped = Math.min(base, cap);
        double jittered = capped * (1.0 - retry.jitter() * random.getAsDouble());
        return Duration.ofMillis(Math.round(jittered));
    }
}
