package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.auth.GatewayRoles;
import io.github.rmkrdev.gateway.eval.EvalProperties;
import io.github.rmkrdev.gateway.eval.EvalSample;
import io.github.rmkrdev.gateway.eval.EvalStore;
import io.github.rmkrdev.gateway.eval.HarnessFixtures;
import io.github.rmkrdev.gateway.eval.InvalidEvalRequestException;
import io.github.rmkrdev.gateway.report.InvalidReportException;
import io.github.rmkrdev.gateway.report.ReportProperties;
import io.github.rmkrdev.gateway.report.ReportQuery;
import io.github.rmkrdev.gateway.report.ReportRequests;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sampled calls for offline evaluation, for callers with {@link GatewayRoles#REPORTS}: export
 * as JSON, JSON Lines or a zip of {@code llm-eval-harness} fixtures, attach scores, and read
 * average scores. Served while {@code gateway.eval.enabled} is on; reads the database sink.
 */
@RestController
@RequestMapping("/v1/eval")
@ConditionalOnBooleanProperty("gateway.eval.enabled")
@Secured(GatewayRoles.REPORTS)
class EvalController {

    static final MediaType NDJSON = MediaType.parseMediaType("application/x-ndjson");
    static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final EvalStore store;
    private final HarnessFixtures fixtures;
    private final EvalProperties properties;
    private final ReportRequests requests;
    private final JsonMapper json;

    EvalController(EvalStore store, HarnessFixtures fixtures, EvalProperties properties,
            ReportProperties reports, JsonMapper json) {
        this.store = store;
        this.fixtures = fixtures;
        this.properties = properties;
        this.requests = new ReportRequests(reports, Clock.systemUTC());
        this.json = json;
    }

    /** Samples that started in the range, oldest first, in {@code json}, {@code jsonl} or {@code fixtures}. */
    @GetMapping("/samples")
    ResponseEntity<Map<String, Object>> samples(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String client,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "json") String format,
            HttpServletResponse response) throws IOException {
        String kind = format.trim().toLowerCase(Locale.ROOT);
        if (!List.of("json", "jsonl", "fixtures").contains(kind)) {
            throw new InvalidEvalRequestException("format must be json, jsonl or fixtures");
        }
        int max = properties.export().maxLimit();
        int rows = limit == null ? Math.min(properties.export().defaultLimit(), max) : limit;
        if (rows < 1 || rows > max) {
            throw new InvalidEvalRequestException("limit must be between 1 and " + max
                    + " (gateway.eval.export.max-limit)");
        }
        ReportQuery query = query(from, to, range, zone, client, model, provider);
        List<EvalSample> samples = store.find(query, rows);
        if (kind.equals("json")) {
            return ResponseEntity.ok(Map.of("from", query.from().toString(), "to", query.to().toString(),
                    "count", samples.size(), "samples", samples));
        }
        response.setStatus(HttpServletResponse.SC_OK);
        OutputStream out = response.getOutputStream();
        if (kind.equals("jsonl")) {
            response.setContentType(NDJSON.toString());
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            for (EvalSample sample : samples) {
                out.write(json.writeValueAsBytes(sample));
                out.write('\n');
            }
        } else {
            response.setContentType(ZIP.toString());
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"eval-fixtures.zip\"");
            fixtures.zip(samples, out);
        }
        out.flush();
        return null;
    }

    @GetMapping("/samples/{id}")
    ResponseEntity<EvalSample> sample(@PathVariable String id) {
        return ResponseEntity.of(store.find(id));
    }

    /** Scores for one sample from any evaluator; a missing score keeps the stored one. */
    @PostMapping("/samples/{id}/scores")
    ResponseEntity<EvalSample> score(@PathVariable String id, @Valid @RequestBody Scores scores) {
        if (scores.faithfulness() == null && scores.relevance() == null) {
            throw new InvalidEvalRequestException("give faithfulness, relevance or both");
        }
        if (!store.score(id, scores.faithfulness(), scores.relevance(), Instant.now())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.of(store.find(id));
    }

    /**
     * Scores from an {@code llm-eval-harness} JSON report run on a {@code fixtures} export: each
     * {@code <call id>-faithfulness} and {@code <call id>-relevance} case sets that score to the
     * mean of its metric scores.
     */
    @PostMapping("/scores")
    Map<String, Object> harnessReport(@RequestBody JsonNode report) {
        if (!report.path("cases").isArray()) {
            throw new InvalidEvalRequestException("expected an llm-eval-harness JSON report with a cases array");
        }
        Instant now = Instant.now();
        int updated = 0;
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, HarnessFixtures.Scored> entry : HarnessFixtures.scores(report).entrySet()) {
            HarnessFixtures.Scored scored = entry.getValue();
            if (store.score(entry.getKey(), scored.faithfulness(), scored.relevance(), now)) {
                updated++;
            } else {
                unknown.add(entry.getKey());
            }
        }
        return Map.of("updated", updated, "unknown", unknown);
    }

    /** Sample counts and average scores by model alias or client. */
    @GetMapping("/summary")
    Map<String, Object> summary(
            @RequestParam(name = "group_by", defaultValue = "model") String groupBy,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String range,
            @RequestParam(required = false) String zone,
            @RequestParam(required = false) String client,
            @RequestParam(required = false) String model,
            @RequestParam(required = false) String provider) {
        String by = groupBy.trim().toLowerCase(Locale.ROOT);
        if (!by.equals("model") && !by.equals("client")) {
            throw new InvalidEvalRequestException("group_by must be model or client");
        }
        ReportQuery query = query(from, to, range, zone, client, model, provider);
        List<Map<String, Object>> rows = store.summary(query, by.equals("client")).stream().map(row -> {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put(by, row.key());
            out.put("samples", row.samples());
            out.put("faithfulness_scored", row.faithfulnessScored());
            out.put("faithfulness_avg", row.faithfulness());
            out.put("relevance_scored", row.relevanceScored());
            out.put("relevance_avg", row.relevance());
            return out;
        }).toList();
        return Map.of("from", query.from().toString(), "to", query.to().toString(), "group_by", by, "rows", rows);
    }

    /**
     * Scores from 0 to 1.
     *
     * @param faithfulness how well the answer sticks to the context
     * @param relevance how well the answer addresses the question
     */
    record Scores(@DecimalMin("0.0") @DecimalMax("1.0") Double faithfulness,
            @DecimalMin("0.0") @DecimalMax("1.0") Double relevance) {
    }

    private ReportQuery query(String from, String to, String range, String zone, String client, String model,
            String provider) {
        try {
            return requests.parse(from, to, range, zone, client, model, provider);
        } catch (InvalidReportException e) {
            throw new InvalidEvalRequestException(e.getMessage());
        }
    }
}
