package io.github.rmkrdev.gateway.eval;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.ChatRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class EvalSamplerTests {

    private static final ChatRequest REQUEST = new ChatRequest("rag",
            List.of(new ChatMessage("system", "Answer from the context."),
                    new ChatMessage("user", "What is the refund window?"),
                    new ChatMessage("assistant", "Which product?"),
                    new ChatMessage("user", "The annual plan.")),
            null, null, null, Map.of("feature", "help"), List.of("Annual plans can be refunded within 30 days.", " "));

    private final List<EvalSample> written = new CopyOnWriteArrayList<>();

    private EvalSampler sampler(EvalProperties properties, double random, EvalSink... sinks) {
        List<EvalSink> all = new ArrayList<>(List.of(sinks));
        if (all.isEmpty()) {
            all.add(written::addAll);
        }
        return new EvalSampler(properties, all, null, () -> random);
    }

    @Test
    void clientRateWinsOverModelRateWhichWinsOverTheDefault() {
        EvalSampler sampler = sampler(EvalTestData.properties(true, 0.01, Map.of("rag", 0.5),
                Map.of("Search-API", 1.0), 10, 1000), 0.0);

        assertThat(sampler.rateFor("search-api", "rag")).isEqualTo(1.0);
        assertThat(sampler.rateFor("billing-api", "rag")).isEqualTo(0.5);
        assertThat(sampler.rateFor("billing-api", "chat")).isEqualTo(0.01);
    }

    @Test
    void samplesWhenTheRandomNumberIsBelowTheRate() {
        EvalProperties properties = EvalTestData.properties(true, 0.2, Map.of("never", 0.0), Map.of(), 10, 1000);

        assertThat(sampler(properties, 0.19).shouldSample("a", "chat")).isTrue();
        assertThat(sampler(properties, 0.2).shouldSample("a", "chat")).isFalse();
        assertThat(sampler(properties, 0.0).shouldSample("a", "never")).isFalse();
    }

    @Test
    void samplesNothingWhileDisabled() {
        EvalSampler sampler = sampler(EvalTestData.properties(false, 1.0, Map.of(), Map.of(), 10, 1000), 0.0);

        assertThat(sampler.shouldSample("a", "chat")).isFalse();
        sampler.start();
        assertThat(sampler.isRunning()).isFalse();
    }

    @Test
    void keepsTheRequestAsSentTheLastUserMessageAsQuestionAndCutsLongText() {
        EvalSampler sampler = sampler(EvalTestData.properties(true, 1.0, Map.of(), Map.of(), 10, 100), 0.0);
        String longAnswer = "a".repeat(250);

        EvalSample sample = sampler.toSample("gw-1", EvalTestData.STARTED, EvalTestData.STARTED.plusSeconds(1),
                "search-api", "openrouter", "llama", REQUEST, longAnswer, 2);

        assertThat(sample.question()).isEqualTo("The annual plan.");
        assertThat(sample.context()).containsExactly("Annual plans can be refunded within 30 days.");
        assertThat(sample.messages()).hasSize(4);
        assertThat(sample.answer()).hasSize(100);
        assertThat(sample.model()).isEqualTo("rag");
        assertThat(sample.tags()).containsExactly("prod");
        assertThat(sample.piiRedactions()).isEqualTo(2);
        assertThat(sample.scores()).isNull();
    }

    @Test
    void cuttingNeverSplitsASurrogatePair() {
        assertThat(EvalSampler.cut("ab\uD83D\uDE00cd", 3)).isEqualTo("ab");
        assertThat(EvalSampler.cut("short", 10)).isEqualTo("short");
    }

    @Test
    void writesQueuedSamplesToEverySinkOnFlush() {
        EvalSampler sampler = sampler(EvalTestData.properties(true, 1.0, Map.of(), Map.of(), 10, 1000), 0.0);

        sampler.capture("gw-1", Instant.now(), "a", "p", "m", REQUEST, "answer", 0);
        sampler.capture("gw-2", Instant.now(), "a", "p", "m", REQUEST, "answer", 0);
        sampler.flush();

        assertThat(written).extracting(EvalSample::id).containsExactly("gw-1", "gw-2");
        assertThat(sampler.sampled()).isEqualTo(2);
        assertThat(sampler.written()).isEqualTo(2);
    }

    @Test
    void dropsAndCountsWhenTheQueueIsFull() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EvalSampler sampler = new EvalSampler(EvalTestData.properties(true, 1.0, Map.of(), Map.of(), 2, 1000),
                List.of(written::addAll), meters, () -> 0.0);

        for (int i = 0; i < 5; i++) {
            sampler.capture("gw-" + i, Instant.now(), "a", "p", "m", REQUEST, "answer", 0);
        }
        sampler.flush();

        assertThat(sampler.sampled()).isEqualTo(2);
        assertThat(sampler.dropped()).isEqualTo(3);
        assertThat(written).hasSize(2);
        assertThat(meters.counter("gateway.eval.samples", "result", "sampled").count()).isEqualTo(2);
        assertThat(meters.counter("gateway.eval.samples", "result", "dropped").count()).isEqualTo(3);
    }

    @Test
    void aFailingSinkDoesNotStopTheOthers() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        EvalSink broken = new EvalSink() {
            @Override
            public void write(List<EvalSample> samples) {
                throw new IllegalStateException("disk full");
            }

            @Override
            public String name() {
                return "broken";
            }
        };
        EvalSampler sampler = new EvalSampler(EvalTestData.properties(true, 1.0, Map.of(), Map.of(), 10, 1000),
                List.of(broken, written::addAll), meters, () -> 0.0);

        sampler.capture("gw-1", Instant.now(), "a", "p", "m", REQUEST, "answer", 0);
        sampler.flush();

        assertThat(written).hasSize(1);
        assertThat(sampler.failed()).isEqualTo(1);
        assertThat(meters.counter("gateway.eval.sink.writes", "sink", "broken", "result", "failed").count())
                .isEqualTo(1);
    }

    @Test
    void captureNeverThrows() {
        EvalSampler sampler = sampler(EvalTestData.properties(true, 1.0, Map.of(), Map.of(), 10, 1000), 0.0);

        sampler.capture("gw-1", Instant.now(), "a", "p", "m", null, "answer", 0);

        assertThat(sampler.sampled()).isZero();
    }

    @Test
    void theBackgroundWriterDrainsTheQueueAndFlushesOnStop() {
        EvalSampler sampler = sampler(EvalTestData.properties(true, 1.0, Map.of(), Map.of(), 10, 1000), 0.0);
        sampler.start();
        assertThat(sampler.isRunning()).isTrue();

        sampler.capture("gw-1", Instant.now(), "a", "p", "m", REQUEST, "answer", 0);
        sampler.stop();

        assertThat(written).extracting(EvalSample::id).containsExactly("gw-1");
        assertThat(sampler.isRunning()).isFalse();
    }
}
