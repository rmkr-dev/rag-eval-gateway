package io.github.rmkrdev.gateway.routing;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.provider.ChatChunk;
import io.github.rmkrdev.gateway.provider.ChatStream;
import java.time.Duration;
import java.util.List;

/**
 * A streaming call that has produced its first chunk. Hands that chunk out first, then reads
 * on from the provider. Not thread-safe; one writer drains it.
 */
public final class RoutedStream implements AutoCloseable {

    /** An upstream stream with its first chunk already read. */
    record Opened(ChatStream stream, ChatChunk first, long firstChunkNanos) {
    }

    private final ChatStream stream;
    private final GatewayProperties.Target target;
    private final List<Attempt> failedAttempts;
    private final long startedNanos;
    private final long firstChunkNanos;
    private ChatChunk pending;

    RoutedStream(Opened opened, GatewayProperties.Target target, List<Attempt> failedAttempts, long startedNanos) {
        this.stream = opened.stream();
        this.pending = opened.first();
        this.firstChunkNanos = opened.firstChunkNanos();
        this.target = target;
        this.failedAttempts = failedAttempts;
        this.startedNanos = startedNanos;
    }

    /**
     * @return the next chunk, or {@code null} when the answer is complete
     * @throws io.github.rmkrdev.gateway.provider.ProviderException when the provider fails
     *     mid-stream
     */
    public ChatChunk next() {
        if (pending != null) {
            ChatChunk first = pending;
            pending = null;
            return first;
        }
        return stream.next();
    }

    public GatewayProperties.Target target() {
        return target;
    }

    public List<Attempt> failedAttempts() {
        return failedAttempts;
    }

    /** Provider calls made, including the one that is streaming. */
    public int calls() {
        return failedAttempts.size() + 1;
    }

    /** Time from routing start to the first chunk, retries and fallback included. */
    public Duration timeToFirstToken() {
        return Duration.ofNanos(firstChunkNanos - startedNanos);
    }

    /** Time from routing start until now. */
    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    @Override
    public void close() {
        stream.close();
    }
}
