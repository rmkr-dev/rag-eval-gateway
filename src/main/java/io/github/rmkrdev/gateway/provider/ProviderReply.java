package io.github.rmkrdev.gateway.provider;

import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.Usage;

/**
 * What a provider returned, already in the unified shape.
 *
 * @param message assistant answer
 * @param finishReason why generation stopped
 * @param usage token counts, zero when the provider does not report them
 */
public record ProviderReply(ChatMessage message, String finishReason, Usage usage) {
}
