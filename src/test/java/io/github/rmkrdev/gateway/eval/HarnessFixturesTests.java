package io.github.rmkrdev.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The fixture layout llm-eval-harness reads, and its JSON report read back as scores. */
class HarnessFixturesTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HarnessFixtures fixtures = new HarnessFixtures(new EvalProperties.Harness(null,
            new EvalProperties.Check(List.of("token_overlap", "edit_ratio"), null)), JSON);

    @Test
    void aSampleWithContextBecomesAFaithfulnessAndARelevanceCase() {
        EvalSample sample = EvalTestData.sample("gw-1", List.of("Refunds within 30 days.", "Annual plans only."));

        List<HarnessFixtures.Case> cases = fixtures.cases(sample);

        assertThat(cases).extracting(HarnessFixtures.Case::name).containsExactly("gw-1-faithfulness", "gw-1-relevance");
        Map<String, String> faithfulness = cases.get(0).files();
        assertThat(faithfulness).containsOnlyKeys("input.txt", "expected.txt", "actual.txt", "meta.json");
        assertThat(faithfulness.get("input.txt")).isEqualTo("What is the refund window?");
        assertThat(faithfulness.get("expected.txt")).isEqualTo("Refunds within 30 days.\n\nAnnual plans only.");
        assertThat(faithfulness.get("actual.txt")).isEqualTo("Refunds are accepted within 30 days.");
        assertThat(cases.get(1).files().get("expected.txt")).isEqualTo("What is the refund window?");

        JsonNode meta = JSON.readTree(faithfulness.get("meta.json"));
        assertThat(meta.path("id").asString()).isEqualTo("gw-1-faithfulness");
        assertThat(meta.path("metrics").toString()).isEqualTo("[\"token_overlap\"]");
        assertThat(meta.path("thresholds").path("token_overlap").asDouble()).isEqualTo(0.3);
        assertThat(meta.path("tags").toString())
                .isEqualTo("[\"faithfulness\",\"model:rag\",\"client:search-api\",\"prod\"]");
        assertThat(meta.path("gateway").path("call_id").asString()).isEqualTo("gw-1");

        JsonNode relevance = JSON.readTree(cases.get(1).files().get("meta.json"));
        assertThat(relevance.path("metrics").toString()).isEqualTo("[\"token_overlap\",\"edit_ratio\"]");
        assertThat(relevance.path("thresholds").path("edit_ratio").asDouble()).isEqualTo(0.1);
    }

    @Test
    void withoutContextOnlyRelevanceIsExported() {
        assertThat(fixtures.cases(EvalTestData.sample("gw-2", List.of())))
                .extracting(HarnessFixtures.Case::name).containsExactly("gw-2-relevance");
    }

    @Test
    void unsafeIdsNeverLeaveTheirDirectory() {
        assertThat(HarnessFixtures.safe("../etc/passwd")).isEqualTo(".._etc_passwd");
    }

    @Test
    void zipHoldsOneDirectoryPerCase() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        fixtures.zip(List.of(EvalTestData.sample("gw-1", List.of("ctx")), EvalTestData.sample("gw-2", List.of())), out);

        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                entries.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(entries).containsKeys("gw-1-faithfulness/expected.txt", "gw-1-faithfulness/meta.json",
                "gw-1-relevance/actual.txt", "gw-2-relevance/input.txt").hasSize(12);
        assertThat(entries.get("gw-1-faithfulness/expected.txt")).isEqualTo("ctx");
    }

    @Test
    void writesFixtureDirectories(@TempDir Path dir) throws Exception {
        fixtures.write(List.of(EvalTestData.sample("gw-1", List.of("ctx"))), dir);

        assertThat(Files.readString(dir.resolve("gw-1-faithfulness/actual.txt")))
                .isEqualTo("Refunds are accepted within 30 days.");
        assertThat(dir.resolve("gw-1-relevance/meta.json")).exists();
    }

    @Test
    void readsScoresFromAHarnessReport() {
        JsonNode report = JSON.readTree("""
                {"harness":"llm-eval-harness","summary":{"total":5},"cases":[
                  {"id":"gw-1-faithfulness","path":"gw-1-faithfulness","pass":true,"skipped":false,
                   "metrics":[{"name":"token_overlap","pass":true,"score":0.6},{"name":"edit_ratio","pass":true,"score":0.8}]},
                  {"id":"gw-1-relevance","path":"gw-1-relevance","pass":false,"skipped":false,
                   "metrics":[{"name":"token_overlap","pass":false,"score":0.05}]},
                  {"id":"gw-2-relevance","path":"gw-2-relevance","pass":true,"skipped":true,"metrics":[]},
                  {"id":"greeting","path":"greeting","pass":true,"metrics":[{"name":"exact_match","score":1}]},
                  {"path":"gw-3-relevance","pass":true,"metrics":[{"name":"token_overlap","score":1.5}]}
                ]}
                """);

        Map<String, HarnessFixtures.Scored> scores = HarnessFixtures.scores(report);

        assertThat(scores).containsOnlyKeys("gw-1", "gw-3");
        assertThat(scores.get("gw-1").faithfulness()).isEqualTo(0.7, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(scores.get("gw-1").relevance()).isEqualTo(0.05);
        assertThat(scores.get("gw-3")).isEqualTo(new HarnessFixtures.Scored(null, 1.0));
    }
}
