package io.github.rmkrdev.gateway.provider.openai;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.config.ProviderType;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ChatProviderFactory;
import org.springframework.stereotype.Component;

/** Builds providers for anything that speaks the OpenAI chat completions protocol. */
@Component
class OpenAiCompatibleProviderFactory implements ChatProviderFactory {

    @Override
    public ProviderType type() {
        return ProviderType.OPENAI_COMPATIBLE;
    }

    @Override
    public ChatProvider create(String id, GatewayProperties.Provider config) {
        return new OpenAiCompatibleProvider(id, config);
    }
}
