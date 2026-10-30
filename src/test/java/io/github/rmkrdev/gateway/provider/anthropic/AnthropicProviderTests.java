package io.github.rmkrdev.gateway.provider.anthropic;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
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

class AnthropicProviderTests {

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String OK_BODY = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
             "content":[{"type":"text","text":"Retrieval "},{"type":"text","text":"augmented generation"}],
             "stop_reason":"end_turn",
             "usage":{"input_tokens":20,"output_tokens":6,"cache_read_input_tokens":100,"cache_creation_input_tokens":10}}
            """;

    private final ChatRequest request = new ChatRequest("chat", List.of(
            new ChatMessage("system", "Be brief."),
            new ChatMessage("user", "What is RAG?"),
            new ChatMessage("assistant", "A pattern."),
            new ChatMessage("system", "Answer in English."),
            new ChatMessage("user", "Expand.")), 0.3, 100, false, null);

    private AnthropicProvider provider(Map<String, String> headers, Integer defaultMaxTokens) {
        return new AnthropicProvider("claude", new GatewayProperties.Provider(ProviderType.ANTHROPIC,
                upstream.baseUrl(), "sk-ant-test", null, null, Duration.ofSeconds(2), Duration.ofSeconds(5),
                null, headers, defaultMaxTokens, null));
    }

    private AnthropicProvider provider() {
        return provider(Map.of(), null);
    }

    @Test
    void sendsMessagesRequestWithSystemSplitOut() {
        upstream.stubFor(post("/v1/messages").willReturn(okJson(OK_BODY)));

        provider().chat("claude-sonnet-4-5", request);

        upstream.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("sk-ant-test"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withHeader("Authorization", absent())
                .withRequestBody(equalToJson("""
                        {"model":"claude-sonnet-4-5","system":"Be brief.\\n\\nAnswer in English.",
                         "messages":[{"role":"user","content":"What is RAG?"},
                                     {"role":"assistant","content":"A pattern."},
                                     {"role":"user","content":"Expand."}],
                         "max_tokens":100,"temperature":0.3}
                        """)));
    }

    @Test
    void mapsTextStopReasonAndCacheAwareUsage() {
        upstream.stubFor(post("/v1/messages").willReturn(okJson(OK_BODY)));

        ProviderReply reply = provider().chat("claude-sonnet-4-5", request);

        assertThat(reply.message()).isEqualTo(new ChatMessage("assistant", "Retrieval augmented generation"));
        assertThat(reply.finishReason()).isEqualTo("stop");
        assertThat(reply.usage()).isEqualTo(new Usage(130, 6, 100, 10));
    }

    @Test
    void alwaysSendsMaxTokensAndLetsConfigPinTheVersion() {
        upstream.stubFor(post("/v1/messages").willReturn(okJson("""
                {"content":[{"type":"text","text":"ok"}],"stop_reason":"max_tokens","usage":{"input_tokens":3,"output_tokens":5}}
                """)));
        ChatRequest minimal = new ChatRequest("chat", List.of(new ChatMessage("user", "hi")), null, null, null, null);

        ProviderReply fallbackDefault = provider().chat("m", minimal);
        provider(Map.of("anthropic-version", "2024-01-01"), 512).chat("m", minimal);

        assertThat(fallbackDefault.finishReason()).isEqualTo("length");
        assertThat(fallbackDefault.usage()).isEqualTo(new Usage(3, 5, null, null));
        upstream.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withRequestBody(equalToJson("""
                        {"model":"m","messages":[{"role":"user","content":"hi"}],"max_tokens":4096}
                        """)));
        upstream.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("anthropic-version", equalTo("2024-01-01"))
                .withRequestBody(equalToJson("""
                        {"model":"m","messages":[{"role":"user","content":"hi"}],"max_tokens":512}
                        """)));
    }

    @Test
    void mapsErrors() {
        upstream.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(529)
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")));
        ProviderException overloaded = catchThrowableOfType(ProviderException.class,
                () -> provider().chat("m", request));
        assertThat(overloaded.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(overloaded.getMessage()).isEqualTo("HTTP 529: Overloaded");

        upstream.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(400)
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"temperature: range\"}}")));
        assertThat(catchThrowableOfType(ProviderException.class, () -> provider().chat("m", request)).failure())
                .isEqualTo(Failure.REQUEST);

        upstream.stubFor(post("/v1/messages").willReturn(aResponse().withStatus(401)
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}")));
        assertThat(catchThrowableOfType(ProviderException.class, () -> provider().chat("m", request)).failure())
                .isEqualTo(Failure.PROVIDER);
    }

    @Test
    void unsupportedRoleIsRejectedWithoutACall() {
        ChatRequest tool = new ChatRequest("chat", List.of(new ChatMessage("tool", "{}")), null, null, null, null);

        ProviderException e = catchThrowableOfType(ProviderException.class, () -> provider().chat("m", tool));

        assertThat(e.failure()).isEqualTo(Failure.REQUEST);
        upstream.verify(0, postRequestedFor(urlEqualTo("/v1/messages")));
    }

    private static final String STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":25,"cache_read_input_tokens":50,"output_tokens":1}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

            event: ping
            data: {"type": "ping"}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" there"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":12}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

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
    void translatesStreamEventsIntoChunks() {
        upstream.stubFor(post("/v1/messages").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream").withBody(STREAM)));

        List<ChatChunk> chunks = drain(provider().stream("claude-sonnet-4-5", request));

        assertThat(chunks).containsExactly(
                ChatChunk.text("Hello"),
                ChatChunk.text(" there"),
                new ChatChunk(null, "stop", new Usage(75, 12, 50, null)));
        upstream.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withHeader("Accept", equalTo("text/event-stream"))
                .withRequestBody(equalToJson("""
                        {"model":"claude-sonnet-4-5","stream":true}
                        """, true, true)));
    }

    @Test
    void errorEventMidStreamIsClassified() {
        upstream.stubFor(post("/v1/messages").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream").withBody("""
                        event: content_block_delta
                        data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"Hel"}}

                        event: error
                        data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                        """)));

        ChatStream stream = provider().stream("m", request);

        assertThat(stream.next()).isEqualTo(ChatChunk.text("Hel"));
        ProviderException e = catchThrowableOfType(ProviderException.class, stream::next);
        assertThat(e.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(e.getMessage()).contains("overloaded_error", "Overloaded");
        stream.close();
    }

    @Test
    void stopReasonsMapToTheUnifiedVocabulary() {
        assertThat(AnthropicProvider.finishReason("stop_sequence")).isEqualTo("stop");
        assertThat(AnthropicProvider.finishReason("tool_use")).isEqualTo("tool_calls");
        assertThat(AnthropicProvider.finishReason("refusal")).isEqualTo("content_filter");
        assertThat(AnthropicProvider.finishReason("something_new")).isEqualTo("something_new");
        assertThat(AnthropicProvider.finishReason(null)).isNull();
    }
}
