package io.github.rmkrdev.gateway.provider;

import io.github.rmkrdev.gateway.api.ChatRequest;

/**
 * One configured upstream provider. Implementations translate the unified request into the
 * provider's wire format and back, and report failures as {@link ProviderException} so the
 * router can decide whether to retry, fall back or give up.
 */
public interface ChatProvider {

    /** Provider id from {@code gateway.providers}. */
    String id();

    /**
     * Sends one chat completion call.
     *
     * @param model model name as the provider knows it
     * @param request the caller's request; its {@code model} field is the alias, not this name
     * @return the provider's answer
     * @throws ProviderException when the call fails
     */
    ProviderReply chat(String model, ChatRequest request);
}
