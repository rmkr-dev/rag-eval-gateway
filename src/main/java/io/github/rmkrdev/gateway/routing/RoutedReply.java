package io.github.rmkrdev.gateway.routing;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.provider.ProviderReply;
import java.util.List;

/**
 * A successful routed call.
 *
 * @param reply the provider's answer
 * @param target the provider and model that served it
 * @param failedAttempts failures before the successful call, empty on a clean first try
 */
public record RoutedReply(ProviderReply reply, GatewayProperties.Target target, List<Attempt> failedAttempts) {

    /** Total provider calls made, including the successful one. */
    public int calls() {
        return failedAttempts.size() + 1;
    }
}
