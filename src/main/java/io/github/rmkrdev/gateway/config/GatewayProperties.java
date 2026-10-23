package io.github.rmkrdev.gateway.config;

import jakarta.validation.Valid;
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
 */
@Validated
@ConfigurationProperties("gateway")
public record GatewayProperties(
        @DefaultValue Map<String, @Valid Provider> providers,
        @DefaultValue Map<String, @Valid ModelAlias> models) {

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
     */
    public record Provider(
            @NotNull @DefaultValue("openai-compatible") ProviderType type,
            @NotBlank String baseUrl,
            String apiKey,
            @NotBlank @DefaultValue("Authorization") String apiKeyHeader,
            @DefaultValue("Bearer ") String apiKeyPrefix,
            @NotNull @DefaultValue("5s") Duration connectTimeout,
            @NotNull @DefaultValue("60s") Duration readTimeout) {
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
