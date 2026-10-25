package io.github.rmkrdev.gateway.provider.anthropic;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.config.ProviderType;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ChatProviderFactory;
import org.springframework.stereotype.Component;

/** Builds providers that speak the Anthropic Messages API. */
@Component
class AnthropicProviderFactory implements ChatProviderFactory {

    @Override
    public ProviderType type() {
        return ProviderType.ANTHROPIC;
    }

    @Override
    public ChatProvider create(String id, GatewayProperties.Provider config) {
        return new AnthropicProvider(id, config);
    }
}
