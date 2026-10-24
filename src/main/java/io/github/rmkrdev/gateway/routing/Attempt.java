package io.github.rmkrdev.gateway.routing;

import io.github.rmkrdev.gateway.provider.ProviderException.Failure;

/**
 * One failed call on the way to an answer. Holds no URLs, keys or prompt text, so it is safe to
 * return to callers and to log.
 *
 * @param provider provider id
 * @param model model name on that provider
 * @param attempt 1-based try number on this target
 * @param failure how the failure was classified
 * @param status HTTP status from the provider, or {@code null} when no response arrived
 */
public record Attempt(String provider, String model, int attempt, Failure failure, Integer status) {
}
