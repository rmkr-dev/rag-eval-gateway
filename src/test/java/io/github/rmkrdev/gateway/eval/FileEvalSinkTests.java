package io.github.rmkrdev.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FileEvalSinkTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private FileEvalSink sink(EvalProperties.FileFormat format) {
        return new FileEvalSink(new EvalProperties.File(dir.resolve("samples"), format), JSON,
                new HarnessFixtures(new EvalProperties.Harness(null, null), JSON), CLOCK);
    }

    @Test
    void appendsOneJsonLinePerSampleToTheDaysFile() throws Exception {
        FileEvalSink sink = sink(EvalProperties.FileFormat.JSONL);

        sink.write(List.of(EvalTestData.sample("gw-1", List.of("ctx"))));
        sink.write(List.of(EvalTestData.sample("gw-2", List.of())));

        List<String> lines = Files.readAllLines(dir.resolve("samples/eval-samples-2026-11-03.jsonl"));
        assertThat(lines).hasSize(2);
        JsonNode first = JSON.readTree(lines.get(0));
        assertThat(first.path("id").asString()).isEqualTo("gw-1");
        assertThat(first.path("question").asString()).isEqualTo("What is the refund window?");
        assertThat(first.path("context").get(0).asString()).isEqualTo("ctx");
        assertThat(first.path("provider_model").asString()).isEqualTo("meta-llama/llama-3.1-70b-instruct");
        assertThat(first.has("scores")).isFalse();
    }

    @Test
    void writesHarnessFixturesForTheDay() {
        sink(EvalProperties.FileFormat.FIXTURES).write(List.of(EvalTestData.sample("gw-1", List.of("ctx"))));

        assertThat(dir.resolve("samples/fixtures-2026-11-03/gw-1-faithfulness/expected.txt")).hasContent("ctx");
        assertThat(dir.resolve("samples/fixtures-2026-11-03/gw-1-relevance/meta.json")).exists();
    }

    @Test
    void deletesDaysBeforeTheCutoffOnly() throws Exception {
        Path root = Files.createDirectories(dir.resolve("samples"));
        Files.writeString(root.resolve("eval-samples-2026-10-01.jsonl"), "{}\n");
        Files.writeString(root.resolve("eval-samples-2026-11-02.jsonl"), "{}\n");
        Files.createDirectories(root.resolve("fixtures-2026-10-01/gw-1-relevance"));
        Files.writeString(root.resolve("fixtures-2026-10-01/gw-1-relevance/actual.txt"), "x");
        Files.writeString(root.resolve("notes.txt"), "keep");

        int deleted = sink(EvalProperties.FileFormat.JSONL).deleteBefore(Instant.parse("2026-11-01T12:00:00Z"));

        assertThat(deleted).isEqualTo(2);
        assertThat(root.resolve("eval-samples-2026-10-01.jsonl")).doesNotExist();
        assertThat(root.resolve("fixtures-2026-10-01")).doesNotExist();
        assertThat(root.resolve("eval-samples-2026-11-02.jsonl")).exists();
        assertThat(root.resolve("notes.txt")).exists();
    }

    @Test
    void nothingToDeleteBeforeTheFirstWrite() {
        assertThat(sink(EvalProperties.FileFormat.JSONL).deleteBefore(Instant.now())).isZero();
    }
}
