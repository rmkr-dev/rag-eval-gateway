package io.github.rmkrdev.gateway.report;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/** Report shapes returned by {@link ReportService}. */
public final class Reports {

    private Reports() {
    }

    /** Calls grouped by one dimension, busiest first. */
    public record Summary(
            Instant from,
            Instant to,
            String zone,
            @JsonProperty("group_by") String groupBy,
            String currency,
            Stats totals,
            List<Group> groups) {
    }

    /** One group of a summary; {@code key} is {@code null} for failed calls no provider answered. */
    public record Group(String key, @JsonUnwrapped Stats stats) {
    }

    /** Calls per time bucket, oldest first, empty buckets included. */
    public record Series(
            Instant from,
            Instant to,
            String zone,
            String bucket,
            String currency,
            List<Point> points) {
    }

    /** One bucket of a series. */
    public record Point(OffsetDateTime start, @JsonUnwrapped Stats stats) {
    }

    /** Everything the dashboard shows, read in one pass over the calls. */
    public record Overview(
            Instant from,
            Instant to,
            String zone,
            String currency,
            Stats totals,
            List<Group> byClient,
            List<Group> byModel,
            List<Group> byProvider,
            String bucket,
            List<Point> series) {
    }
}
