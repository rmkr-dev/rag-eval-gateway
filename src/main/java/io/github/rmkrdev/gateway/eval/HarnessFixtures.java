package io.github.rmkrdev.gateway.eval;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns samples into {@code llm-eval-harness} fixture cases, and harness reports back into
 * scores.
 * <p>
 * The harness scores {@code actual.txt} against {@code expected.txt} in one directory per case,
 * with the metrics named in {@code meta.json}. Each sample becomes up to two cases:
 * <ul>
 *   <li>{@code <call id>-faithfulness}: the answer against the retrieved context, only when the
 *       caller sent {@code eval_context};
 *   <li>{@code <call id>-relevance}: the answer against the question.
 * </ul>
 * Both carry the tags {@code faithfulness} or {@code relevance}, {@code model:<alias>},
 * {@code client:<name>} and the configured tags, so {@code llm-eval --tag faithfulness} runs one
 * kind only.
 */
public class HarnessFixtures {

    public static final String FAITHFULNESS = "faithfulness";
    public static final String RELEVANCE = "relevance";

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");

    private final EvalProperties.Harness harness;
    private final JsonMapper json;

    public HarnessFixtures(EvalProperties.Harness harness, JsonMapper json) {
        this.harness = harness;
        this.json = json;
    }

    /**
     * One fixture case.
     *
     * @param name directory name, also the case id
     * @param files file name to content: {@code input.txt}, {@code expected.txt},
     *     {@code actual.txt} and {@code meta.json}
     */
    public record Case(String name, Map<String, String> files) {
    }

    public List<Case> cases(EvalSample sample) {
        List<Case> cases = new ArrayList<>(2);
        if (sample.context() != null && !sample.context().isEmpty()) {
            cases.add(build(sample, FAITHFULNESS, String.join("\n\n", sample.context()), harness.faithfulness(),
                    "Is the answer grounded in the retrieved context?"));
        }
        cases.add(build(sample, RELEVANCE, sample.question(), harness.relevance(),
                "Does the answer address the question?"));
        return cases;
    }

    private Case build(EvalSample sample, String check, String expected, EvalProperties.Check metrics,
            String description) {
        String name = safe(sample.id()) + "-" + check;
        Set<String> tags = new LinkedHashSet<>();
        tags.add(check);
        tags.add("model:" + sample.model());
        tags.add("client:" + sample.client());
        tags.addAll(sample.tags() == null ? List.of() : sample.tags());
        Map<String, Double> thresholds = new LinkedHashMap<>();
        metrics.metrics().forEach(metric -> thresholds.put(metric, metrics.threshold()));
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("call_id", sample.id());
        gateway.put("check", check);
        gateway.put("model", sample.model());
        gateway.put("provider", sample.provider());
        gateway.put("provider_model", sample.providerModel());
        gateway.put("client", sample.client());
        gateway.put("started_at", sample.startedAt() == null ? null : sample.startedAt().toString());
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", name);
        meta.put("description", description);
        meta.put("metrics", metrics.metrics());
        meta.put("thresholds", thresholds);
        meta.put("tags", List.copyOf(tags));
        meta.put("gateway", gateway);
        Map<String, String> files = new LinkedHashMap<>();
        files.put("input.txt", sample.question() == null ? "" : sample.question());
        files.put("expected.txt", expected == null ? "" : expected);
        files.put("actual.txt", sample.answer() == null ? "" : sample.answer());
        files.put("meta.json", json.writerWithDefaultPrettyPrinter().writeValueAsString(meta) + "\n");
        return new Case(name, files);
    }

    /** Writes every sample's cases as a zip of fixture directories. */
    public void zip(List<EvalSample> samples, OutputStream out) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        for (EvalSample sample : samples) {
            for (Case c : cases(sample)) {
                for (Map.Entry<String, String> file : c.files().entrySet()) {
                    zip.putNextEntry(new ZipEntry(c.name() + "/" + file.getKey()));
                    zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
        }
        zip.finish();
    }

    /** Writes every sample's cases as fixture directories under {@code root}. */
    public void write(List<EvalSample> samples, Path root) throws IOException {
        for (EvalSample sample : samples) {
            for (Case c : cases(sample)) {
                Path dir = root.resolve(c.name());
                Files.createDirectories(dir);
                for (Map.Entry<String, String> file : c.files().entrySet()) {
                    Files.writeString(dir.resolve(file.getKey()), file.getValue(), StandardCharsets.UTF_8);
                }
            }
        }
    }

    /**
     * Scores read from a harness JSON report: for every case named
     * {@code <call id>-faithfulness} or {@code <call id>-relevance}, the mean score of its
     * metrics. Skipped cases, cases without metrics and other case names are left out.
     *
     * @return scores by call id
     */
    public static Map<String, Scored> scores(JsonNode report) {
        Map<String, Scored> scores = new LinkedHashMap<>();
        for (JsonNode c : report.path("cases")) {
            if (c.path("skipped").asBoolean(false)) {
                continue;
            }
            String id = c.path("id").asString("");
            if (id.isEmpty()) {
                id = c.path("path").asString("");
            }
            String check = id.endsWith("-" + FAITHFULNESS) ? FAITHFULNESS : id.endsWith("-" + RELEVANCE) ? RELEVANCE : null;
            if (check == null) {
                continue;
            }
            double sum = 0;
            int n = 0;
            for (JsonNode metric : c.path("metrics")) {
                JsonNode score = metric.path("score");
                if (score.isNumber()) {
                    sum += Math.clamp(score.asDouble(), 0.0, 1.0);
                    n++;
                }
            }
            if (n == 0) {
                continue;
            }
            String callId = id.substring(0, id.length() - check.length() - 1);
            Scored previous = scores.getOrDefault(callId, new Scored(null, null));
            scores.put(callId, FAITHFULNESS.equals(check)
                    ? new Scored(sum / n, previous.relevance())
                    : new Scored(previous.faithfulness(), sum / n));
        }
        return scores;
    }

    /** Faithfulness and relevance of one call, each {@code null} when not in the report. */
    public record Scored(Double faithfulness, Double relevance) {
    }

    static String safe(String id) {
        return UNSAFE.matcher(id == null ? "" : id).replaceAll("_");
    }
}
