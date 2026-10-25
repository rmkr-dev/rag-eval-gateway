package io.github.rmkrdev.gateway.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.provider.ChatChunk;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ChatStream;
import io.github.rmkrdev.gateway.provider.ProviderException;
import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import io.github.rmkrdev.gateway.provider.ProviderReply;
import io.github.rmkrdev.gateway.provider.http.ProviderHttpClient;
import io.github.rmkrdev.gateway.provider.http.SseChatStream;
import io.github.rmkrdev.gateway.provider.http.SseEvent;
import io.github.rmkrdev.gateway.provider.http.SseEventReader;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/**
 * Generic adapter for the OpenAI chat completions protocol. Base URL, key header, key prefix,
 * extra headers and model name all come from configuration, so OpenAI, Azure OpenAI (v1 API),
 * OpenRouter, Together AI, Groq, Mistral, Modal, Ollama and vLLM need no code.
 */
class OpenAiCompatibleProvider implements ChatProvider {

    private static final StreamOptions INCLUDE_USAGE = new StreamOptions(true);

    private final String id;
    private final ProviderHttpClient http;
    private final String chatPath;

    OpenAiCompatibleProvider(String id, GatewayProperties.Provider config) {
        this.id = id;
        this.chatPath = config.chatPath();
        this.http = new ProviderHttpClient(id, config, Map.of());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ProviderReply chat(String model, ChatRequest request) {
        WireRequest body = new WireRequest(model, request.messages(), request.temperature(), request.maxTokens(),
                false, null);
        return toReply(http.postJson(chatPath, body, WireResponse.class));
    }

    @Override
    public ChatStream stream(String model, ChatRequest request) {
        WireRequest body = new WireRequest(model, request.messages(), request.temperature(), request.maxTokens(),
                true, INCLUDE_USAGE);
        return new OpenAiStream(http.postStream(chatPath, body));
    }

    private ProviderReply toReply(WireResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst().message() == null) {
            throw new ProviderException(id, Failure.PROVIDER, null, "response has no choices");
        }
        WireChoice choice = response.choices().getFirst();
        String role = StringUtils.hasText(choice.message().role()) ? choice.message().role() : "assistant";
        Usage usage = response.usage() == null ? Usage.NONE : response.usage().toUsage();
        return new ProviderReply(new ChatMessage(role, nullToEmpty(choice.message().content())),
                choice.finishReason(), usage);
    }

    static String errorMessage(String body) {
        return ProviderHttpClient.errorMessage(body);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Reads {@code data:} chunks until {@code [DONE]}; the usage-only chunk comes just before it. */
    private final class OpenAiStream extends SseChatStream {

        OpenAiStream(SseEventReader reader) {
            super(OpenAiCompatibleProvider.this.id, reader, true);
        }

        @Override
        protected ChatChunk translate(SseEvent event) {
            if ("[DONE]".equals(event.data().strip())) {
                end();
                return null;
            }
            JsonNode node = http.readTree(event.data());
            JsonNode error = node.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                throw new ProviderException(providerId, Failure.PROVIDER, null,
                        "stream error: " + ProviderHttpClient.errorMessage(event.data()));
            }
            JsonNode choice = node.path("choices").path(0);
            String content = choice.path("delta").path("content").isString()
                    ? choice.path("delta").path("content").asString() : null;
            String finish = choice.path("finish_reason").isString() ? choice.path("finish_reason").asString() : null;
            JsonNode usage = node.path("usage");
            return new ChatChunk(content, finish, usage.isObject() ? usage(usage) : null);
        }

        private static Usage usage(JsonNode usage) {
            JsonNode cached = usage.path("prompt_tokens_details").path("cached_tokens");
            return new Usage(usage.path("prompt_tokens").asInt(), usage.path("completion_tokens").asInt(),
                    cached.isNumber() ? cached.asInt() : null, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireRequest(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens,
            boolean stream,
            @JsonProperty("stream_options") StreamOptions streamOptions) {
    }

    record StreamOptions(@JsonProperty("include_usage") boolean includeUsage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResponse(List<WireChoice> choices, WireUsage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireChoice(WireMessage message, @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireMessage(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireUsage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens,
            @JsonProperty("prompt_tokens_details") TokenDetails promptTokensDetails) {

        Usage toUsage() {
            Integer cached = promptTokensDetails == null ? null : promptTokensDetails.cachedTokens();
            return new Usage(promptTokens == null ? 0 : promptTokens, completionTokens == null ? 0 : completionTokens,
                    cached, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenDetails(@JsonProperty("cached_tokens") Integer cachedTokens) {
    }
}
