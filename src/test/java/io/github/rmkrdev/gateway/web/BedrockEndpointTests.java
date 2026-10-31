package io.github.rmkrdev.gateway.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.rmkrdev.gateway.provider.bedrock.EventStreamFrames;
import io.github.rmkrdev.gateway.tracking.CallRecord;
import io.github.rmkrdev.gateway.tracking.CallRecorder;
import io.github.rmkrdev.gateway.tracking.CallStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Bedrock behind the full gateway: routing, fallback, streaming and cost tracking. */
@SpringBootTest(properties = {
        "entra.security.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:bedrock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gateway.retry.max-attempts=2",
        "gateway.retry.initial-backoff=10ms",
        "gateway.retry.max-backoff=20ms",
        "gateway.providers.bedrock.type=bedrock",
        "gateway.providers.bedrock.aws.region=us-east-1",
        "gateway.providers.bedrock.aws.access-key-id=AKIDEXAMPLE",
        "gateway.providers.bedrock.aws.secret-access-key=secret-example",
        "gateway.models.chat.targets[0].provider=bedrock",
        "gateway.models.chat.targets[0].model=" + BedrockEndpointTests.MODEL,
        "gateway.models.chat.targets[1].provider=backup",
        "gateway.models.chat.targets[1].model=llama3.1",
        "gateway.models.lenient.targets[0].provider=bedrock",
        "gateway.models.lenient.targets[0].model=" + BedrockEndpointTests.MODEL,
        "gateway.models.lenient.targets[1].provider=backup",
        "gateway.models.lenient.targets[1].model=llama3.1",
        "gateway.models.lenient.fallback-on-bad-request=true",
        "gateway.pricing.prices[0].provider=bedrock",
        "gateway.pricing.prices[0].model=" + BedrockEndpointTests.MODEL,
        "gateway.pricing.prices[0].input=3.00",
        "gateway.pricing.prices[0].output=15.00",
        "gateway.pricing.prices[0].cache-read=0.30",
        "gateway.pricing.prices[0].cache-write=3.75"
})
@AutoConfigureMockMvc
class BedrockEndpointTests {

    static final String MODEL = "anthropic.claude-3-5-sonnet-20240620-v1:0";
    private static final String CONVERSE = "/model/anthropic.claude-3-5-sonnet-20240620-v1%3A0/converse";
    private static final String CONVERSE_STREAM = CONVERSE + "-stream";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @RegisterExtension
    static WireMockExtension bedrock = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension backup = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.bedrock.base-url", bedrock::baseUrl);
        registry.add("gateway.providers.backup.base-url", () -> backup.baseUrl() + "/v1");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    CallRecorder recorder;

    @Autowired
    CallStore store;

    private ResultActions chat(String alias, boolean stream) throws Exception {
        return mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"" + alias + "\",\"stream\":" + stream
                        + ",\"messages\":[{\"role\":\"user\",\"content\":\"What is RAG?\"}]}"));
    }

    private static String completion(String text) {
        return """
                {"choices":[{"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":7,"completion_tokens":4}}
                """.formatted(text);
    }

    @Test
    void answersFromBedrockAndStoresTheCost() throws Exception {
        bedrock.stubFor(WireMock.post(CONVERSE).willReturn(okJson("""
                {"output":{"message":{"role":"assistant","content":[{"text":"From Bedrock"}]}},"stopReason":"end_turn",
                 "usage":{"inputTokens":4000,"outputTokens":1000,"cacheReadInputTokens":5000,"cacheWriteInputTokens":1000}}
                """)));

        String body = chat("chat", false)
                .andExpect(status().isOk())
                .andExpect(header().string(ChatController.CALLS_HEADER, "1"))
                .andExpect(jsonPath("$.provider").value("bedrock"))
                .andExpect(jsonPath("$.provider_model").value(MODEL))
                .andExpect(jsonPath("$.message.content").value("From Bedrock"))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(10_000))
                .andExpect(jsonPath("$.usage.cache_read_tokens").value(5_000))
                .andReturn().getResponse().getContentAsString();
        recorder.flush();

        CallRecord record = store.find(JSON.readTree(body).path("id").asString()).orElseThrow();
        assertThat(record.provider()).isEqualTo("bedrock");
        assertThat(record.promptTokens()).isEqualTo(10_000);
        assertThat(record.cacheReadTokens()).isEqualTo(5_000);
        assertThat(record.cacheWriteTokens()).isEqualTo(1_000);
        // 4,000 x 3.00 + 5,000 x 0.30 + 1,000 x 3.75 + 1,000 x 15.00 = 32,250 per million
        assertThat(record.cost()).isEqualByComparingTo("0.03225");
        bedrock.verify(postRequestedFor(urlEqualTo(CONVERSE))
                .withHeader("Authorization", matching("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/.*")));
    }

    @Test
    void throttlingIsRetriedThenFallsBack() throws Exception {
        bedrock.stubFor(WireMock.post(CONVERSE).willReturn(aResponse().withStatus(429)
                .withBody("{\"message\":\"Too many requests\"}")));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("from backup"))));

        chat("chat", false)
                .andExpect(status().isOk())
                .andExpect(header().string(ChatController.CALLS_HEADER, "3"))
                .andExpect(jsonPath("$.provider").value("backup"));
        bedrock.verify(2, postRequestedFor(urlEqualTo(CONVERSE)));
    }

    @Test
    void validationErrorGoesBackUnlessTheAliasFallsBack() throws Exception {
        bedrock.stubFor(WireMock.post(CONVERSE).willReturn(aResponse().withStatus(400)
                .withHeader("x-amzn-ErrorType", "ValidationException")
                .withBody("{\"message\":\"Input is too long for requested model.\"}")));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("from backup"))));

        chat("chat", false)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Input is too long")));
        chat("lenient", false)
                .andExpect(status().isOk())
                .andExpect(header().string(ChatController.CALLS_HEADER, "2"))
                .andExpect(jsonPath("$.provider").value("backup"));
    }

    @Test
    void earlierRejectionThenLastTargetDownIsA502() throws Exception {
        bedrock.stubFor(WireMock.post(CONVERSE).willReturn(aResponse().withStatus(422)
                .withBody("{\"message\":\"unprocessable\"}")));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(503)));

        chat("lenient", false)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.attempts[0].provider").value("bedrock"))
                .andExpect(jsonPath("$.attempts[0].status").value(422))
                .andExpect(jsonPath("$.attempts[1].status").value(503));
    }

    @Test
    void streamsBedrockEventFramesAsServerSentEvents() throws Exception {
        bedrock.stubFor(WireMock.post(CONVERSE_STREAM).willReturn(aResponse()
                .withHeader("Content-Type", "application/vnd.amazon.eventstream")
                .withBody(EventStreamFrames.stream()
                        .event("messageStart", "{\"role\":\"assistant\"}")
                        .event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\"Retrieval\"}}")
                        .event("contentBlockDelta", "{\"contentBlockIndex\":0,\"delta\":{\"text\":\" augmented\"}}")
                        .event("messageStop", "{\"stopReason\":\"end_turn\"}")
                        .event("metadata", "{\"usage\":{\"inputTokens\":7,\"outputTokens\":3,\"totalTokens\":10}}")
                        .bytes())));

        List<String> data = dataLines(chat("chat", true).andExpect(status().isOk()));

        assertThat(data).hasSize(5).last().isEqualTo("[DONE]");
        JsonNode first = JSON.readTree(data.get(0));
        assertThat(first.path("provider").asString()).isEqualTo("bedrock");
        assertThat(first.path("choices").path(0).path("delta").path("content").asString()).isEqualTo("Retrieval");
        assertThat(JSON.readTree(data.get(2)).path("choices").path(0).path("finish_reason").asString()).isEqualTo("stop");
        assertThat(JSON.readTree(data.get(3)).path("usage").path("total_tokens").asInt()).isEqualTo(10);
    }

    @Test
    void streamExceptionBeforeTheFirstTokenFallsBack() throws Exception {
        bedrock.stubFor(WireMock.post(CONVERSE_STREAM).willReturn(aResponse()
                .withBody(EventStreamFrames.stream()
                        .exception("serviceUnavailableException", "{\"message\":\"busy\"}").bytes())));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"from backup\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n")));

        List<String> data = dataLines(chat("chat", true)
                .andExpect(status().isOk())
                .andExpect(header().string(ChatController.CALLS_HEADER, "3")));

        assertThat(JSON.readTree(data.get(0)).path("provider").asString()).isEqualTo("backup");
        assertThat(data).last().isEqualTo("[DONE]");
    }

    private static List<String> dataLines(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> data = new ArrayList<>();
        for (String event : body.split("\n\n")) {
            if (!event.isBlank()) {
                data.add(event.substring("data: ".length()));
            }
        }
        return data;
    }
}
