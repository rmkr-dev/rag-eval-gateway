package io.github.rmkrdev.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The single response shape callers get back.
 *
 * @param id gateway call id, also used in cost and latency records
 * @param model alias the caller asked for
 * @param provider provider id that served the call
 * @param providerModel model name on that provider
 * @param message assistant answer
 * @param finishReason why generation stopped, as reported by the provider
 * @param usage token counts
 */
public record ChatResponse(
        String id,
        String model,
        String provider,
        @JsonProperty("provider_model") String providerModel,
        ChatMessage message,
        @JsonProperty("finish_reason") String finishReason,
        Usage usage) {
}
