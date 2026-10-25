package io.github.rmkrdev.gateway.provider;

/**
 * An open streaming call. Pull chunks with {@link #next()} until it returns {@code null}, and
 * always {@link #close()} it, also when the caller goes away early.
 */
public interface ChatStream extends AutoCloseable {

    /**
     * Blocks until the next non-empty chunk arrives.
     *
     * @return the chunk, or {@code null} once the provider finished the answer
     * @throws ProviderException when the upstream stream fails or ends early
     */
    ChatChunk next();

    /** Releases the upstream connection. Safe to call more than once. */
    @Override
    void close();
}
