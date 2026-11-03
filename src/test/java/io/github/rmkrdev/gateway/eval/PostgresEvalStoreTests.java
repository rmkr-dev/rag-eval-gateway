package io.github.rmkrdev.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rmkrdev.gateway.report.ReportQuery;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the eval migration and store against a real Postgres. Skipped without Docker. */
@SpringBootTest(properties = {"entra.security.enabled=false", "gateway.eval.enabled=true"})
@Testcontainers(disabledWithoutDocker = true)
class PostgresEvalStoreTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    EvalStore store;

    private static EvalSample at(String id, Instant startedAt, String client) {
        EvalSample s = EvalTestData.sample(id, List.of("Refunds within 30 days.", "Monthly plans excluded."));
        return new EvalSample(s.id(), startedAt, startedAt.plusSeconds(1), client, s.model(), s.provider(),
                s.providerModel(), true, s.question(), s.context(), s.answer(), s.messages(), s.tags(), 2, null);
    }

    @Test
    void roundTripsScoresAndSummarises() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        EvalSample written = at("gw-pg-eval-1", now, "summary-api");
        store.write(List.of(written, at("gw-pg-eval-2", now, "summary-api")));

        assertThat(store.find("gw-pg-eval-1")).hasValue(written);
        assertThat(store.score("gw-pg-eval-1", 0.5, null, now)).isTrue();
        assertThat(store.score("gw-pg-eval-1", null, 0.25, now)).isTrue();
        assertThat(store.score("gw-pg-eval-2", 1.0, null, now)).isTrue();
        assertThat(store.score("gw-pg-missing", 1.0, null, now)).isFalse();

        EvalSample scored = store.find("gw-pg-eval-1").orElseThrow();
        assertThat(scored.scores().faithfulness()).isEqualTo(0.5);
        assertThat(scored.scores().relevance()).isEqualTo(0.25);

        ReportQuery query = new ReportQuery(now.minusSeconds(60), now.plusSeconds(60), ZoneOffset.UTC,
                written.client(), null, null);
        assertThat(store.find(query, 10)).extracting(EvalSample::id).containsExactly("gw-pg-eval-1", "gw-pg-eval-2");
        assertThat(store.summary(query, false)).singleElement().satisfies(row -> {
            assertThat(row.key()).isEqualTo("rag");
            assertThat(row.samples()).isEqualTo(2);
            assertThat(row.faithfulnessScored()).isEqualTo(2);
            assertThat(row.faithfulness()).isEqualTo(0.75);
            assertThat(row.relevanceScored()).isEqualTo(1);
            assertThat(row.relevance()).isEqualTo(0.25);
        });
    }

    @Test
    void purgesOnlySamplesOlderThanTheCutoff() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        store.write(List.of(at("gw-pg-eval-old", now.minus(40, ChronoUnit.DAYS), "purge-api"),
                at("gw-pg-eval-new", now, "purge-api")));

        assertThat(store.deleteStartedBefore(now.minus(30, ChronoUnit.DAYS))).isGreaterThanOrEqualTo(1);
        assertThat(store.find("gw-pg-eval-old")).isEmpty();
        assertThat(store.find("gw-pg-eval-new")).isPresent();
    }
}
