package io.github.rmkrdev.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Token counts reported by the provider.
 *
 * @param promptTokens tokens in the request, including any read from or written to a prompt
 *     cache
 * @param completionTokens tokens in the answer, including reasoning tokens where the provider
 *     reports them separately
 * @param cacheReadTokens prompt tokens served from the provider's prompt cache, when reported
 * @param cacheWriteTokens prompt tokens written to the provider's prompt cache, when reported
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Usage(
        @JsonProperty("prompt_tokens") int promptTokens,
        @JsonProperty("completion_tokens") int completionTokens,
        @JsonProperty("cache_read_tokens") Integer cacheReadTokens,
        @JsonProperty("cache_write_tokens") Integer cacheWriteTokens) {

    public static final Usage NONE = new Usage(0, 0);

    public Usage(int promptTokens, int completionTokens) {
        this(promptTokens, completionTokens, null, null);
    }

    @JsonProperty("total_tokens")
    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
