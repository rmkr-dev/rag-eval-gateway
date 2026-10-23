package io.github.rmkrdev.gateway.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;

/**
 * The single request shape callers send, whatever provider ends up serving it.
 *
 * @param model model alias from {@code gateway.models}
 * @param messages conversation so far
 * @param temperature sampling temperature, optional
 * @param maxTokens completion token cap, optional
 * @param stream whether to stream the answer as server-sent events
 * @param metadata free-form tags recorded with the call, for example a feature name
 */
public record ChatRequest(
        @NotBlank String model,
        @NotEmpty List<@Valid ChatMessage> messages,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        @JsonProperty("max_tokens") @Positive Integer maxTokens,
        Boolean stream,
        Map<String, String> metadata) {

    public boolean streaming() {
        return Boolean.TRUE.equals(stream);
    }
}
