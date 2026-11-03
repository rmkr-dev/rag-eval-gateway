package io.github.rmkrdev.gateway.eval;

import io.github.rmkrdev.gateway.api.ChatMessage;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Builders shared by the eval unit tests. */
final class EvalTestData {

    static final Instant STARTED = Instant.parse("2026-11-03T09:00:00Z");

    private EvalTestData() {
    }

    static EvalProperties properties(boolean enabled, double rate, Map<String, Double> models,
            Map<String, Double> clients, int queueCapacity, int maxTextLength) {
        return new EvalProperties(enabled, rate, models, clients, List.of("prod"), List.of("database"),
                new EvalProperties.File(Path.of("eval-samples"), EvalProperties.FileFormat.JSONL), queueCapacity, 50,
                Duration.ofSeconds(2), maxTextLength, Duration.ofDays(30), Duration.ofHours(1),
                new EvalProperties.Export(1000, 10000), new EvalProperties.Harness(null, null));
    }

    static EvalSample sample(String id, List<String> context) {
        return new EvalSample(id, STARTED, STARTED.plusSeconds(2), "search-api", "rag", "openrouter",
                "meta-llama/llama-3.1-70b-instruct", false, "What is the refund window?", context,
                "Refunds are accepted within 30 days.",
                List.of(new ChatMessage("system", "Answer from the context."),
                        new ChatMessage("user", "What is the refund window?")),
                List.of("prod"), 0, null);
    }
}
