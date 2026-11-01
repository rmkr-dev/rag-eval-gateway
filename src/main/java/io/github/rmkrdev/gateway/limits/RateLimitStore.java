package io.github.rmkrdev.gateway.limits;

import java.time.Duration;

/**
 * Token buckets that refill evenly over a minute up to {@code perMinute}. The default keeps
 * them in memory, so each replica counts on its own. Register a bean of this type to share
 * buckets across replicas, for example in Redis or a database.
 */
public interface RateLimitStore {

    /**
     * Takes {@code permits} from the bucket if it holds that many.
     *
     * @return {@link Duration#ZERO} when taken, otherwise how long until the bucket holds
     *     enough
     */
    Duration tryTake(String key, long perMinute, long permits);

    /** Takes {@code permits} whatever the bucket holds, so it can go below zero. */
    void take(String key, long perMinute, long permits);

    /** How long until the bucket holds more than zero; {@link Duration#ZERO} if it does now. */
    Duration untilAvailable(String key, long perMinute);
}
