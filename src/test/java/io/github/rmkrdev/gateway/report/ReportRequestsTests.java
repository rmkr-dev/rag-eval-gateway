package io.github.rmkrdev.gateway.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReportRequestsTests {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    private final ReportRequests requests = new ReportRequests(
            new ReportProperties(true, Duration.ofHours(24), Duration.ofDays(31), 1000,
                    new ReportProperties.Dashboard(true)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void defaultsToTheLastDayInUtc() {
        ReportQuery query = requests.parse(null, null, null, null, null, null, null);

        assertThat(query.from()).isEqualTo(NOW.minus(Duration.ofHours(24)));
        assertThat(query.to()).isEqualTo(NOW);
        assertThat(query.zone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(query.client()).isNull();
    }

    @Test
    void rangeCountsBackFromTo() {
        ReportQuery query = requests.parse(null, "2026-10-20T00:00:00Z", "7d", null, null, null, null);

        assertThat(query.from()).isEqualTo(Instant.parse("2026-10-13T00:00:00Z"));
        assertThat(query.range()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void datesAndLocalTimesAreReadInTheZone() {
        ReportQuery query = requests.parse("2026-10-26", "2026-10-27T06:30", null, "Asia/Kolkata", null, null, null);

        assertThat(query.zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThat(query.from()).isEqualTo(Instant.parse("2026-10-25T18:30:00Z"));
        assertThat(query.to()).isEqualTo(Instant.parse("2026-10-27T01:00:00Z"));
    }

    @Test
    void offsetTimesIgnoreTheZone() {
        ReportQuery query = requests.parse("2026-10-27T09:00:00+05:30", null, null, "UTC", " billing-api ", "chat",
                "openai");

        assertThat(query.from()).isEqualTo(Instant.parse("2026-10-27T03:30:00Z"));
        assertThat(query.client()).isEqualTo("billing-api");
        assertThat(query.model()).isEqualTo("chat");
        assertThat(query.provider()).isEqualTo("openai");
    }

    @Test
    void rejectsBadInput() {
        assertInvalid("yesterday", null, null, null, "from must be an ISO-8601 time");
        assertInvalid(null, "2026-13-01", null, null, "to must be an ISO-8601 time");
        assertInvalid("2026-10-27", "2026-10-26", null, null, "from must be before to");
        assertInvalid("2026-09-01", "2026-10-27", null, null, "longer than the maximum of 31d");
        assertInvalid(null, null, "32d", null, "longer than the maximum");
        assertInvalid(null, null, "soon", null, "range must be a duration");
        assertInvalid(null, null, "-1h", null, "range must be positive");
        assertInvalid("2026-10-26", null, "1d", null, "either from or range");
        assertInvalid(null, null, null, "Mars/Olympus", "zone must be a time zone");
        assertThatThrownBy(() -> requests.parse(null, null, null, null, "x".repeat(257), null, null))
                .isInstanceOf(InvalidReportException.class).hasMessageContaining("at most 256");
    }

    @Test
    void parsesDimensionsAndBuckets() {
        assertThat(GroupBy.parse(" Provider ")).isEqualTo(GroupBy.PROVIDER);
        assertThat(Bucket.parse("day")).isEqualTo(Bucket.DAY);
        assertThat(Bucket.forRange(Duration.ofHours(48))).isEqualTo(Bucket.HOUR);
        assertThat(Bucket.forRange(Duration.ofDays(7))).isEqualTo(Bucket.DAY);
        assertThatThrownBy(() -> GroupBy.parse("region")).hasMessage("group_by must be one of client, model, provider");
        assertThatThrownBy(() -> Bucket.parse("week")).hasMessage("bucket must be one of hour, day");
    }

    private void assertInvalid(String from, String to, String range, String zone, String message) {
        assertThatThrownBy(() -> requests.parse(from, to, range, zone, null, null, null))
                .isInstanceOf(InvalidReportException.class)
                .hasMessageContaining(message);
    }
}
