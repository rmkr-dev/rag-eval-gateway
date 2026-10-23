package io.github.rmkrdev.gateway.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One message in a conversation.
 *
 * @param role {@code system}, {@code user} or {@code assistant}
 * @param content message text
 */
public record ChatMessage(@NotBlank String role, @NotNull String content) {
}
