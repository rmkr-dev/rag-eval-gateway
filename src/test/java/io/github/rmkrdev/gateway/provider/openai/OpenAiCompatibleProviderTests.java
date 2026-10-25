package io.github.rmkrdev.gateway.provider.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.sun.net.httpserver.HttpServer;
import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.config.ProviderType;
import io.github.rmkrdev.gateway.provider.ChatChunk;
import io.github.rmkrdev.gateway.provider.ChatStream;
import io.github.rmkrdev.gateway.provider.ProviderException;
import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import io.github.rmkrdev.gateway.provider.ProviderReply;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

class OpenAiCompatibleProviderTests {

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String OK_BODY = """
            {"id":"x","object":"chat.completion","model":"m",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hello there"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":12,"completion_tokens":3,"total_tokens":15}}
            """;

    private final ChatRequest request = new ChatRequest("chat-default",
            List.of(new ChatMessage("system", "Be brief."), new ChatMessage("user", "Hi")), 0.2, 64, false, null);

    private OpenAiCompatibleProvider provider(String apiKey, String header, String prefix, Map<String, String> headers,
            Duration readTimeout) {
        GatewayProperties.Provider config = new GatewayProperties.Provider(ProviderType.OPENAI_COMPATIBLE,
                upstream.baseUrl() + "/v1", apiKey, header, prefix, Duration.ofSeconds(2), readTimeout,
                "/chat/completions", headers, null);
        return new OpenAiCompatibleProvider("upstream", config);
    }

    private OpenAiCompatibleProvider provider() {
        return provider("sk-test", "Authorization", "Bearer ", Map.of(), Duration.ofSeconds(5));
    }

    @Test
    void sendsOpenAiRequestAndMapsReply() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        ProviderReply reply = provider().chat("meta-llama/llama-3.1-70b-instruct", request);

        assertThat(reply.message()).isEqualTo(new ChatMessage("assistant", "Hello there"));
        assertThat(reply.finishReason()).isEqualTo("stop");
        assertThat(reply.usage().promptTokens()).isEqualTo(12);
        assertThat(reply.usage().completionTokens()).isEqualTo(3);
        upstream.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test"))
                .withRequestBody(equalToJson("""
                        {"model":"meta-llama/llama-3.1-70b-instruct",
                         "messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"Hi"}],
                         "temperature":0.2,"max_tokens":64,"stream":false}
                        """)));
    }

    @Test
    void usesConfiguredKeyHeaderPrefixAndExtraHeaders() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));

        provider("azure-key", "api-key", "", Map.of("HTTP-Referer", "https://example.org"), Duration.ofSeconds(5))
                .chat("gpt-4o-mini", request);

        upstream.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("api-key", equalTo("azure-key"))
                .withHeader("HTTP-Referer", equalTo("https://example.org"))
                .withHeader("Authorization", absent()));
    }

    @Test
    void omitsKeyHeaderAndNullFieldsForKeylessLocalModels() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY)));
        ChatRequest minimal = new ChatRequest("local", List.of(new ChatMessage("user", "Hi")), null, null, null, null);

        provider(null, "Authorization", "Bearer ", Map.of(), Duration.ofSeconds(5)).chat("llama3.1", minimal);

        upstream.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", absent())
                .withRequestBody(equalToJson("""
                        {"model":"llama3.1","messages":[{"role":"user","content":"Hi"}],"stream":false}
                        """)));
    }

    @Test
    void missingUsageCountsAsZero() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(okJson("""
                {"choices":[{"message":{"content":"ok"},"finish_reason":"length"}]}
                """)));

        ProviderReply reply = provider().chat("m", request);

        assertThat(reply.message()).isEqualTo(new ChatMessage("assistant", "ok"));
        assertThat(reply.usage().totalTokens()).isZero();
    }

    @Test
    void rateLimitIsTransientWithRetryAfter() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "3")
                .withBody("{\"error\":{\"message\":\"Rate limit reached\"}}")));

        ProviderException e = catchThrowableOfType(ProviderException.class, () -> provider().chat("m", request));

        assertThat(e.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(e.status()).isEqualTo(429);
        assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(3));
        assertThat(e.getMessage()).isEqualTo("HTTP 429: Rate limit reached");
    }

    @Test
    void serverErrorIsTransient() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(503).withBody("busy")));

        ProviderException e = catchThrowableOfType(ProviderException.class, () -> provider().chat("m", request));

        assertThat(e.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(e.retryAfter()).isNull();
        assertThat(e.getMessage()).isEqualTo("HTTP 503: busy");
    }

    @Test
    void authFailureFallsBackWithoutRetry() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":\"invalid api key\"}")));

        ProviderException e = catchThrowableOfType(ProviderException.class, () -> provider().chat("m", request));

        assertThat(e.failure()).isEqualTo(Failure.PROVIDER);
        assertThat(e.status()).isEqualTo(401);
    }

    @Test
    void badRequestIsTheCallersProblem() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"message\":\"max_tokens is too large\"}}")));

        ProviderException e = catchThrowableOfType(ProviderException.class, () -> provider().chat("m", request));

        assertThat(e.failure()).isEqualTo(Failure.REQUEST);
        assertThat(e.getMessage()).contains("max_tokens is too large");
    }

    @Test
    void readTimeoutIsTransient() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(okJson(OK_BODY).withFixedDelay(1500)));

        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> provider("k", "Authorization", "Bearer ", Map.of(), Duration.ofMillis(300)).chat("m", request));

        assertThat(e.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(e.status()).isNull();
    }

    @Test
    void responseWithoutChoicesIsAProviderFailure() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(okJson("{\"choices\":[]}")));

        ProviderException e = catchThrowableOfType(ProviderException.class, () -> provider().chat("m", request));

        assertThat(e.failure()).isEqualTo(Failure.PROVIDER);
    }

    @Test
    void longErrorBodiesAreTruncated() {
        assertThat(OpenAiCompatibleProvider.errorMessage("x".repeat(1000))).hasSize(303).endsWith("...");
        assertThat(OpenAiCompatibleProvider.errorMessage("{\"message\":\"model not found\"}")).isEqualTo("model not found");
    }

    private static String sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        return body.toString();
    }

    private static List<ChatChunk> drain(ChatStream stream) {
        List<ChatChunk> chunks = new ArrayList<>();
        try (stream) {
            for (ChatChunk chunk = stream.next(); chunk != null; chunk = stream.next()) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    @Test
    void streamsChunksAndFinalUsageUntilDone() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(
                        "{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}",
                        "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}],\"usage\":null}",
                        "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}",
                        "{\"choices\":[],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":2,"
                                + "\"prompt_tokens_details\":{\"cached_tokens\":4}}}",
                        "[DONE]"))));

        List<ChatChunk> chunks = drain(provider().stream("gpt-4o-mini", request));

        assertThat(chunks).extracting(ChatChunk::content).containsExactly("Hel", "lo", null);
        assertThat(chunks.get(1).finishReason()).isEqualTo("stop");
        assertThat(chunks.get(2).usage()).isEqualTo(new Usage(9, 2, 4, null));
        upstream.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Accept", equalTo("text/event-stream"))
                .withHeader("Authorization", equalTo("Bearer sk-test"))
                .withRequestBody(equalToJson("""
                        {"model":"gpt-4o-mini",
                         "messages":[{"role":"system","content":"Be brief."},{"role":"user","content":"Hi"}],
                         "temperature":0.2,"max_tokens":64,"stream":true,"stream_options":{"include_usage":true}}
                        """)));
    }

    @Test
    void streamOpenFailureIsClassifiedBeforeAnyChunk() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "2")
                .withBody("{\"error\":{\"message\":\"slow down\"}}")));

        ProviderException e = catchThrowableOfType(ProviderException.class, () -> provider().stream("m", request));

        assertThat(e.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(e.status()).isEqualTo(429);
        assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(2));
        assertThat(e.getMessage()).isEqualTo("HTTP 429: slow down");
    }

    @Test
    void streamRejectionIsTheCallersProblem() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"message\":\"bad stream_options\"}}")));

        ProviderException e = catchThrowableOfType(ProviderException.class, () -> provider().stream("m", request));

        assertThat(e.failure()).isEqualTo(Failure.REQUEST);
    }

    @Test
    void errorEventMidStreamFailsTheStream() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse("{\"choices\":[{\"delta\":{\"content\":\"par\"}}]}",
                        "{\"error\":{\"message\":\"upstream overloaded\"}}"))));

        ChatStream stream = provider().stream("m", request);

        assertThat(stream.next().content()).isEqualTo("par");
        ProviderException e = catchThrowableOfType(ProviderException.class, stream::next);
        assertThat(e.getMessage()).contains("upstream overloaded");
        stream.close();
    }

    @Test
    void streamCutBeforeDoneIsTransient() {
        upstream.stubFor(post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse("{\"choices\":[{\"delta\":{\"content\":\"par\"}}]}"))));

        ChatStream stream = provider().stream("m", request);

        assertThat(stream.next().content()).isEqualTo("par");
        ProviderException e = catchThrowableOfType(ProviderException.class, stream::next);
        assertThat(e.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(e.getMessage()).isEqualTo("stream ended before completion");
    }

    @Test
    @Timeout(10)
    void quietStreamTimesOutBetweenEvents() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(sse("{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}").getBytes(StandardCharsets.UTF_8));
                out.flush();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException | IOException ignored) {
                // test is over
            }
        });
        server.start();
        try {
            GatewayProperties.Provider config = new GatewayProperties.Provider(ProviderType.OPENAI_COMPATIBLE,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", null, null, null,
                    Duration.ofSeconds(2), Duration.ofMillis(300), null, Map.of(), null);
            ChatStream stream = new OpenAiCompatibleProvider("quiet", config).stream("m", request);

            assertThat(stream.next().content()).isEqualTo("a");
            ProviderException e = catchThrowableOfType(ProviderException.class, stream::next);
            assertThat(e.failure()).isEqualTo(Failure.TRANSIENT);
            assertThat(e.getMessage()).contains("no data from provider for 300 ms");
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
