package io.github.rmkrdev.gateway.provider.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ProviderException;
import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import io.github.rmkrdev.gateway.provider.ProviderReply;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generic adapter for the OpenAI chat completions protocol. Base URL, key header, key prefix,
 * extra headers and model name all come from configuration, so OpenAI, Azure OpenAI (v1 API),
 * OpenRouter, Together AI, Groq, Mistral, Modal, Ollama and vLLM need no code.
 */
class OpenAiCompatibleProvider implements ChatProvider {

    private static final int MAX_ERROR_MESSAGE = 300;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String id;
    private final RestClient client;
    private final String chatPath;

    OpenAiCompatibleProvider(String id, GatewayProperties.Provider config) {
        this.id = id;
        this.chatPath = config.chatPath();
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                // Plain-HTTP servers such as Ollama and vLLM do not all handle an h2c upgrade.
                .version(config.baseUrl().startsWith("https:") ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(config.readTimeout());
        this.client = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(config.baseUrl())
                .defaultHeaders(headers -> {
                    config.headers().forEach(headers::set);
                    if (StringUtils.hasText(config.apiKey())) {
                        headers.set(config.apiKeyHeader(), nullToEmpty(config.apiKeyPrefix()) + config.apiKey());
                    }
                })
                .build();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ProviderReply chat(String model, ChatRequest request) {
        WireRequest body = new WireRequest(model, request.messages(), request.temperature(), request.maxTokens(), false);
        WireResponse response;
        try {
            response = client.post()
                    .uri(chatPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        if (res.getStatusCode().is2xxSuccessful()) {
                            return res.bodyTo(WireResponse.class);
                        }
                        String text = StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8);
                        throw new ProviderException(id, ProviderException.classify(status), status,
                                retryAfter(res.getHeaders()), "HTTP " + status + ": " + errorMessage(text), null);
                    });
        } catch (ProviderException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new ProviderException(id, Failure.TRANSIENT, null, null, "I/O error: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new ProviderException(id, Failure.PROVIDER, null, null, "unreadable response: " + e.getMessage(), e);
        }
        return toReply(response);
    }

    private ProviderReply toReply(WireResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()
                || response.choices().getFirst().message() == null) {
            throw new ProviderException(id, Failure.PROVIDER, null, "response has no choices");
        }
        WireChoice choice = response.choices().getFirst();
        String role = StringUtils.hasText(choice.message().role()) ? choice.message().role() : "assistant";
        Usage usage = response.usage() == null
                ? new Usage(0, 0)
                : new Usage(response.usage().promptTokens(), response.usage().completionTokens());
        return new ProviderReply(new ChatMessage(role, nullToEmpty(choice.message().content())),
                choice.finishReason(), usage);
    }

    static Duration retryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Pulls {@code error.message} out of an OpenAI-style error body, falling back to raw text. */
    static String errorMessage(String body) {
        String message = body;
        try {
            JsonNode node = JSON.readTree(body);
            JsonNode error = node.path("error");
            if (error.isObject() && error.path("message").isString()) {
                message = error.path("message").asString();
            } else if (error.isString()) {
                message = error.asString();
            } else if (node.path("message").isString()) {
                message = node.path("message").asString();
            }
        } catch (RuntimeException ignored) {
            // not JSON, keep the raw text
        }
        message = message == null ? "" : message.strip();
        return message.length() > MAX_ERROR_MESSAGE ? message.substring(0, MAX_ERROR_MESSAGE) + "..." : message;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record WireRequest(
            String model,
            List<ChatMessage> messages,
            Double temperature,
            @JsonProperty("max_tokens") Integer maxTokens,
            boolean stream) {
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
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens) {
    }
}
