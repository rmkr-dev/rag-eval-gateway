package io.github.rmkrdev.gateway.tracking;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How finished calls are written to the database. Writes happen off the request path in
 * batches, so a slow database never slows an answer down.
 *
 * @param enabled record calls at all
 * @param queueCapacity calls held in memory waiting to be written; when full, new calls are
 *     dropped and counted rather than blocking callers
 * @param batchSize most calls written in one insert
 * @param flushInterval longest a call waits in the queue before it is written
 * @param retention how long calls are kept; {@code 0} keeps them forever
 * @param purgeInterval how often old calls are deleted
 */
@Validated
@ConfigurationProperties("gateway.tracking")
public record TrackingProperties(
        @DefaultValue("true") boolean enabled,
        @Min(1) @DefaultValue("10000") int queueCapacity,
        @Min(1) @Max(5000) @DefaultValue("200") int batchSize,
        @NotNull @DefaultValue("1s") Duration flushInterval,
        @NotNull @DefaultValue("90d") Duration retention,
        @NotNull @DefaultValue("1h") Duration purgeInterval) {
}
