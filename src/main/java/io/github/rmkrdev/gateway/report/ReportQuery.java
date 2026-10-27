package io.github.rmkrdev.gateway.report;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Which calls a report covers: those that started in {@code [from, to)}, optionally narrowed to
 * one client, model alias or provider.
 *
 * @param from first instant included
 * @param to first instant excluded
 * @param zone zone for day buckets and for dates given without a time
 * @param client only this client, or {@code null}
 * @param model only this model alias, or {@code null}
 * @param provider only this provider, or {@code null}
 */
public record ReportQuery(Instant from, Instant to, ZoneId zone, String client, String model, String provider) {

    public Duration range() {
        return Duration.between(from, to);
    }
}
