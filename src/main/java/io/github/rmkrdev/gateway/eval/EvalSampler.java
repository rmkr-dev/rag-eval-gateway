package io.github.rmkrdev.gateway.eval;

import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.ChatRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Decides which calls are sampled and hands them to the {@link EvalSink}s on a background
 * virtual thread. Nothing here can slow down or fail a caller: the decision is one random
 * number, a full queue drops the sample and counts it, and every error is caught.
 * <p>
 * Counts go to Micrometer as {@code gateway.eval.samples} with a {@code result} of
 * {@code sampled} or {@code dropped}, and {@code gateway.eval.sink.writes} with the sink name
 * and a {@code result} of {@code ok} or {@code failed}, when a registry is present.
 */
public class EvalSampler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EvalSampler.class);

    private final EvalProperties properties;
    private final List<EvalSink> sinks;
    private final MeterRegistry meters;
    private final DoubleSupplier random;
    private final Map<String, Double> clients = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final BlockingQueue<EvalSample> queue;
    private final AtomicLong sampled = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final Map<String, Counter> sinkCounters = new HashMap<>();
    private final Counter sampledCounter;
    private final Counter droppedCounter;
    private final Object writeLock = new Object();
    private volatile Thread writer;
    private volatile boolean running;

    public EvalSampler(EvalProperties properties, List<EvalSink> sinks, MeterRegistry meters) {
        this(properties, sinks, meters, () -> ThreadLocalRandom.current().nextDouble());
    }

    EvalSampler(EvalProperties properties, List<EvalSink> sinks, MeterRegistry meters, DoubleSupplier random) {
        this.properties = properties;
        this.sinks = List.copyOf(sinks);
        this.meters = meters;
        this.random = random;
        this.clients.putAll(properties.clients());
        this.queue = new ArrayBlockingQueue<>(properties.queueCapacity());
        this.sampledCounter = counter("gateway.eval.samples", "result", "sampled");
        this.droppedCounter = counter("gateway.eval.samples", "result", "dropped");
        for (EvalSink sink : this.sinks) {
            sinkCounters.put(sink.name() + ":ok", counter("gateway.eval.sink.writes", "sink", sink.name(), "result", "ok"));
            sinkCounters.put(sink.name() + ":failed",
                    counter("gateway.eval.sink.writes", "sink", sink.name(), "result", "failed"));
        }
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** The sample rate for one call: the client entry, else the model entry, else the default. */
    public double rateFor(String client, String model) {
        Double byClient = client == null ? null : clients.get(client);
        if (byClient != null) {
            return byClient;
        }
        Double byModel = model == null ? null : properties.models().get(model);
        return byModel != null ? byModel : properties.sampleRate();
    }

    /** Whether to sample this call. Decided when the call arrives, so streams know to keep the answer. */
    public boolean shouldSample(String client, String model) {
        if (!properties.enabled()) {
            return false;
        }
        double rate = rateFor(client, model);
        return rate >= 1.0 || (rate > 0.0 && random.getAsDouble() < rate);
    }

    /** Longest answer kept for a sample, so streamed answers are buffered only up to this. */
    public int maxTextLength() {
        return properties.maxTextLength();
    }

    /**
     * Queues a finished call. {@code request} must be the request as sent to the provider and
     * {@code answer} the answer before any placeholder was restored. Never throws.
     */
    public void capture(String id, Instant startedAt, String client, String provider, String providerModel,
            ChatRequest request, String answer, int piiRedactions) {
        try {
            EvalSample sample = toSample(id, startedAt, Instant.now(), client, provider, providerModel, request,
                    answer, piiRedactions);
            if (queue.offer(sample)) {
                sampled.incrementAndGet();
                increment(sampledCounter);
            } else {
                long count = dropped.incrementAndGet();
                increment(droppedCounter);
                if (count == 1 || count % 1000 == 0) {
                    log.warn("eval sample queue is full, {} samples dropped so far", count);
                }
            }
        } catch (RuntimeException e) {
            log.warn("could not sample call {}: {}", id, e.toString());
        }
    }

    EvalSample toSample(String id, Instant startedAt, Instant completedAt, String client, String provider,
            String providerModel, ChatRequest request, String answer, int piiRedactions) {
        int max = properties.maxTextLength();
        List<ChatMessage> messages = new ArrayList<>(request.messages().size());
        for (ChatMessage message : request.messages()) {
            messages.add(new ChatMessage(message.role(), cut(message.content(), max)));
        }
        List<String> context = new ArrayList<>();
        if (request.evalContext() != null) {
            for (String passage : request.evalContext()) {
                if (passage != null && !passage.isBlank()) {
                    context.add(cut(passage, max));
                }
            }
        }
        return new EvalSample(id, startedAt, completedAt, client, request.model(), provider, providerModel,
                request.streaming(), EvalSample.question(messages), List.copyOf(context),
                cut(answer == null ? "" : answer, max), List.copyOf(messages), properties.tags(), piiRedactions, null);
    }

    /** Writes everything queued so far on the calling thread. */
    public void flush() {
        synchronized (writeLock) {
            List<EvalSample> batch = new ArrayList<>(properties.batchSize());
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
                EvalSample first = queue.poll(waitNanos, TimeUnit.NANOSECONDS);
                if (first == null) {
                    continue;
                }
                synchronized (writeLock) {
                    List<EvalSample> batch = new ArrayList<>(properties.batchSize());
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

    private void write(List<EvalSample> batch) {
        List<EvalSample> samples = List.copyOf(batch);
        for (EvalSink sink : sinks) {
            try {
                sink.write(samples);
                written.addAndGet(samples.size());
                increment(sinkCounters.get(sink.name() + ":ok"), samples.size());
            } catch (RuntimeException e) {
                failed.addAndGet(samples.size());
                increment(sinkCounters.get(sink.name() + ":failed"), samples.size());
                log.warn("eval sink {} could not write {} samples: {}", sink.name(), samples.size(), e.toString());
            }
        }
    }

    public long sampled() {
        return sampled.get();
    }

    public long dropped() {
        return dropped.get();
    }

    /** Samples written, counted once per sink. */
    public long written() {
        return written.get();
    }

    /** Samples a sink failed to write, counted once per sink. */
    public long failed() {
        return failed.get();
    }

    public List<EvalSink> sinks() {
        return sinks;
    }

    @Override
    public void start() {
        if (!properties.enabled()) {
            return;
        }
        running = true;
        writer = Thread.ofVirtual().name("eval-sampler").start(this::drainLoop);
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
            writer = null;
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

    private Counter counter(String name, String... tags) {
        return meters == null ? null : Counter.builder(name).tags(tags).register(meters);
    }

    private static void increment(Counter counter) {
        increment(counter, 1);
    }

    private static void increment(Counter counter, int amount) {
        if (counter != null) {
            counter.increment(amount);
        }
    }

    static String cut(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }
}
