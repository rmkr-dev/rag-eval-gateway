package io.github.rmkrdev.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Root configuration for the gateway.
 *
 * <pre>
 * gateway:
 *   providers:
 *     openrouter:
 *       type: openai-compatible
 *       base-url: https://openrouter.ai/api/v1
 *       api-key: ${OPENROUTER_API_KEY}
 *   models:
 *     chat-default:
 *       targets:
 *         - provider: openrouter
 *           model: meta-llama/llama-3.1-70b-instruct
 * </pre>
 *
 * @param providers upstream providers by id
 * @param models model aliases callers use, each mapped to an ordered list of targets
 * @param retry how transient failures are retried on each target before falling back
 */
@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @DefaultValue Map<String, @Valid Provider> providers,
        @DefaultValue Map<String, @Valid ModelAlias> models,
        @NotNull @Valid @DefaultValue Retry retry) {

    /**
     * One upstream provider.
     *
     * @param type wire protocol
     * @param baseUrl API base URL, for example {@code https://api.openai.com/v1}
     * @param apiKey secret, normally injected from Key Vault or an environment variable
     * @param apiKeyHeader header that carries the key
     * @param apiKeyPrefix value prefix for the key header, empty for raw keys
     * @param connectTimeout TCP connect timeout
     * @param readTimeout response read timeout
     * @param chatPath path appended to the base URL for chat calls
     * @param headers extra headers sent on every call, for example OpenRouter's
     *     {@code HTTP-Referer}
     */
    public record Provider(
            @NotNull @DefaultValue("openai-compatible") ProviderType type,
            @NotBlank String baseUrl,
            String apiKey,
            @NotBlank @DefaultValue("Authorization") String apiKeyHeader,
            @DefaultValue("Bearer ") String apiKeyPrefix,
            @NotNull @DefaultValue("5s") Duration connectTimeout,
            @NotNull @DefaultValue("60s") Duration readTimeout,
            @NotBlank @DefaultValue("/chat/completions") String chatPath,
            @DefaultValue Map<String, String> headers) {
    }

    /**
     * Retry policy for transient failures (timeouts, connection errors, 408, 429, 5xx). Each
     * target gets up to {@code maxAttempts} tries; then the next target in the alias is used.
     *
     * @param maxAttempts tries per target, including the first
     * @param initialBackoff wait before the first retry
     * @param maxBackoff upper bound for any single wait, including a provider's
     *     {@code Retry-After}
     * @param multiplier growth factor between waits
     * @param jitter fraction of each wait that is randomised, 0 to disable
     */
    public record Retry(
            @Min(1) @DefaultValue("2") int maxAttempts,
            @NotNull @DefaultValue("200ms") Duration initialBackoff,
            @NotNull @DefaultValue("2s") Duration maxBackoff,
            @DecimalMin("1.0") @DefaultValue("2.0") double multiplier,
            @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.2") double jitter) {
    }

    /**
     * A model alias. Targets are tried in order, so later entries act as fallbacks.
     *
     * @param targets ordered provider and model pairs
     */
    public record ModelAlias(@NotEmpty List<@Valid Target> targets) {
    }

    /**
     * A concrete model on a provider.
     *
     * @param provider provider id from {@code gateway.providers}
     * @param model model name as the provider knows it
     */
    public record Target(@NotBlank String provider, @NotBlank String model) {
    }
}
