package io.github.rmkrdev.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token counts reported by the provider.
 *
 * @param promptTokens tokens in the request
 * @param completionTokens tokens in the answer
 */
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens) {

    @JsonProperty("total_tokens")
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
