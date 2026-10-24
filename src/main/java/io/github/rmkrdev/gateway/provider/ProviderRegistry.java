package io.github.rmkrdev.gateway.provider;

import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.config.ProviderType;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds one {@link ChatProvider} per entry in {@code gateway.providers}, using the factory
 * registered for its {@code type}. A provider whose type has no factory yet still starts, but
 * every call to it fails with {@link ProviderException.Failure#PROVIDER} so aliases fall back
 * past it.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, ChatProvider> providers;

    public ProviderRegistry(GatewayProperties properties, List<ChatProviderFactory> factories) {
        Map<ProviderType, ChatProviderFactory> byType = new EnumMap<>(ProviderType.class);
        for (ChatProviderFactory factory : factories) {
            ChatProviderFactory previous = byType.putIfAbsent(factory.type(), factory);
            if (previous != null) {
                throw new IllegalStateException("More than one provider factory for type " + factory.type());
            }
        }
        Map<String, ChatProvider> built = new LinkedHashMap<>();
        properties.providers().forEach((id, config) -> {
            ChatProviderFactory factory = byType.get(config.type());
            if (factory == null) {
                log.warn("Provider '{}' has type {} which has no adapter yet; calls to it will fall back",
                        id, config.type());
                built.put(id, new UnsupportedProvider(id, config.type()));
            } else {
                built.put(id, factory.create(id, config));
            }
        });
        this.providers = Map.copyOf(built);
    }

    public Optional<ChatProvider> find(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    private record UnsupportedProvider(String id, ProviderType type) implements ChatProvider {

        @Override
        public ProviderReply chat(String model, ChatRequest request) {
            throw new ProviderException(id, ProviderException.Failure.PROVIDER, null,
                    "no adapter for provider type " + type);
        }
    }
}
