package io.github.rmkrdev.gateway.provider.gemini;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.config.ProviderType;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ChatProviderFactory;
import org.springframework.stereotype.Component;

/** Builds providers that speak the Gemini API. */
@Component
class GeminiProviderFactory implements ChatProviderFactory {

    @Override
    public ProviderType type() {
        return ProviderType.GEMINI;
    }

    @Override
    public ChatProvider create(String id, GatewayProperties.Provider config) {
        return new GeminiProvider(id, config);
    }
}
