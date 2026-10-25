package io.github.rmkrdev.gateway.provider;

import io.github.rmkrdev.gateway.api.Usage;

/**
 * One piece of a streamed answer, already in the unified shape. Any field may be {@code null}:
 * providers send text, the finish reason and usage in separate events or together.
 *
 * @param content text to append to the answer
 * @param finishReason why generation stopped, on the last content chunk
 * @param usage token counts so far; the last one seen is the total
 */
public record ChatChunk(String content, String finishReason, Usage usage) {

    public static ChatChunk text(String content) {
        return new ChatChunk(content, null, null);
    }

    /** {@code true} when the chunk carries nothing worth passing on. */
    public boolean isEmpty() {
        return (content == null || content.isEmpty()) && finishReason == null && usage == null;
    }
}
