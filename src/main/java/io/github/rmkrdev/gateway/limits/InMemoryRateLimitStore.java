package io.github.rmkrdev.gateway.limits;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * {@link RateLimitStore} in this JVM. Buckets that have refilled completely are dropped once
 * there are many of them, since a full bucket is the same as a new one.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    private static final double NANOS_PER_MINUTE = 60e9;
    private static final int SWEEP_ABOVE = 10_000;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier nanoTime;

    public InMemoryRateLimitStore() {
        this(System::nanoTime);
    }

    InMemoryRateLimitStore(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    @Override
    public Duration tryTake(String key, long perMinute, long permits) {
        return bucket(key, perMinute).tryTake(permits, nanoTime.getAsLong());
    }

    @Override
    public void take(String key, long perMinute, long permits) {
        bucket(key, perMinute).take(permits, nanoTime.getAsLong());
    }

    @Override
    public Duration untilAvailable(String key, long perMinute) {
        return bucket(key, perMinute).untilAvailable(nanoTime.getAsLong());
    }

    int size() {
        return buckets.size();
    }

    private Bucket bucket(String key, long perMinute) {
        if (buckets.size() > SWEEP_ABOVE) {
            long now = nanoTime.getAsLong();
            buckets.values().removeIf(bucket -> bucket.full(now));
        }
        return buckets.compute(key, (k, existing) ->
                existing != null && existing.capacity == perMinute ? existing : new Bucket(perMinute, nanoTime.getAsLong()));
    }

    private static final class Bucket {

        private final long capacity;
        private final double perNano;
        private double level;
        private long updatedAt;

        Bucket(long capacity, long now) {
            this.capacity = capacity;
            this.perNano = capacity / NANOS_PER_MINUTE;
            this.level = capacity;
            this.updatedAt = now;
        }

        synchronized Duration tryTake(long permits, long now) {
            refill(now);
            if (level >= permits) {
                level -= permits;
                return Duration.ZERO;
            }
            return wait(permits - level);
        }

        synchronized void take(long permits, long now) {
            refill(now);
            level -= permits;
        }

        synchronized Duration untilAvailable(long now) {
            refill(now);
            return level > 0 ? Duration.ZERO : wait(Math.nextUp(-level));
        }

        synchronized boolean full(long now) {
            refill(now);
            return level >= capacity;
        }

        private void refill(long now) {
            long elapsed = now - updatedAt;
            if (elapsed > 0) {
                level = Math.min(capacity, level + elapsed * perNano);
                updatedAt = now;
            }
        }

        private Duration wait(double missing) {
            return Duration.ofNanos((long) Math.ceil(missing / perNano));
        }
    }
}
