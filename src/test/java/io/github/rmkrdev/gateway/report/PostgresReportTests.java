package io.github.rmkrdev.gateway.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rmkrdev.gateway.tracking.CallStore;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the report queries against a real Postgres. Skipped without Docker. */
@SpringBootTest(properties = "gateway.tracking.retention=0")
@Testcontainers(disabledWithoutDocker = true)
class PostgresReportTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CallStore store;

    @Autowired
    ReportService reports;

    @Autowired
    JdbcTemplate jdbc;

    private static ReportQuery day(ZoneId zone) {
        return new ReportQuery(ReportFixtures.DAY, ReportFixtures.DAY.plusSeconds(86_400), zone, null, null, null);
    }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM gateway_call");
        store.insertAll(ReportFixtures.calls());
    }

    @Test
    void summaryMatchesOnPostgres() {
        Reports.Summary summary = reports.summary(day(ZoneOffset.UTC), GroupBy.MODEL);

        assertThat(summary.totals().calls()).isEqualTo(15);
        assertThat(summary.totals().cost()).isEqualByComparingTo("0.1");
        assertThat(summary.groups()).extracting(Reports.Group::key).containsExactly("chat", "embed");
        Stats chat = summary.groups().getFirst().stats();
        assertThat(chat.totalMs()).isEqualTo(new Percentiles(500, 1000, 1000));
        assertThat(chat.ttftMs()).isEqualTo(new Percentiles(50, 100, 100));
        assertThat(chat.cacheReadTokens()).isEqualTo(1000);
        Stats embed = summary.groups().get(1).stats();
        assertThat(embed.errors()).isEqualTo(1);
        assertThat(embed.ttftMs()).isNull();
    }

    @Test
    void filtersAndSeriesMatchOnPostgres() {
        ReportQuery search = new ReportQuery(ReportFixtures.DAY, ReportFixtures.DAY.plusSeconds(86_400),
                ZoneOffset.UTC, "search", null, null);
        assertThat(reports.summary(search, GroupBy.PROVIDER).groups())
                .extracting(Reports.Group::key).containsExactly("local", null);

        Reports.Series series = reports.series(day(ZoneOffset.UTC), Bucket.HOUR);
        assertThat(series.points()).hasSize(24);
        assertThat(series.points().get(9).stats().calls()).isEqualTo(10);
        assertThat(series.points().get(10).stats().errors()).isEqualTo(1);
        assertThat(series.points().get(9).start().toInstant()).isEqualTo(Instant.parse("2026-10-26T09:00:00Z"));
    }
}
