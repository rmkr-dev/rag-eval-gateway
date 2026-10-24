package io.github.rmkrdev.gateway.provider;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.config.ProviderType;

/**
 * Creates providers for one wire protocol. Register an implementation as a Spring bean to add
 * a new protocol; every entry in {@code gateway.providers} with a matching {@code type} is
 * built through it at startup.
 */
public interface ChatProviderFactory {

    /** Wire protocol this factory handles. */
    ProviderType type();

    /**
     * @param id provider id from {@code gateway.providers}
     * @param config that provider's settings
     */
    ChatProvider create(String id, GatewayProperties.Provider config);
}
