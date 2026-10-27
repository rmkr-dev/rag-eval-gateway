package io.github.rmkrdev.gateway.report;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Reports over the stored calls and the built-in dashboard. Both need
 * {@code gateway.tracking.enabled}. There is no authentication until Entra arrives, so keep
 * these endpoints off the public internet until then.
 *
 * @param enabled serve {@code /v1/reports/*}
 * @param defaultRange range used when the caller gives no {@code from} or {@code range}
 * @param maxRange longest range one report may cover
 * @param maxRows most calls one report may read; larger ranges are refused with 400
 * @param dashboard the {@code /dashboard} page
 */
@Validated
@ConfigurationProperties("gateway.reports")
public record ReportProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue("24h") Duration defaultRange,
        @NotNull @DefaultValue("31d") Duration maxRange,
        @Min(1) @DefaultValue("500000") int maxRows,
        @NotNull @Valid @DefaultValue Dashboard dashboard) {

    /**
     * The built-in dashboard.
     *
     * @param enabled serve {@code /dashboard}; needs the reports API to be enabled too
     */
    public record Dashboard(@DefaultValue("true") boolean enabled) {
    }
}
