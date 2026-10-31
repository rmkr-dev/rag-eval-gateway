package io.github.rmkrdev.gateway.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the Flyway migrations and the store against a real Postgres. Skipped without Docker. */
@SpringBootTest(properties = {"entra.security.enabled=false", "gateway.tracking.purge-interval=1h"})
@Testcontainers(disabledWithoutDocker = true)
class PostgresCallStoreTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    CallStore store;

    private static CallRecord record(String id, Instant startedAt, BigDecimal cost) {
        return new CallRecord(id, startedAt, "billing-api", "chat", "openai", "gpt-4o-mini", true, CallRecord.OK,
                null, "stop", 1200, 300, 200, null, cost, "USD", "2026-10-01", 85L, 910L, 1);
    }

    @Test
    void roundTripsEveryColumn() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CallRecord written = record("gw-pg-1", now, new BigDecimal("0.00123456"));

        store.insertAll(List.of(written));

        CallRecord read = store.find("gw-pg-1").orElseThrow();
        assertThat(read).usingRecursiveComparison().ignoringFields("cost").isEqualTo(written);
        assertThat(read.cost()).isEqualByComparingTo("0.00123456");
    }

    @Test
    void purgesOnlyCallsOlderThanTheCutoff() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.insertAll(List.of(
                record("gw-pg-old", now.minus(100, ChronoUnit.DAYS), null),
                record("gw-pg-new", now, null)));

        int deleted = store.deleteStartedBefore(now.minus(90, ChronoUnit.DAYS));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(store.find("gw-pg-old")).isEmpty();
        assertThat(store.find("gw-pg-new")).isPresent();
        assertThat(store.recent(10)).extracting(CallRecord::id).contains("gw-pg-new");
    }
}
