package io.github.rmkrdev.gateway.pii;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Personal data handling for prompts. Nothing is scanned until {@code mode}, a model alias or
 * a client asks for it.
 *
 * <pre>
 * gateway:
 *   pii:
 *     mode: redact
 *     models:
 *       chat-internal: off
 *     clients:
 *       billing-api: block
 *     custom:
 *       employee-id:
 *         pattern: 'EMP-\d{6}'
 * </pre>
 *
 * @param mode mode for every call that no model or client entry covers
 * @param models modes by model alias
 * @param clients modes by client name, as recorded for calls, ignoring case. When both a model
 *     and a client entry apply, the stricter one wins
 * @param detectors detector ids to use; empty for every built-in, custom and bean detector
 * @param custom extra detectors from regular expressions, by id
 * @param restore put the original values back in place of {@code redact} placeholders in
 *     answers, streamed or not
 * @param scrubLogs replace personal data in provider error details before they are logged,
 *     whatever the mode
 */
@Validated
@ConfigurationProperties("gateway.pii")
public record PiiProperties(
        @NotNull @DefaultValue("off") PiiMode mode,
        @DefaultValue Map<String, @NotNull PiiMode> models,
        @DefaultValue Map<String, @NotNull PiiMode> clients,
        @DefaultValue List<String> detectors,
        @DefaultValue Map<String, @Valid Custom> custom,
        @DefaultValue("false") boolean restore,
        @DefaultValue("true") boolean scrubLogs) {

    /**
     * A detector from configuration.
     *
     * @param pattern Java regular expression for one value
     * @param label placeholder prefix; the id in upper case by default
     */
    public record Custom(@NotBlank String pattern, String label) {
    }

    /** Whether any call can be scanned. */
    public boolean active() {
        return mode != PiiMode.OFF
                || models.values().stream().anyMatch(m -> m != PiiMode.OFF)
                || clients.values().stream().anyMatch(m -> m != PiiMode.OFF);
    }
}
