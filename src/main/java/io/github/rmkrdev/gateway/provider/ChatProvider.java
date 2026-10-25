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

    /**
     * Opens a streaming chat call. The upstream status is checked before this returns, so a
     * rejected or failed call throws here and the router can still retry or fall back.
     *
     * @param model model name as the provider knows it
     * @param request the caller's request
     * @return the open stream, which the caller must close
     * @throws ProviderException when the call cannot be opened
     */
    default ChatStream stream(String model, ChatRequest request) {
        throw new ProviderException(id(), ProviderException.Failure.PROVIDER, null,
                "provider does not support streaming");
    }
}
