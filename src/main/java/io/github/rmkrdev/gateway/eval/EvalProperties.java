package io.github.rmkrdev.gateway.eval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Eval sampling: a share of successful calls is kept with its prompt, context and answer, so
 * faithfulness and relevance can be scored offline with {@code llm-eval-harness}. Off until
 * {@code enabled} is set.
 *
 * <pre>
 * gateway:
 *   eval:
 *     enabled: true
 *     sample-rate: 0.02
 *     models:
 *       rag-answer: 0.2
 *     clients:
 *       search-api: 1.0
 * </pre>
 *
 * @param enabled sample calls at all
 * @param sampleRate share of calls sampled, 0 to 1, when no model or client entry applies
 * @param models sample rates by model alias
 * @param clients sample rates by client name, ignoring case. A client entry wins over a model
 *     entry, and both over {@code sample-rate}
 * @param tags tags stored with every sample and copied into exported fixtures
 * @param sinks built-in sinks to write to: {@code database} and {@code file}, or none when only
 *     {@link EvalSink} beans should get samples. Beans are always written to as well
 * @param file the {@code file} sink
 * @param queueCapacity samples held in memory waiting to be written; when full, new samples
 *     are dropped and counted
 * @param batchSize most samples handed to a sink at once
 * @param flushInterval longest a sample waits before it is written
 * @param maxTextLength longest message, context passage or answer kept, in characters; longer
 *     text is cut
 * @param retention how long stored samples and sample files are kept; {@code 0} keeps them
 * @param purgeInterval how often old samples are deleted
 * @param export samples served by {@code GET /v1/eval/samples}
 * @param harness metrics and thresholds written into exported harness fixtures
 */
@Validated
@ConfigurationProperties("gateway.eval")
public record EvalProperties(
        @DefaultValue("false") boolean enabled,
        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.01") double sampleRate,
        @DefaultValue Map<String, @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double> models,
        @DefaultValue Map<String, @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double> clients,
        @DefaultValue List<@NotBlank String> tags,
        @DefaultValue("database") List<@NotBlank String> sinks,
        @NotNull @Valid @DefaultValue File file,
        @Min(1) @DefaultValue("1000") int queueCapacity,
        @Min(1) @Max(1000) @DefaultValue("50") int batchSize,
        @NotNull @DefaultValue("2s") Duration flushInterval,
        @Min(100) @DefaultValue("32000") int maxTextLength,
        @NotNull @DefaultValue("30d") Duration retention,
        @NotNull @DefaultValue("1h") Duration purgeInterval,
        @NotNull @Valid @DefaultValue Export export,
        @NotNull @Valid @DefaultValue Harness harness) {

    public static final String DATABASE = "database";
    public static final String FILE = "file";

    /**
     * Writes samples under a directory, one set per UTC day.
     *
     * @param path directory to write to; created when missing
     * @param format {@code jsonl} for one sample per line in {@code eval-samples-<date>.jsonl},
     *     or {@code fixtures} for harness fixture directories under {@code fixtures-<date>/}
     */
    public record File(@NotNull @DefaultValue("eval-samples") Path path,
            @NotNull @DefaultValue("jsonl") FileFormat format) {
    }

    public enum FileFormat {
        JSONL, FIXTURES
    }

    /**
     * @param defaultLimit samples returned when the request gives no {@code limit}
     * @param maxLimit largest {@code limit} a request may ask for
     */
    public record Export(@Min(1) @DefaultValue("1000") int defaultLimit,
            @Min(1) @DefaultValue("10000") int maxLimit) {
    }

    /**
     * What exported fixtures ask the harness to score. Unset parts fall back to
     * {@code token_overlap} with a pass mark of 0.3 for faithfulness and 0.1 for relevance.
     *
     * @param faithfulness metrics for the answer against the context
     * @param relevance metrics for the answer against the question
     */
    public record Harness(@Valid Check faithfulness, @Valid Check relevance) {

        public Harness {
            faithfulness = Check.orDefault(faithfulness, 0.3);
            relevance = Check.orDefault(relevance, 0.1);
        }
    }

    /**
     * @param metrics harness metric names, for example {@code token_overlap} or {@code edit_ratio}
     * @param threshold pass mark from 0 to 1 applied to every listed metric
     */
    public record Check(List<@NotBlank String> metrics, @DecimalMin("0.0") @DecimalMax("1.0") Double threshold) {

        static final List<String> DEFAULT_METRICS = List.of("token_overlap");

        static Check orDefault(Check check, double threshold) {
            if (check == null) {
                return new Check(DEFAULT_METRICS, threshold);
            }
            return new Check(check.metrics() == null || check.metrics().isEmpty() ? DEFAULT_METRICS : check.metrics(),
                    check.threshold() == null ? threshold : check.threshold());
        }
    }
}
