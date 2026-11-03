package io.github.rmkrdev.gateway.eval;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires eval sampling. The {@link EvalSampler} always exists so the chat endpoint can ask it;
 * with {@code gateway.eval.enabled} off it samples nothing and starts no thread. Startup fails
 * on an unknown sink or model alias.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class EvalConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EvalConfiguration.class);

    /** Metrics in {@code llm-eval-harness} 1.0.0; others are allowed, with a warning. */
    static final Set<String> HARNESS_METRICS = Set.of("exact_match", "case_insensitive_match", "contains",
            "contains_all", "contains_any", "json_equal", "token_overlap", "starts_with", "ends_with", "regex_match",
            "whitespace_normalized_match", "length_ratio", "edit_ratio");

    @Bean
    HarnessFixtures harnessFixtures(EvalProperties properties, JsonMapper json) {
        return new HarnessFixtures(properties.harness(), json);
    }

    @Bean
    @ConditionalOnBooleanProperty("gateway.eval.enabled")
    EvalStore evalStore(JdbcTemplate jdbc, JsonMapper json) {
        return new EvalStore(jdbc, json);
    }

    @Bean
    @ConditionalOnBooleanProperty("gateway.eval.enabled")
    FileEvalSink fileEvalSink(EvalProperties properties, JsonMapper json, HarnessFixtures fixtures) {
        return new FileEvalSink(properties.file(), json, fixtures, Clock.systemUTC());
    }

    @Bean
    EvalSampler evalSampler(EvalProperties properties, GatewayProperties gateway, ObjectProvider<EvalStore> store,
            ObjectProvider<FileEvalSink> file, ObjectProvider<EvalSink> beans, ObjectProvider<MeterRegistry> meters) {
        if (!properties.enabled()) {
            return new EvalSampler(properties, List.of(), null);
        }
        List<String> problems = new ArrayList<>();
        List<EvalSink> sinks = new ArrayList<>();
        for (String name : properties.sinks()) {
            switch (name) {
                case EvalProperties.DATABASE -> sinks.add(store.getObject());
                case EvalProperties.FILE -> sinks.add(file.getObject());
                default -> problems.add("gateway.eval.sinks names unknown sink '%s' (database or file)".formatted(name));
            }
        }
        beans.orderedStream()
                .filter(sink -> !(sink instanceof EvalStore) && !(sink instanceof FileEvalSink))
                .forEach(sinks::add);
        properties.models().keySet().forEach(alias -> {
            if (!gateway.models().containsKey(alias)) {
                problems.add("gateway.eval.models names unknown model alias '%s'".formatted(alias));
            }
        });
        if (sinks.isEmpty()) {
            problems.add("gateway.eval.sinks is empty and there is no EvalSink bean, so samples have nowhere to go");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid eval configuration: " + String.join("; ", problems));
        }
        Stream.concat(properties.harness().faithfulness().metrics().stream(),
                        properties.harness().relevance().metrics().stream())
                .filter(metric -> !HARNESS_METRICS.contains(metric))
                .distinct()
                .forEach(metric -> log.warn("gateway.eval.harness names metric '{}', which llm-eval-harness 1.0.0 "
                        + "does not know; the harness fails cases that use an unknown metric", metric));
        log.info("Eval sampling on: default rate {}, {} model and {} client entries, sinks {}",
                properties.sampleRate(), properties.models().size(), properties.clients().size(),
                sinks.stream().map(EvalSink::name).toList());
        return new EvalSampler(properties, sinks, meters.getIfAvailable());
    }

    @Bean
    @ConditionalOnBooleanProperty("gateway.eval.enabled")
    EvalPurge evalPurge(EvalProperties properties, ObjectProvider<EvalStore> store, ObjectProvider<FileEvalSink> file) {
        return new EvalPurge(properties, properties.sinks().contains(EvalProperties.DATABASE) ? store.getObject() : null,
                properties.sinks().contains(EvalProperties.FILE) ? file.getObject() : null);
    }

    /** Deletes samples older than {@code gateway.eval.retention} from the database and the file sink. */
    static class EvalPurge {

        private final EvalProperties properties;
        private final EvalStore store;
        private final FileEvalSink file;

        EvalPurge(EvalProperties properties, EvalStore store, FileEvalSink file) {
            this.properties = properties;
            this.store = store;
            this.file = file;
        }

        @Scheduled(initialDelayString = "${gateway.eval.purge-interval:1h}",
                fixedDelayString = "${gateway.eval.purge-interval:1h}")
        void purge() {
            if (properties.retention().isZero() || properties.retention().isNegative()) {
                return;
            }
            Instant cutoff = Instant.now().minus(properties.retention());
            int deleted = 0;
            try {
                if (store != null) {
                    deleted += store.deleteStartedBefore(cutoff);
                }
                if (file != null) {
                    deleted += file.deleteBefore(cutoff);
                }
            } catch (RuntimeException e) {
                log.warn("could not delete old eval samples: {}", e.toString());
                return;
            }
            if (deleted > 0) {
                log.info("deleted {} eval samples or sample files older than {}", deleted, properties.retention());
            }
        }
    }
}
