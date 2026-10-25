package io.github.rmkrdev.gateway.provider.http;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.provider.ProviderException;
import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP plumbing shared by every adapter: timeouts, key and extra headers, and turning upstream
 * failures into classified {@link ProviderException}s. Adapters only deal with their wire
 * format.
 *
 * <p>Plain calls use the provider's {@code read-timeout} as a limit for the whole response.
 * Streaming calls use it for the response headers and then as the longest allowed gap between
 * events, so long answers are not cut off.
 */
public final class ProviderHttpClient {

    public static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int MAX_ERROR_MESSAGE = 300;
    private static final int MAX_ERROR_BODY = 16 * 1024;

    private final String providerId;
    private final String baseUrl;
    private final Duration readTimeout;
    private final Map<String, String> headers;
    private final HttpClient http;
    private final RestClient rest;

    /**
     * @param providerId provider id, used in errors
     * @param config provider settings
     * @param protocolHeaders headers the protocol needs, such as {@code anthropic-version};
     *     configured {@code headers} with the same name win
     */
    public ProviderHttpClient(String providerId, GatewayProperties.Provider config, Map<String, String> protocolHeaders) {
        this.providerId = providerId;
        this.baseUrl = stripTrailingSlash(config.baseUrl());
        this.readTimeout = config.readTimeout();
        Map<String, String> all = new LinkedHashMap<>(protocolHeaders);
        config.headers().forEach(all::put);
        if (StringUtils.hasText(config.apiKey())) {
            all.put(config.apiKeyHeader(), nullToEmpty(config.apiKeyPrefix()) + config.apiKey());
        }
        this.headers = Map.copyOf(all);
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                // Plain-HTTP servers such as Ollama and vLLM do not all handle an h2c upgrade.
                .version(baseUrl.startsWith("https:") ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(readTimeout);
        this.rest = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(baseUrl)
                .defaultHeaders(h -> headers.forEach(h::set))
                .build();
    }

    /**
     * Posts a JSON body and reads a JSON answer.
     *
     * @param path path after the base URL, already encoded
     * @throws ProviderException for non-2xx answers, I/O errors and unreadable bodies
     */
    public <T> T postJson(String path, Object body, Class<T> responseType) {
        try {
            return rest.post()
                    .uri(URI.create(baseUrl + path))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((req, res) -> {
                        int status = res.getStatusCode().value();
                        if (res.getStatusCode().is2xxSuccessful()) {
                            return res.bodyTo(responseType);
                        }
                        String text = StreamUtils.copyToString(res.getBody(), StandardCharsets.UTF_8);
                        throw httpError(status, res.getHeaders().getFirst(HttpHeaders.RETRY_AFTER), text);
                    });
        } catch (ProviderException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw new ProviderException(providerId, Failure.TRANSIENT, null, null, "I/O error: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new ProviderException(providerId, Failure.PROVIDER, null, null,
                    "unreadable response: " + e.getMessage(), e);
        }
    }

    /**
     * Posts a JSON body and opens the server-sent event stream that comes back. The status is
     * checked first, so failures before any event surface here and can still be retried.
     *
     * @param path path after the base URL, already encoded, query string included
     * @throws ProviderException for non-2xx answers and I/O errors
     */
    public SseEventReader postStream(String path, Object body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(readTimeout)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        headers.forEach(request::setHeader);
        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ProviderException(providerId, Failure.TRANSIENT, null, null, "I/O error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(providerId, Failure.TRANSIENT, null, null, "interrupted", e);
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return new SseEventReader(response.body(), readTimeout);
        }
        String text;
        try (InputStream in = response.body()) {
            text = new String(in.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
        } catch (IOException e) {
            text = "";
        }
        throw httpError(status, response.headers().firstValue(HttpHeaders.RETRY_AFTER).orElse(null), text);
    }

    /** Parses one JSON event payload, reporting garbage as a provider failure. */
    public JsonNode readTree(String json) {
        try {
            return JSON.readTree(json);
        } catch (JacksonException e) {
            throw new ProviderException(providerId, Failure.PROVIDER, null, null, "unreadable stream event", e);
        }
    }

    /** Binds one JSON event payload, reporting garbage as a provider failure. */
    public <T> T readEvent(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JacksonException e) {
            throw new ProviderException(providerId, Failure.PROVIDER, null, null, "unreadable stream event", e);
        }
    }

    private ProviderException httpError(int status, String retryAfter, String body) {
        return new ProviderException(providerId, ProviderException.classify(status), status,
                retryAfter(retryAfter), "HTTP " + status + ": " + errorMessage(body), null);
    }

    /** Reads a {@code Retry-After} value in seconds; HTTP dates are ignored. */
    public static Duration retryAfter(String value) {
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

    /**
     * Pulls the message out of an error body. Handles OpenAI and Gemini ({@code error.message}),
     * Anthropic ({@code {"type":"error","error":{...}}}) and bare {@code message} or
     * {@code error} strings, falling back to the raw text, truncated.
     */
    public static String errorMessage(String body) {
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

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
