package io.github.rmkrdev.gateway.provider.bedrock;

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
import io.github.rmkrdev.gateway.provider.http.RequestSigner;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * Native adapter for the Amazon Bedrock Converse API ({@code /model/{modelId}/converse} and
 * {@code /converse-stream}). Requests are signed with SigV4, or carry a Bedrock API key as a
 * bearer token when one is configured. System messages move to {@code system}, the stream's
 * binary event frames become unified chunks, and usage maps to the unified shape including
 * prompt cache reads and writes.
 */
class BedrockProvider implements ChatProvider {

    static final String SIGNING_NAME = "bedrock";
    static final String EVENT_STREAM = "application/vnd.amazon.eventstream";

    private final String id;
    private final ProviderHttpClient http;
    private final String modelPath;
    private final Integer defaultMaxTokens;

    /**
     * @param signer SigV4 signer, or {@code null} when the provider uses a Bedrock API key
     */
    BedrockProvider(String id, GatewayProperties.Provider config, RequestSigner signer) {
        this.id = id;
        this.modelPath = config.chatPath();
        this.defaultMaxTokens = config.defaultMaxTokens();
        this.http = new ProviderHttpClient(id, config, Map.of(), signer);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ProviderReply chat(String model, ChatRequest request) {
        WireResponse response = http.postJson(path(model, "/converse"), toWire(request), WireResponse.class);
        if (response == null || response.output() == null || response.output().message() == null) {
            throw new ProviderException(id, Failure.PROVIDER, null, "response has no output message");
        }
        Usage usage = response.usage() == null ? Usage.NONE : response.usage().toUsage();
        return new ProviderReply(new ChatMessage("assistant", response.output().message().text()),
                finishReason(response.stopReason()), usage);
    }

    @Override
    public ChatStream stream(String model, ChatRequest request) {
        return new BedrockStream(new EventStreamReader(
                http.postForStream(path(model, "/converse-stream"), toWire(request), EVENT_STREAM),
                http.readTimeout()));
    }

    /** Model ids and ARNs hold {@code :} and {@code /}, so the id is encoded as one segment. */
    private String path(String model, String operation) {
        return modelPath + "/" + SigV4Signer.uriEncode(model, true) + operation;
    }

    private WireRequest toWire(ChatRequest request) {
        List<WireText> system = new ArrayList<>();
        List<WireMessage> messages = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            switch (message.role()) {
                case "system", "developer" -> system.add(new WireText(message.content()));
                case "user", "assistant" -> messages.add(
                        new WireMessage(message.role(), List.of(new WireText(message.content()))));
                default -> throw new ProviderException(id, Failure.REQUEST, 400,
                        "role '" + message.role() + "' is not supported by this provider");
            }
        }
        Integer maxTokens = request.maxTokens() != null ? request.maxTokens() : defaultMaxTokens;
        WireInferenceConfig config = request.temperature() == null && maxTokens == null
                ? null : new WireInferenceConfig(maxTokens, request.temperature());
        return new WireRequest(messages, system.isEmpty() ? null : system, config);
    }

    /** Maps Converse stop reasons onto the OpenAI vocabulary callers already handle. */
    static String finishReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> "stop";
            case "max_tokens", "model_context_window_exceeded" -> "length";
            case "tool_use" -> "tool_calls";
            case "guardrail_intervened", "content_filtered" -> "content_filter";
            default -> stopReason;
        };
    }

    /** Classifies an exception sent inside the event stream. */
    static Failure streamFailure(String exceptionType) {
        return switch (exceptionType) {
            case "throttlingException", "serviceUnavailableException", "internalServerException",
                    "modelStreamErrorException" -> Failure.TRANSIENT;
            case "validationException" -> Failure.REQUEST;
            default -> Failure.PROVIDER;
        };
    }

    /**
     * Translates ConverseStream events: text arrives in {@code contentBlockDelta}, the stop
     * reason in {@code messageStop}, and usage in the closing {@code metadata} event. A body that
     * ends before {@code messageStop} is a failure, not a short answer.
     */
    private final class BedrockStream implements ChatStream {

        private final EventStreamReader reader;
        private boolean stopped;
        private boolean ended;

        BedrockStream(EventStreamReader reader) {
            this.reader = reader;
        }

        @Override
        public ChatChunk next() {
            while (!ended) {
                EventStreamReader.Message message;
                try {
                    message = reader.next();
                } catch (IOException e) {
                    close();
                    throw new ProviderException(id, Failure.TRANSIENT, null, null,
                            "stream interrupted: " + e.getMessage(), e);
                }
                if (message == null) {
                    ended = true;
                    close();
                    if (!stopped) {
                        throw new ProviderException(id, Failure.TRANSIENT, null, "stream ended before completion");
                    }
                    return null;
                }
                ChatChunk chunk = translate(message);
                if (chunk != null && !chunk.isEmpty()) {
                    return chunk;
                }
            }
            close();
            return null;
        }

        private ChatChunk translate(EventStreamReader.Message message) {
            String messageType = message.header(":message-type");
            if ("exception".equals(messageType)) {
                String type = String.valueOf(message.header(":exception-type"));
                Failure failure = streamFailure(type);
                throw new ProviderException(id, failure, failure == Failure.REQUEST ? 400 : null,
                        "stream error: " + type + ": " + ProviderHttpClient.errorMessage(message.payloadText()));
            }
            if ("error".equals(messageType)) {
                throw new ProviderException(id, Failure.PROVIDER, null,
                        "stream error: " + message.header(":error-code") + ": " + message.header(":error-message"));
            }
            String eventType = message.header(":event-type");
            if (eventType == null) {
                return null;
            }
            switch (eventType) {
                case "contentBlockDelta" -> {
                    JsonNode text = http.readTree(message.payloadText()).path("delta").path("text");
                    return text.isString() ? ChatChunk.text(text.asString()) : null;
                }
                case "messageStop" -> {
                    stopped = true;
                    String reason = http.readTree(message.payloadText()).path("stopReason").asString(null);
                    return new ChatChunk(null, finishReason(reason), null);
                }
                case "metadata" -> {
                    ended = true;
                    WireUsage usage = http.readEvent(message.payloadText(), WireMetadata.class).usage();
                    return usage == null ? null : new ChatChunk(null, null, usage.toUsage());
                }
                default -> {
                    return null;
                }
            }
        }

        @Override
        public void close() {
            reader.close();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireRequest(List<WireMessage> messages, List<WireText> system, WireInferenceConfig inferenceConfig) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireMessage(String role, List<WireText> content) {

        /** Answer text, leaving out reasoning, tool use and other non-text blocks. */
        String text() {
            if (content == null) {
                return "";
            }
            return content.stream()
                    .filter(block -> block != null && block.text() != null)
                    .map(WireText::text)
                    .collect(Collectors.joining());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireText(String text) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireInferenceConfig(Integer maxTokens, Double temperature) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireResponse(WireOutput output, String stopReason, WireUsage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireOutput(WireMessage message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireMetadata(WireUsage usage) {
    }

    /** Bedrock reports cached prompt tokens apart from {@code inputTokens}; the unified count includes them. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireUsage(Integer inputTokens, Integer outputTokens, Integer cacheReadInputTokens,
            Integer cacheWriteInputTokens) {

        Usage toUsage() {
            int prompt = orZero(inputTokens) + orZero(cacheReadInputTokens) + orZero(cacheWriteInputTokens);
            return new Usage(prompt, orZero(outputTokens), cacheReadInputTokens, cacheWriteInputTokens);
        }

        private static int orZero(Integer value) {
            return value == null ? 0 : value;
        }
    }
}
