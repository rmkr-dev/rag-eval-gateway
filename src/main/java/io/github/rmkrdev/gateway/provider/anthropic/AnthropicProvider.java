package io.github.rmkrdev.gateway.provider.anthropic;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Native adapter for the Anthropic Messages API. System messages move to the top-level
 * {@code system} field, {@code max_tokens} is always sent because the API requires it, and
 * stop reasons and usage (including prompt cache reads and writes) map to the unified shape.
 */
class AnthropicProvider implements ChatProvider {

    static final String API_VERSION = "2023-06-01";

    private final String id;
    private final ProviderHttpClient http;
    private final String chatPath;
    private final int defaultMaxTokens;

    AnthropicProvider(String id, GatewayProperties.Provider config) {
        this.id = id;
        this.chatPath = config.chatPath();
        this.defaultMaxTokens = config.defaultMaxTokens() != null
                ? config.defaultMaxTokens() : GatewayProperties.Provider.ANTHROPIC_DEFAULT_MAX_TOKENS;
        this.http = new ProviderHttpClient(id, config, Map.of("anthropic-version", API_VERSION));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ProviderReply chat(String model, ChatRequest request) {
        WireResponse response = http.postJson(chatPath, toWire(model, request, false), WireResponse.class);
        if (response == null || response.content() == null) {
            throw new ProviderException(id, Failure.PROVIDER, null, "response has no content");
        }
        String text = response.content().stream()
                .filter(block -> "text".equals(block.type()) && block.text() != null)
                .map(WireBlock::text)
                .collect(Collectors.joining());
        Usage usage = response.usage() == null ? Usage.NONE : response.usage().toUsage();
        return new ProviderReply(new ChatMessage("assistant", text), finishReason(response.stopReason()), usage);
    }

    @Override
    public ChatStream stream(String model, ChatRequest request) {
        return new AnthropicStream(http.postStream(chatPath, toWire(model, request, true)));
    }

    private WireRequest toWire(String model, ChatRequest request, boolean stream) {
        List<String> system = new ArrayList<>();
        List<WireMessage> messages = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            switch (message.role()) {
                case "system", "developer" -> system.add(message.content());
                case "user", "assistant" -> messages.add(new WireMessage(message.role(), message.content()));
                default -> throw new ProviderException(id, Failure.REQUEST, 400,
                        "role '" + message.role() + "' is not supported by this provider");
            }
        }
        int maxTokens = request.maxTokens() != null ? request.maxTokens() : defaultMaxTokens;
        return new WireRequest(model, system.isEmpty() ? null : String.join("\n\n", system), messages, maxTokens,
                request.temperature(), stream ? Boolean.TRUE : null);
    }

    /** Maps Anthropic stop reasons onto the OpenAI vocabulary callers already handle. */
    static String finishReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "end_turn", "stop_sequence", "pause_turn" -> "stop";
            case "max_tokens", "model_context_window_exceeded" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            default -> stopReason;
        };
    }

    /**
     * Translates Messages API stream events. Input and cache counts arrive in
     * {@code message_start}, the stop reason and final output count in {@code message_delta},
     * and {@code message_stop} ends the stream.
     */
    private final class AnthropicStream extends SseChatStream {

        private int inputTokens;
        private Integer cacheRead;
        private Integer cacheWrite;

        AnthropicStream(SseEventReader reader) {
            super(AnthropicProvider.this.id, reader, true);
        }

        @Override
        protected ChatChunk translate(SseEvent event) {
            JsonNode node = http.readTree(event.data());
            String type = event.event() != null ? event.event() : node.path("type").asString("");
            switch (type) {
                case "message_start" -> {
                    JsonNode usage = node.path("message").path("usage");
                    inputTokens = usage.path("input_tokens").asInt();
                    cacheRead = optionalInt(usage, "cache_read_input_tokens");
                    cacheWrite = optionalInt(usage, "cache_creation_input_tokens");
                    return null;
                }
                case "content_block_delta" -> {
                    JsonNode delta = node.path("delta");
                    return "text_delta".equals(delta.path("type").asString(""))
                            ? ChatChunk.text(delta.path("text").asString("")) : null;
                }
                case "message_delta" -> {
                    JsonNode usage = node.path("usage");
                    if (usage.has("input_tokens")) {
                        inputTokens = usage.path("input_tokens").asInt();
                    }
                    Usage total = WireUsage.combine(inputTokens, usage.path("output_tokens").asInt(),
                            cacheRead, cacheWrite);
                    return new ChatChunk(null, finishReason(text(node.path("delta").path("stop_reason"))),
                            total);
                }
                case "message_stop" -> {
                    end();
                    return null;
                }
                case "error" -> {
                    String errorType = node.path("error").path("type").asString("");
                    Failure failure = switch (errorType) {
                        case "overloaded_error", "api_error", "rate_limit_error", "timeout_error" -> Failure.TRANSIENT;
                        default -> Failure.PROVIDER;
                    };
                    throw new ProviderException(providerId, failure, null,
                            "stream error: " + errorType + ": " + ProviderHttpClient.errorMessage(event.data()));
                }
                default -> {
                    return null;
                }
            }
        }

        private static String text(JsonNode node) {
            return node.isString() ? node.asString() : null;
        }

        private static Integer optionalInt(JsonNode node, String field) {
            return node.path(field).isNumber() ? node.path(field).asInt() : null;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireRequest(
            String model,
            String system,
            List<WireMessage> messages,
            @JsonProperty("max_tokens") int maxTokens,
            Double temperature,
            Boolean stream) {
    }

    record WireMessage(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResponse(List<WireBlock> content, @JsonProperty("stop_reason") String stopReason, WireUsage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireBlock(String type, String text) {
    }

    /** Anthropic reports cached prompt tokens apart from {@code input_tokens}; the unified count includes them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireUsage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens,
            @JsonProperty("cache_read_input_tokens") Integer cacheReadInputTokens,
            @JsonProperty("cache_creation_input_tokens") Integer cacheCreationInputTokens) {

        Usage toUsage() {
            return combine(inputTokens == null ? 0 : inputTokens, outputTokens == null ? 0 : outputTokens,
                    cacheReadInputTokens, cacheCreationInputTokens);
        }

        static Usage combine(int input, int output, Integer cacheRead, Integer cacheWrite) {
            int prompt = input + (cacheRead == null ? 0 : cacheRead) + (cacheWrite == null ? 0 : cacheWrite);
            return new Usage(prompt, output, cacheRead, cacheWrite);
        }
    }
}
