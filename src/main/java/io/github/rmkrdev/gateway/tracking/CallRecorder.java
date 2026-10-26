package io.github.rmkrdev.gateway.tracking;

import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.call.CallObserver;
import io.github.rmkrdev.gateway.call.CallOutcome;
import io.github.rmkrdev.gateway.tracking.PricingProperties.Price;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Prices every finished call and writes it to the {@link CallStore} in batches on a background
 * virtual thread. Callers never wait for the database: when the queue is full a call is dropped
 * and counted, and a failed batch is logged and dropped. Pending calls are flushed on shutdown.
 */
public class CallRecorder implements CallObserver, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(CallRecorder.class);

    private final CallStore store;
    private final PriceTable prices;
    private final TrackingProperties properties;
    private final BlockingQueue<CallRecord> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final Object writeLock = new Object();
    private volatile Thread writer;
    private volatile boolean running;

    public CallRecorder(CallStore store, PriceTable prices, TrackingProperties properties) {
        this.store = store;
        this.prices = prices;
        this.properties = properties;
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
    }

    @Override
    public void completed(CallOutcome outcome) {
        if (!queue.offer(toRecord(outcome))) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1000 == 0) {
                log.warn("call tracking queue is full, {} calls dropped so far", count);
            }
        }
    }

    CallRecord toRecord(CallOutcome outcome) {
        Usage usage = outcome.usage() == null ? Usage.NONE : outcome.usage();
        Optional<Price> price = prices.find(outcome.provider(), outcome.providerModel(), outcome.startedAt());
        BigDecimal cost = price.map(p -> PriceTable.cost(usage, p)).orElse(null);
        if (price.isEmpty() && outcome.provider() != null) {
            log.debug("no price for provider={} model={}, call {} stored without cost",
                    outcome.provider(), outcome.providerModel(), outcome.id());
        }
        return new CallRecord(
                outcome.id(),
                outcome.startedAt(),
                outcome.client(),
                truncate(outcome.model(), 128),
                outcome.provider(),
                truncate(outcome.providerModel(), 256),
                outcome.streamed(),
                outcome.succeeded() ? CallRecord.OK : CallRecord.ERROR,
                outcome.error(),
                truncate(outcome.finishReason(), 32),
                usage.promptTokens(),
                usage.completionTokens(),
                usage.cacheReadTokens(),
                usage.cacheWriteTokens(),
                cost,
                price.isPresent() ? prices.currency() : null,
                price.map(Price::version).orElse(null),
                millis(outcome.timeToFirstToken()),
                outcome.total() == null ? 0 : outcome.total().toMillis(),
                outcome.failedAttempts() == null ? 0 : outcome.failedAttempts().size());
    }

    /** Writes everything queued so far on the calling thread. */
    public void flush() {
        synchronized (writeLock) {
            List<CallRecord> batch = new ArrayList<>(properties.batchSize());
            while (queue.drainTo(batch, properties.batchSize()) > 0) {
                write(batch);
                batch.clear();
            }
        }
    }

    private void drainLoop() {
        long waitNanos = properties.flushInterval().toNanos();
        while (running || !queue.isEmpty()) {
            try {
                CallRecord first = queue.poll(waitNanos, TimeUnit.NANOSECONDS);
                if (first == null) {
                    continue;
                }
                synchronized (writeLock) {
                    List<CallRecord> batch = new ArrayList<>(properties.batchSize());
                    batch.add(first);
                    queue.drainTo(batch, properties.batchSize() - 1);
                    write(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void write(List<CallRecord> batch) {
        try {
            store.insertAll(batch);
            written.addAndGet(batch.size());
        } catch (RuntimeException e) {
            failed.addAndGet(batch.size());
            log.warn("could not store {} calls: {}", batch.size(), e.getMessage());
        }
    }

    public long dropped() {
        return dropped.get();
    }

    public long failed() {
        return failed.get();
    }

    public long written() {
        return written.get();
    }

    @Override
    public void start() {
        running = true;
        writer = Thread.ofVirtual().name("call-recorder").start(this::drainLoop);
    }

    @Override
    public void stop() {
        running = false;
        Thread thread = writer;
        if (thread != null) {
            try {
                thread.join(Duration.ofSeconds(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stop after the web server has drained requests, before the datasource closes. */
    @Override
    public int getPhase() {
        return SmartLifecycle.DEFAULT_PHASE - 1024;
    }

    private static Long millis(Duration duration) {
        return duration == null ? null : duration.toMillis();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
