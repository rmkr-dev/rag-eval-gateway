package io.github.rmkrdev.gateway.report;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Width of one step in a time series. Day buckets start at midnight in the report's zone. */
public enum Bucket {

    HOUR(ChronoUnit.HOURS),
    DAY(ChronoUnit.DAYS);

    private final ChronoUnit unit;

    Bucket(ChronoUnit unit) {
        this.unit = unit;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Start of the bucket holding {@code instant}. */
    ZonedDateTime start(Instant instant, ZoneId zone) {
        return instant.atZone(zone).truncatedTo(unit);
    }

    ZonedDateTime next(ZonedDateTime start) {
        return start.plus(1, unit);
    }

    /** Smallest bucket that keeps a chart of {@code range} readable. */
    public static Bucket forRange(Duration range) {
        return range.compareTo(Duration.ofDays(2)) <= 0 ? HOUR : DAY;
    }

    public static Bucket parse(String value) {
        return Arrays.stream(values())
                .filter(b -> b.id().equals(value == null ? "" : value.trim().toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElseThrow(() -> new InvalidReportException("bucket must be one of "
                        + Arrays.stream(values()).map(Bucket::id).collect(Collectors.joining(", "))));
    }
}
