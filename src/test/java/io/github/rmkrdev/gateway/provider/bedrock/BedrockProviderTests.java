package io.github.rmkrdev.gateway.provider.bedrock;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.config.ProviderType;
import io.github.rmkrdev.gateway.provider.ChatChunk;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ChatStream;
import io.github.rmkrdev.gateway.provider.ProviderException;
import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import io.github.rmkrdev.gateway.provider.ProviderReply;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BedrockProviderTests {

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    private static final String MODEL = "anthropic.claude-3-5-sonnet-20240620-v1:0";
    private static final String CONVERSE = "/model/anthropic.claude-3-5-sonnet-20240620-v1%3A0/converse";
    private static final String CONVERSE_STREAM = "/model/anthropic.claude-3-5-sonnet-20240620-v1%3A0/converse-stream";
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private static final String OK_BODY = """
            {"output":{"message":{"role":"assistant","content":[{"text":"Retrieval "},{"text":"augmented generation"}]}},
             "stopReason":"end_turn",
             "usage":{"inputTokens":20,"outputTokens":6,"totalTokens":136,"cacheReadInputTokens":100,"cacheWriteInputTokens":10},
             "metrics":{"latencyMs":420}}
            """;

    private final ChatRequest request = new ChatRequest("chat", List.of(
            new ChatMessage("system", "Be brief."),
            new ChatMessage("user", "What is RAG?"),
            new ChatMessage("assistant", "A pattern."),
            new ChatMessage("user", "Expand.")), 0.3, 100, false, null);

    private static GatewayProperties.Provider config(String apiKey, GatewayProperties.Aws aws, Duration readTimeout) {
        return new GatewayProperties.Provider(ProviderType.BEDROCK, upstream.baseUrl(), apiKey, null, null,
                Duration.ofSeconds(2), readTimeout, null, Map.of(), null, aws);
    }

    private static ChatProvider provider(GatewayProperties.Aws aws, Map<String, String> env) {
        return new BedrockProviderFactory(env::get, Clock.fixed(NOW, ZoneOffset.UTC))
                .create("bedrock", config(null, aws, Duration.ofSeconds(5)));
    }

    private static ChatProvider provider() {
        return provider(new GatewayProperties.Aws("us-east-1", "AKIDEXAMPLE", "secret-example", "token-example"),
                Map.of());
    }

    @Test
    void sendsConverseRequestWithSystemSplitOut() {
        upstream.stubFor(post(CONVERSE).willReturn(okJson(OK_BODY)));

        provider().chat(MODEL, request);

        upstream.verify(postRequestedFor(urlEqualTo(CONVERSE))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"messages":[{"role":"user","content":[{"text":"What is RAG?"}]},
                                     {"role":"assistant","content":[{"text":"A pattern."}]},
                                     {"role":"user","content":[{"text":"Expand."}]}],
                         "system":[{"text":"Be brief."}],
                         "inferenceConfig":{"maxTokens":100,"temperature":0.3}}
                        """)));
    }

    @Test
    void signsWithSigV4OverWhatTheServerReceived() {
        upstream.stubFor(post(CONVERSE).willReturn(okJson(OK_BODY)));

        provider().chat(MODEL, request);

        LoggedRequest received = upstream.findAll(postRequestedFor(urlEqualTo(CONVERSE))).getFirst();
        assertThat(received.getHeader("X-Amz-Date")).isEqualTo("20261030T120000Z");
        assertThat(received.getHeader("X-Amz-Security-Token")).isEqualTo("token-example");
        String authorization = received.getHeader("Authorization");
        assertThat(authorization).matches("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20261030/us-east-1/bedrock/aws4_request, "
                + "SignedHeaders=host;x-amz-date;x-amz-security-token, Signature=[0-9a-f]{64}");

        SigV4Signer.Signature expected = new SigV4Signer("us-east-1", "bedrock").sign(
                new SigV4Signer.Request("POST", URI.create(received.getAbsoluteUrl()).getRawPath(), null,
                        List.of(Map.entry("host", received.getHeader("Host"))), received.getBody()),
                new SigV4Signer.Credentials("AKIDEXAMPLE", "secret-example", "token-example"), NOW, false);
        assertThat(expected.canonicalRequest()).contains("/model/anthropic.claude-3-5-sonnet-20240620-v1%253A0/converse");
        assertThat(authorization).isEqualTo(expected.headers().get("Authorization"));
    }

    @Test
    void mapsTextStopReasonAndCacheAwareUsage() {
        upstream.stubFor(post(CONVERSE).willReturn(okJson(OK_BODY)));

        ProviderReply reply = provider().chat(MODEL, request);

        assertThat(reply.message()).isEqualTo(new ChatMessage("assistant", "Retrieval augmented generation"));
        assertThat(reply.finishReason()).isEqualTo("stop");
        assertThat(reply.usage()).isEqualTo(new Usage(130, 6, 100, 10));
    }

    @Test
    void fallsBackToEnvironmentCredentialsAndOmitsAbsentSessionToken() {
        upstream.stubFor(post(CONVERSE).willReturn(okJson("""
                {"output":{"message":{"role":"assistant","content":[{"reasoningContent":{}},{"text":"ok"}]}},
                 "stopReason":"max_tokens","usage":{"inputTokens":3,"outputTokens":5}}
                """)));
        ChatRequest minimal = new ChatRequest("chat", List.of(new ChatMessage("user", "hi")), null, null, null, null);

        ProviderReply reply = provider(new GatewayProperties.Aws("eu-west-1", null, null, null),
                Map.of("AWS_ACCESS_KEY_ID", "AKIDENV", "AWS_SECRET_ACCESS_KEY", "env-secret")).chat(MODEL, minimal);

        assertThat(reply.message().content()).isEqualTo("ok");
        assertThat(reply.finishReason()).isEqualTo("length");
        assertThat(reply.usage()).isEqualTo(new Usage(3, 5, null, null));
        upstream.verify(postRequestedFor(urlEqualTo(CONVERSE))
                .withHeader("Authorization", matching("AWS4-HMAC-SHA256 Credential=AKIDENV/20261030/eu-west-1/bedrock/"
                        + "aws4_request, SignedHeaders=host;x-amz-date, Signature=.*"))
                .withHeader("X-Amz-Security-Token", absent())
                .withRequestBody(equalToJson("{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"hi\"}]}]}")));
    }

    @Test
    void sendsABedrockApiKeyAsBearerTokenWithoutSigning() {
        upstream.stubFor(post(CONVERSE).willReturn(okJson(OK_BODY)));

        new BedrockProviderFactory(name -> null, Clock.systemUTC())
                .create("bedrock", config("bedrock-api-key", null, Duration.ofSeconds(5))).chat(MODEL, request);

        upstream.verify(postRequestedFor(urlEqualTo(CONVERSE))
                .withHeader("Authorization", equalTo("Bearer bedrock-api-key"))
                .withHeader("X-Amz-Date", absent()));
    }

    @Test
    void failsStartupWithoutRegionOrCredentials() {
        BedrockProviderFactory factory = new BedrockProviderFactory(name -> null, Clock.systemUTC());

        assertThatThrownBy(() -> factory.create("bedrock", config(null, null, Duration.ofSeconds(1))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("aws.region");
        assertThatThrownBy(() -> factory.create("bedrock",
                        config(null, new GatewayProperties.Aws("us-east-1", "AKIDEXAMPLE", null, null), Duration.ofSeconds(1))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("aws.secret-access-key")
                .hasMessageNotContaining("AKIDEXAMPLE");
    }

    @ParameterizedTest
    @CsvSource({
            "400, REQUEST, ValidationException",
            "413, REQUEST, ValidationException",
            "403, PROVIDER, AccessDeniedException",
            "404, PROVIDER, ResourceNotFoundException",
            "424, PROVIDER, ModelErrorException",
            "408, TRANSIENT, ModelTimeoutException",
            "429, TRANSIENT, ThrottlingException",
            "500, TRANSIENT, InternalServerException",
            "503, TRANSIENT, ServiceUnavailableException"
    })
    void mapsHttpErrorsOntoRoutingFailures(int status, Failure failure, String type) {
        upstream.stubFor(post(CONVERSE).willReturn(aResponse().withStatus(status)
                .withHeader("x-amzn-ErrorType", type + ":http://internal.amazon.com/coral/com.amazon.bedrock/")
                .withHeader("Retry-After", "3")
                .withBody("{\"message\":\"" + type + " from Bedrock\"}")));

        ProviderException error = catchThrowableOfType(ProviderException.class, () -> provider().chat(MODEL, request));

        assertThat(error.failure()).isEqualTo(failure);
        assertThat(error.status()).isEqualTo(status);
        assertThat(error.getMessage()).isEqualTo("HTTP " + status + ": " + type + " from Bedrock");
        assertThat(error.getMessage()).doesNotContain("secret-example", "token-example");
    }

    @Test
    void rejectsUnsupportedRolesBeforeCalling() {
        ChatRequest tool = new ChatRequest("chat", List.of(new ChatMessage("tool", "{}")), null, null, null, null);

        ProviderException error = catchThrowableOfType(ProviderException.class, () -> provider().chat(MODEL, tool));

        assertThat(error.failure()).isEqualTo(Failure.REQUEST);
        assertThat(error.status()).isEqualTo(400);
        assertThat(upstream.getAllServeEvents()).isEmpty();
    }

    @Test
    void streamsEventFramesAsUnifiedChunks() {
        byte[] body = EventStreamFrames.stream()
                .event("messageStart", "{\"role\":\"assistant\",\"p\":\"abcd\"}")
                .event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"Retrieval \"},\"p\":\"ab\"}")
                .event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"reasoningContent\":{\"text\":\"hmm\"}}}")
                .event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"augmented generation\"}}")
                .event("contentBlockStop", "{\"contentBlockIndex\":0}")
                .event("messageStop", "{\"stopReason\":\"end_turn\"}")
                .event("metadata", "{\"usage\":{\"inputTokens\":20,\"outputTokens\":6,\"totalTokens\":126,"
                        + "\"cacheReadInputTokens\":100},\"metrics\":{\"latencyMs\":300}}")
                .bytes();
        upstream.stubFor(post(CONVERSE_STREAM).willReturn(aResponse()
                .withHeader("Content-Type", BedrockProvider.EVENT_STREAM).withBody(body)));

        List<ChatChunk> chunks = drain(provider().stream(MODEL, request));

        assertThat(chunks).containsExactly(
                ChatChunk.text("Retrieval "),
                ChatChunk.text("augmented generation"),
                new ChatChunk(null, "stop", null),
                new ChatChunk(null, null, new Usage(120, 6, 100, null)));
        upstream.verify(postRequestedFor(urlEqualTo(CONVERSE_STREAM))
                .withHeader("Accept", equalTo(BedrockProvider.EVENT_STREAM))
                .withHeader("Authorization", matching("AWS4-HMAC-SHA256 .*")));
    }

    @Test
    void streamWithoutMetadataStillEndsAfterMessageStop() {
        byte[] body = EventStreamFrames.stream()
                .event("contentBlockDelta", "{\"delta\":{\"text\":\"ok\"}}")
                .event("messageStop", "{\"stopReason\":\"guardrail_intervened\"}")
                .bytes();
        upstream.stubFor(post(CONVERSE_STREAM).willReturn(aResponse().withBody(body)));

        assertThat(drain(provider().stream(MODEL, request)))
                .containsExactly(ChatChunk.text("ok"), new ChatChunk(null, "content_filter", null));
    }

    @ParameterizedTest
    @CsvSource({
            "throttlingException, TRANSIENT, ",
            "modelStreamErrorException, TRANSIENT, ",
            "validationException, REQUEST, 400",
            "accessDeniedException, PROVIDER, "
    })
    void mapsStreamExceptions(String type, Failure failure, Integer status) {
        byte[] body = EventStreamFrames.stream()
                .exception(type, "{\"message\":\"stream went wrong\"}")
                .bytes();
        upstream.stubFor(post(CONVERSE_STREAM).willReturn(aResponse().withBody(body)));

        ChatStream stream = provider().stream(MODEL, request);
        ProviderException error = catchThrowableOfType(ProviderException.class, stream::next);

        assertThat(error.failure()).isEqualTo(failure);
        assertThat(error.status()).isEqualTo(status);
        assertThat(error.getMessage()).isEqualTo("stream error: " + type + ": stream went wrong");
    }

    @Test
    void truncatedOrCorruptStreamIsTransient() {
        byte[] good = EventStreamFrames.stream().event("contentBlockDelta", "{\"delta\":{\"text\":\"Hi\"}}").bytes();
        upstream.stubFor(post(CONVERSE_STREAM).willReturn(aResponse().withBody(good)));

        ChatStream stream = provider().stream(MODEL, request);
        assertThat(stream.next()).isEqualTo(ChatChunk.text("Hi"));
        ProviderException ended = catchThrowableOfType(ProviderException.class, stream::next);
        assertThat(ended.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(ended.getMessage()).contains("ended before completion");

        byte[] corrupt = good.clone();
        corrupt[corrupt.length - 1] ^= 1;
        upstream.stubFor(post(CONVERSE_STREAM).willReturn(aResponse().withBody(corrupt)));
        ProviderException bad = catchThrowableOfType(ProviderException.class, provider().stream(MODEL, request)::next);
        assertThat(bad.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(bad.getMessage()).contains("checksum");
    }

    @Test
    void streamErrorStatusSurfacesBeforeAnyChunk() {
        upstream.stubFor(post(CONVERSE_STREAM).willReturn(aResponse().withStatus(429)
                .withBody("{\"message\":\"Too many requests, please wait before trying again.\"}")));

        ProviderException error = catchThrowableOfType(ProviderException.class, () -> provider().stream(MODEL, request));

        assertThat(error.failure()).isEqualTo(Failure.TRANSIENT);
        assertThat(error.status()).isEqualTo(429);
        assertThat(error.getMessage()).contains("Too many requests");
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
}
