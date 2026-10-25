package io.github.rmkrdev.gateway.provider.http;

import io.github.rmkrdev.gateway.provider.ChatChunk;
import io.github.rmkrdev.gateway.provider.ChatStream;
import io.github.rmkrdev.gateway.provider.ProviderException;
import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import java.io.IOException;

/**
 * Base for adapters whose streaming API is server-sent events. Subclasses translate one event
 * at a time and call {@link #end()} when the provider signals the answer is complete.
 */
public abstract class SseChatStream implements ChatStream {

    protected final String providerId;
    private final SseEventReader reader;
    private final boolean needsEndMarker;
    private boolean ended;

    /**
     * @param needsEndMarker whether the protocol has an explicit end event; if so, a body that
     *     stops without one is reported as a failure instead of a short answer
     */
    protected SseChatStream(String providerId, SseEventReader reader, boolean needsEndMarker) {
        this.providerId = providerId;
        this.reader = reader;
        this.needsEndMarker = needsEndMarker;
    }

    /**
     * @return the unified chunk for this event, or {@code null} to skip it
     * @throws ProviderException when the event reports an upstream error
     */
    protected abstract ChatChunk translate(SseEvent event);

    /** Marks the answer complete; {@link #next()} returns {@code null} from now on. */
    protected final void end() {
        ended = true;
    }

    @Override
    public final ChatChunk next() {
        while (!ended) {
            SseEvent event;
            try {
                event = reader.next();
            } catch (IOException e) {
                close();
                throw new ProviderException(providerId, Failure.TRANSIENT, null, null,
                        "stream interrupted: " + e.getMessage(), e);
            }
            if (event == null) {
                ended = true;
                close();
                if (needsEndMarker) {
                    throw new ProviderException(providerId, Failure.TRANSIENT, null, "stream ended before completion");
                }
                return null;
            }
            ChatChunk chunk = translate(event);
            if (chunk != null && !chunk.isEmpty()) {
                return chunk;
            }
        }
        close();
        return null;
    }

    @Override
    public final void close() {
        reader.close();
    }
}
