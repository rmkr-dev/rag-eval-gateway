package io.github.rmkrdev.gateway.provider.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.util.UriUtils;

/**
 * Native adapter for the Gemini API ({@code generateContent} and
 * {@code streamGenerateContent?alt=sse}). Assistant turns become {@code model} turns, system
 * messages become {@code systemInstruction}, and {@code usageMetadata} maps to the unified
 * usage, with thinking tokens counted as completion tokens because they are billed as output.
 */
class GeminiProvider implements ChatProvider {

    private final String id;
    private final ProviderHttpClient http;
    private final String modelsPath;
    private final Integer defaultMaxTokens;

    GeminiProvider(String id, GatewayProperties.Provider config) {
        this.id = id;
        this.modelsPath = config.chatPath();
        this.defaultMaxTokens = config.defaultMaxTokens();
        this.http = new ProviderHttpClient(id, config, Map.of());
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ProviderReply chat(String model, ChatRequest request) {
        WireResponse response = http.postJson(path(model, ":generateContent"), toWire(request), WireResponse.class);
        if (response == null) {
            throw new ProviderException(id, Failure.PROVIDER, null, "empty response");
        }
        Usage usage = response.usageMetadata() == null ? Usage.NONE : response.usageMetadata().toUsage();
        if (response.candidates() == null || response.candidates().isEmpty()) {
            if (response.promptFeedback() != null && response.promptFeedback().blockReason() != null) {
                return new ProviderReply(new ChatMessage("assistant", ""), "content_filter", usage);
            }
            throw new ProviderException(id, Failure.PROVIDER, null, "response has no candidates");
        }
        WireCandidate candidate = response.candidates().getFirst();
        return new ProviderReply(new ChatMessage("assistant", candidate.text()),
                finishReason(candidate.finishReason()), usage);
    }

    @Override
    public ChatStream stream(String model, ChatRequest request) {
        return new GeminiStream(http.postStream(path(model, ":streamGenerateContent?alt=sse"), toWire(request)));
    }

    private String path(String model, String method) {
        String name = model.startsWith("models/") ? model.substring("models/".length()) : model;
        return modelsPath + "/" + UriUtils.encodePathSegment(name, StandardCharsets.UTF_8) + method;
    }

    private WireRequest toWire(ChatRequest request) {
        List<WirePart> system = new ArrayList<>();
        List<WireContent> contents = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            switch (message.role()) {
                case "system", "developer" -> system.add(new WirePart(message.content(), null));
                case "user" -> contents.add(new WireContent("user", List.of(new WirePart(message.content(), null))));
                case "assistant" -> contents.add(new WireContent("model", List.of(new WirePart(message.content(), null))));
                default -> throw new ProviderException(id, Failure.REQUEST, 400,
                        "role '" + message.role() + "' is not supported by this provider");
            }
        }
        Integer maxTokens = request.maxTokens() != null ? request.maxTokens() : defaultMaxTokens;
        WireGenerationConfig config = request.temperature() == null && maxTokens == null
                ? null : new WireGenerationConfig(request.temperature(), maxTokens);
        return new WireRequest(contents, system.isEmpty() ? null : new WireContent(null, system), config);
    }

    /** Maps Gemini finish reasons onto the OpenAI vocabulary callers already handle. */
    static String finishReason(String reason) {
        if (reason == null || reason.equals("FINISH_REASON_UNSPECIFIED")) {
            return null;
        }
        return switch (reason) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY" -> "content_filter";
            default -> reason.toLowerCase(Locale.ROOT);
        };
    }

    /** Each event is a full {@code GenerateContentResponse} holding the next piece of text. */
    private final class GeminiStream extends SseChatStream {

        GeminiStream(SseEventReader reader) {
            super(GeminiProvider.this.id, reader, false);
        }

        @Override
        protected ChatChunk translate(SseEvent event) {
            WireResponse response = http.readEvent(event.data(), WireResponse.class);
            Usage usage = response.usageMetadata() == null ? null : response.usageMetadata().toUsage();
            if (response.candidates() == null || response.candidates().isEmpty()) {
                String blocked = response.promptFeedback() != null && response.promptFeedback().blockReason() != null
                        ? "content_filter" : null;
                return new ChatChunk(null, blocked, usage);
            }
            WireCandidate candidate = response.candidates().getFirst();
            return new ChatChunk(candidate.text(), finishReason(candidate.finishReason()), usage);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireRequest(List<WireContent> contents, WireContent systemInstruction, WireGenerationConfig generationConfig) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireContent(String role, List<WirePart> parts) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WirePart(String text, Boolean thought) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireGenerationConfig(Double temperature, Integer maxOutputTokens) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResponse(List<WireCandidate> candidates, WireUsage usageMetadata, WireFeedback promptFeedback) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireCandidate(WireContent content, String finishReason) {

        /** Answer text, leaving out thought summaries. */
        String text() {
            if (content == null || content.parts() == null) {
                return "";
            }
            return content.parts().stream()
                    .filter(part -> part.text() != null && !Boolean.TRUE.equals(part.thought()))
                    .map(WirePart::text)
                    .collect(Collectors.joining());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireFeedback(String blockReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireUsage(Integer promptTokenCount, Integer candidatesTokenCount, Integer thoughtsTokenCount,
            Integer cachedContentTokenCount) {

        Usage toUsage() {
            return new Usage(orZero(promptTokenCount), orZero(candidatesTokenCount) + orZero(thoughtsTokenCount),
                    cachedContentTokenCount, null);
        }

        private static int orZero(Integer value) {
            return value == null ? 0 : value;
        }
    }
}
