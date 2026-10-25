package io.github.rmkrdev.gateway.provider.gemini;

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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class GeminiProviderTests {

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String PATH = "/v1beta/models/gemini-2.5-flash:generateContent";
    private static final String STREAM_PATH = "/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse";

    private static final String OK_BODY = """
            {"candidates":[{"content":{"role":"model","parts":[
                {"text":"thinking about it","thought":true},{"text":"RAG grounds "},{"text":"answers."}]},
              "finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":5,"thoughtsTokenCount":7,
              "cachedContentTokenCount":32,"totalTokenCount":52}}
            """;

    private final ChatRequest request = new ChatRequest("chat", List.of(
            new ChatMessage("system", "Be brief."),
            new ChatMessage("user", "What is RAG?"),
            new ChatMessage("assistant", "A pattern."),
            new ChatMessage("user", "Expand.")), 0.4, 80, false, null);

    private GeminiProvider provider(Integer defaultMaxTokens) {
        return new GeminiProvider("gemini", new GatewayProperties.Provider(ProviderType.GEMINI,
                upstream.baseUrl() + "/v1beta/", "g-key", null, null, Duration.ofSeconds(2), Duration.ofSeconds(5),
                null, Map.of(), defaultMaxTokens));
    }

    @Test
    void sendsGenerateContentWithRolesAndSystemInstruction() {
        upstream.stubFor(post(PATH).willReturn(okJson(OK_BODY)));

        provider(null).chat("models/gemini-2.5-flash", request);

        upstream.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("x-goog-api-key", equalTo("g-key"))
                .withRequestBody(equalToJson("""
                        {"contents":[{"role":"user","parts":[{"text":"What is RAG?"}]},
                                     {"role":"model","parts":[{"text":"A pattern."}]},
                                     {"role":"user","parts":[{"text":"Expand."}]}],
                         "systemInstruction":{"parts":[{"text":"Be brief."}]},
                         "generationConfig":{"temperature":0.4,"maxOutputTokens":80}}
                        """)));
    }

    @Test
    void mapsAnswerWithoutThoughtsAndUsageWithThinkingTokens() {
        upstream.stubFor(post(PATH).willReturn(okJson(OK_BODY)));

        ProviderReply reply = provider(null).chat("gemini-2.5-flash", request);

        assertThat(reply.message()).isEqualTo(new ChatMessage("assistant", "RAG grounds answers."));
        assertThat(reply.finishReason()).isEqualTo("stop");
        assertThat(reply.usage()).isEqualTo(new Usage(40, 12, 32, null));
    }

    @Test
    void omitsGenerationConfigUnlessSetAndUsesConfiguredDefaultCap() {
        upstream.stubFor(post(PATH).willReturn(okJson("""
                {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"MAX_TOKENS"}],
                 "usageMetadata":{"promptTokenCount":2,"candidatesTokenCount":1}}
                """)));
        ChatRequest minimal = new ChatRequest("chat", List.of(new ChatMessage("user", "hi")), null, null, null, null);

        ProviderReply reply = provider(null).chat("gemini-2.5-flash", minimal);
        provider(256).chat("gemini-2.5-flash", minimal);

        assertThat(reply.finishReason()).isEqualTo("length");
        assertThat(reply.usage()).isEqualTo(new Usage(2, 1));
        upstream.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"contents":[{"role":"user","parts":[{"text":"hi"}]}]}
                """)));
        upstream.verify(postRequestedFor(urlEqualTo(PATH)).withRequestBody(equalToJson("""
                {"contents":[{"role":"user","parts":[{"text":"hi"}]}],"generationConfig":{"maxOutputTokens":256}}
                """)));
    }

    @Test
    void blockedPromptIsAContentFilterFinish() {
        upstream.stubFor(post(PATH).willReturn(okJson("""
                {"promptFeedback":{"blockReason":"SAFETY"},"usageMetadata":{"promptTokenCount":9}}
                """)));

        ProviderReply reply = provider(null).chat("gemini-2.5-flash", request);

        assertThat(reply.message().content()).isEmpty();
        assertThat(reply.finishReason()).isEqualTo("content_filter");
        assertThat(reply.usage().promptTokens()).isEqualTo(9);
    }

    @Test
    void mapsErrors() {
        upstream.stubFor(post(PATH).willReturn(aResponse().withStatus(429)
                .withBody("{\"error\":{\"code\":429,\"message\":\"Resource exhausted\",\"status\":\"RESOURCE_EXHAUSTED\"}}")));
        ProviderException limited = catchThrowableOfType(ProviderException.class,
                () -> provider(null).chat("gemini-2.5-flash", request));
        assertThat(limited.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(limited.getMessage()).isEqualTo("HTTP 429: Resource exhausted");

        upstream.stubFor(post(PATH).willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"code\":400,\"message\":\"Invalid value\",\"status\":\"INVALID_ARGUMENT\"}}")));
        assertThat(catchThrowableOfType(ProviderException.class,
                () -> provider(null).chat("gemini-2.5-flash", request)).failure()).isEqualTo(Failure.REQUEST);

        upstream.stubFor(post(PATH).willReturn(aResponse().withStatus(403)
                .withBody("{\"error\":{\"code\":403,\"message\":\"API key not valid\",\"status\":\"PERMISSION_DENIED\"}}")));
        assertThat(catchThrowableOfType(ProviderException.class,
                () -> provider(null).chat("gemini-2.5-flash", request)).failure()).isEqualTo(Failure.PROVIDER);

        upstream.stubFor(post(PATH).willReturn(okJson("{\"candidates\":[]}")));
        assertThat(catchThrowableOfType(ProviderException.class,
                () -> provider(null).chat("gemini-2.5-flash", request)).failure()).isEqualTo(Failure.PROVIDER);
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
    void streamsServerSentEventsUntilTheBodyEnds() {
        upstream.stubFor(post(STREAM_PATH).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"candidates":[{"content":{"role":"model","parts":[{"text":"RAG "}]}}],"usageMetadata":{"promptTokenCount":40}}

                        data: {"candidates":[{"content":{"role":"model","parts":[{"text":"grounds answers."}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":40,"candidatesTokenCount":5,"thoughtsTokenCount":2}}

                        """)));

        List<ChatChunk> chunks = drain(provider(null).stream("gemini-2.5-flash", request));

        assertThat(chunks).containsExactly(
                new ChatChunk("RAG ", null, new Usage(40, 0)),
                new ChatChunk("grounds answers.", "stop", new Usage(40, 7)));
        upstream.verify(postRequestedFor(urlEqualTo(STREAM_PATH))
                .withHeader("x-goog-api-key", equalTo("g-key"))
                .withHeader("Accept", equalTo("text/event-stream")));
    }

    @Test
    void streamOpenFailureIsClassified() {
        upstream.stubFor(post(STREAM_PATH).willReturn(aResponse().withStatus(503)
                .withBody("{\"error\":{\"code\":503,\"message\":\"The model is overloaded.\"}}")));

        ProviderException e = catchThrowableOfType(ProviderException.class,
                () -> provider(null).stream("gemini-2.5-flash", request));

        assertThat(e.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(e.getMessage()).isEqualTo("HTTP 503: The model is overloaded.");
    }

    @Test
    void unreadableStreamEventIsAProviderFailure() {
        upstream.stubFor(post(STREAM_PATH).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream").withBody("data: {not json\n\n")));

        ChatStream stream = provider(null).stream("gemini-2.5-flash", request);

        assertThat(catchThrowableOfType(ProviderException.class, stream::next).failure()).isEqualTo(Failure.PROVIDER);
        stream.close();
    }

    @Test
    void finishReasonsMapToTheUnifiedVocabulary() {
        assertThat(GeminiProvider.finishReason("SAFETY")).isEqualTo("content_filter");
        assertThat(GeminiProvider.finishReason("MALFORMED_FUNCTION_CALL")).isEqualTo("malformed_function_call");
        assertThat(GeminiProvider.finishReason("FINISH_REASON_UNSPECIFIED")).isNull();
    }
}
