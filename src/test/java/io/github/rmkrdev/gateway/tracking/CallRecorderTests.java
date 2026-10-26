package io.github.rmkrdev.gateway.tracking;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.call.CallOutcome;
import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import io.github.rmkrdev.gateway.routing.Attempt;
import io.github.rmkrdev.gateway.tracking.PricingProperties.Price;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CallRecorderTests {

    static class FakeStore extends CallStore {

        final List<List<CallRecord>> batches = new CopyOnWriteArrayList<>();
        volatile boolean broken;

        FakeStore() {
            super(new JdbcTemplate());
        }

        @Override
        public void insertAll(List<CallRecord> records) {
            if (broken) {
                throw new IllegalStateException("database down");
            }
            batches.add(List.copyOf(records));
        }
    }

    private static final PriceTable PRICES = new PriceTable(new PricingProperties("USD", List.of(
            new Price("openai", "gpt-4o-mini", new BigDecimal("0.15"), new BigDecimal("0.60"), null, null, null))));

    private static TrackingProperties props(int capacity, int batch) {
        return new TrackingProperties(true, capacity, batch, Duration.ofMillis(50), Duration.ZERO, Duration.ofHours(1));
    }

    private static CallOutcome outcome(String id, String provider, String model, String error) {
        return new CallOutcome(id, Instant.parse("2026-10-26T10:00:00Z"), "billing-api", "chat", provider, model,
                false, List.of(new Attempt("backup", "llama3.1", 1, Failure.TRANSIENT, 503)),
                new Usage(1_000, 500), "stop", Duration.ofMillis(120), Duration.ofMillis(340), error);
    }

    @Test
    void pricedCallKeepsCostCurrencyVersionAndLatency() {
        CallRecorder recorder = new CallRecorder(new FakeStore(), PRICES, props(10, 10));

        CallRecord record = recorder.toRecord(outcome("gw-1", "openai", "gpt-4o-mini", null));

        assertThat(record.status()).isEqualTo(CallRecord.OK);
        assertThat(record.client()).isEqualTo("billing-api");
        assertThat(record.cost()).isEqualByComparingTo("0.00045");
        assertThat(record.currency()).isEqualTo("USD");
        assertThat(record.priceVersion()).isEqualTo("initial");
        assertThat(record.timeToFirstTokenMs()).isEqualTo(120L);
        assertThat(record.totalMs()).isEqualTo(340L);
        assertThat(record.failedAttempts()).isEqualTo(1);
    }

    @Test
    void unpricedAndFailedCallsAreStillStored() {
        CallRecorder recorder = new CallRecorder(new FakeStore(), PRICES, props(10, 10));

        CallRecord unpriced = recorder.toRecord(outcome("gw-2", "local", "llama3.1", null));
        CallRecord failed = recorder.toRecord(outcome("gw-3", null, null, "all_targets_failed"));

        assertThat(unpriced.cost()).isNull();
        assertThat(unpriced.currency()).isNull();
        assertThat(failed.status()).isEqualTo(CallRecord.ERROR);
        assertThat(failed.error()).isEqualTo("all_targets_failed");
    }

    @Test
    void flushWritesInBatches() {
        FakeStore store = new FakeStore();
        CallRecorder recorder = new CallRecorder(store, PRICES, props(10, 2));
        for (int i = 0; i < 5; i++) {
            recorder.completed(outcome("gw-" + i, "openai", "gpt-4o-mini", null));
        }

        recorder.flush();

        assertThat(store.batches).extracting(List::size).containsExactly(2, 2, 1);
        assertThat(recorder.written()).isEqualTo(5);
    }

    @Test
    void fullQueueDropsInsteadOfBlocking() {
        CallRecorder recorder = new CallRecorder(new FakeStore(), PRICES, props(2, 10));
        for (int i = 0; i < 5; i++) {
            recorder.completed(outcome("gw-" + i, "openai", "gpt-4o-mini", null));
        }

        assertThat(recorder.dropped()).isEqualTo(3);
    }

    @Test
    void failedWriteIsCountedAndNeverThrows() {
        FakeStore store = new FakeStore();
        store.broken = true;
        CallRecorder recorder = new CallRecorder(store, PRICES, props(10, 10));
        recorder.completed(outcome("gw-1", "openai", "gpt-4o-mini", null));

        recorder.flush();

        assertThat(recorder.failed()).isEqualTo(1);
        assertThat(recorder.written()).isZero();
    }

    @Test
    void backgroundWriterDrainsAndStopFlushesTheRest() throws Exception {
        FakeStore store = new FakeStore();
        CallRecorder recorder = new CallRecorder(store, PRICES, props(100, 10));
        recorder.start();
        recorder.completed(outcome("gw-a", "openai", "gpt-4o-mini", null));
        for (int i = 0; i < 100 && recorder.written() == 0; i++) {
            Thread.sleep(20);
        }
        assertThat(recorder.written()).isEqualTo(1);

        recorder.completed(outcome("gw-b", "openai", "gpt-4o-mini", null));
        recorder.stop();

        assertThat(recorder.isRunning()).isFalse();
        assertThat(recorder.written()).isEqualTo(2);
    }
}
