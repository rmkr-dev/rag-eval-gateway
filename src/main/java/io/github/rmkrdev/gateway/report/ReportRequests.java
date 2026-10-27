package io.github.rmkrdev.gateway.report;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.convert.DurationStyle;

/**
 * Turns report query parameters into a {@link ReportQuery}, applying defaults and limits.
 *
 * <ul>
 *   <li>{@code to} defaults to now; {@code from} defaults to {@code to - range}, and
 *       {@code range} to {@code gateway.reports.default-range}.
 *   <li>Times are ISO-8601 with an offset ({@code 2026-10-27T09:00:00Z}), a local time or a date
 *       ({@code 2026-10-27}, midnight) read in {@code zone}, which defaults to UTC.
 *   <li>{@code range} is a duration such as {@code 6h} or {@code 7d}.
 * </ul>
 */
public class ReportRequests {

    private static final int MAX_FILTER_LENGTH = 256;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private final ReportProperties properties;
    private final Clock clock;

    public ReportRequests(ReportProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public ReportQuery parse(String from, String to, String range, String zone, String client, String model,
            String provider) {
        ZoneId zoneId = zone(zone);
        Instant end = blank(to) ? clock.instant().truncatedTo(ChronoUnit.SECONDS) : time("to", to, zoneId);
        Instant start;
        if (!blank(from)) {
            if (!blank(range)) {
                throw new InvalidReportException("give either from or range, not both");
            }
            start = time("from", from, zoneId);
        } else {
            start = end.minus(blank(range) ? properties.defaultRange() : range(range));
        }
        if (!start.isBefore(end)) {
            throw new InvalidReportException("from must be before to");
        }
        if (Duration.between(start, end).compareTo(properties.maxRange()) > 0) {
            throw new InvalidReportException("the range is longer than the maximum of "
                    + print(properties.maxRange()) + " (gateway.reports.max-range)");
        }
        return new ReportQuery(start, end, zoneId, filter("client", client), filter("model", model),
                filter("provider", provider));
    }

    private static ZoneId zone(String zone) {
        if (blank(zone)) {
            return UTC;
        }
        try {
            return ZoneId.of(zone.trim());
        } catch (DateTimeException e) {
            throw new InvalidReportException("zone must be a time zone such as UTC or Asia/Kolkata");
        }
    }

    private static Instant time(String name, String value, ZoneId zone) {
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the zone-relative forms next
        }
        try {
            return LocalDateTime.parse(text).atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // try a plain date next
        }
        try {
            return LocalDate.parse(text).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidReportException(name + " must be an ISO-8601 time such as 2026-10-27T09:00:00Z "
                    + "or a date such as 2026-10-27");
        }
    }

    private static Duration range(String value) {
        Duration range;
        try {
            range = DurationStyle.SIMPLE.parse(value.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidReportException("range must be a duration such as 6h or 7d");
        }
        if (range.isZero() || range.isNegative()) {
            throw new InvalidReportException("range must be positive");
        }
        return range;
    }

    private static String print(Duration duration) {
        if (duration.toSeconds() % 86_400 == 0) {
            return duration.toDays() + "d";
        }
        if (duration.toSeconds() % 3600 == 0) {
            return duration.toHours() + "h";
        }
        return duration.toMinutes() + "m";
    }

    private static String filter(String name, String value) {
        if (blank(value)) {
            return null;
        }
        if (value.length() > MAX_FILTER_LENGTH) {
            throw new InvalidReportException(name + " must be at most " + MAX_FILTER_LENGTH + " characters");
        }
        return value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
