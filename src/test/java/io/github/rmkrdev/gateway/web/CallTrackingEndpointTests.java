package io.github.rmkrdev.gateway.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.rmkrdev.gateway.tracking.CallRecord;
import io.github.rmkrdev.gateway.tracking.CallRecorder;
import io.github.rmkrdev.gateway.tracking.CallStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "entra.security.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:tracking;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gateway.retry.max-attempts=1",
        "gateway.models.chat.targets[0].provider=primary",
        "gateway.models.chat.targets[0].model=gpt-4o-mini",
        "gateway.models.chat.targets[1].provider=backup",
        "gateway.models.chat.targets[1].model=llama3.1",
        "gateway.pricing.prices[0].provider=primary",
        "gateway.pricing.prices[0].model=gpt-4o-mini",
        "gateway.pricing.prices[0].input=0.15",
        "gateway.pricing.prices[0].output=0.60",
        "gateway.pricing.prices[0].cache-read=0.075"
})
@AutoConfigureMockMvc
class CallTrackingEndpointTests {

    @RegisterExtension
    static WireMockExtension primary = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension backup = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.primary.base-url", () -> primary.baseUrl() + "/v1");
        registry.add("gateway.providers.backup.base-url", () -> backup.baseUrl() + "/v1");
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    CallRecorder recorder;

    @Autowired
    CallStore store;

    private static String completion(int prompt, int completion, int cached) {
        return """
                {"id":"up-1","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":%d,"completion_tokens":%d,"prompt_tokens_details":{"cached_tokens":%d}}}
                """.formatted(prompt, completion, cached);
    }

    private String chat(String client, boolean stream) throws Exception {
        var request = post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"chat\",\"stream\":" + stream
                        + ",\"messages\":[{\"role\":\"user\",\"content\":\"What is RAG?\"}]}");
        if (client != null) {
            request.header(ChatController.CLIENT_HEADER, client);
        }
        MvcResult result = mvc.perform(request).andReturn();
        recorder.flush();
        String body = result.getResponse().getContentAsString();
        if (stream) {
            String first = body.lines().filter(l -> l.startsWith("data: {")).findFirst().orElseThrow();
            return JSON.readTree(first.substring(6)).path("id").asString();
        }
        return JSON.readTree(body).path("id").asString();
    }

    @Test
    void storesCostTokensAndLatencyForAPricedCall() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion(10_000, 2_000, 4_000))));

        String id = chat("billing-api", false);

        CallRecord record = store.find(id).orElseThrow();
        assertThat(record.client()).isEqualTo("billing-api");
        assertThat(record.model()).isEqualTo("chat");
        assertThat(record.provider()).isEqualTo("primary");
        assertThat(record.providerModel()).isEqualTo("gpt-4o-mini");
        assertThat(record.status()).isEqualTo(CallRecord.OK);
        assertThat(record.promptTokens()).isEqualTo(10_000);
        assertThat(record.cacheReadTokens()).isEqualTo(4_000);
        // 6,000 x 0.15 + 4,000 x 0.075 + 2,000 x 0.60 = 2,400 per million
        assertThat(record.cost()).isEqualByComparingTo("0.0024");
        assertThat(record.currency()).isEqualTo("USD");
        assertThat(record.timeToFirstTokenMs()).isNotNull();
        assertThat(record.totalMs()).isGreaterThanOrEqualTo(record.timeToFirstTokenMs());

        mvc.perform(get("/v1/calls/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client").value("billing-api"))
                .andExpect(jsonPath("$.provider_model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.cost").value(0.0024))
                .andExpect(jsonPath("$.ttft_ms").isNumber())
                .andExpect(jsonPath("$.messages").doesNotExist());
    }

    @Test
    void fallbackToAnUnpricedModelIsStoredWithoutCost() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(503)));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion(100, 20, 0))));

        CallRecord record = store.find(chat(null, false)).orElseThrow();

        assertThat(record.client()).isEqualTo("anonymous");
        assertThat(record.provider()).isEqualTo("backup");
        assertThat(record.failedAttempts()).isEqualTo(1);
        assertThat(record.cost()).isNull();
        assertThat(record.currency()).isNull();
    }

    @Test
    void streamedCallRecordsTimeToFirstToken() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

                        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: {"choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":100}}

                        data: [DONE]

                        """)));

        CallRecord record = store.find(chat("search", true)).orElseThrow();

        assertThat(record.streamed()).isTrue();
        assertThat(record.finishReason()).isEqualTo("stop");
        assertThat(record.timeToFirstTokenMs()).isNotNull();
        assertThat(record.cost()).isEqualByComparingTo("0.00021");
    }

    @Test
    void failedCallIsStoredAsAnError() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(503)));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(500)));
        long before = recorder.written();

        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .header(ChatController.CLIENT_HEADER, "batch-job")
                        .content("{\"model\":\"chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .andExpect(status().isBadGateway());
        recorder.flush();

        assertThat(recorder.written()).isEqualTo(before + 1);
        CallRecord record = store.recent(1).getFirst();
        assertThat(record.client()).isEqualTo("batch-job");
        assertThat(record.status()).isEqualTo(CallRecord.ERROR);
        assertThat(record.error()).isEqualTo("all_targets_failed");
        assertThat(record.failedAttempts()).isEqualTo(2);
        assertThat(record.provider()).isNull();
    }

    @Test
    void unknownCallIdIsNotFound() throws Exception {
        mvc.perform(get("/v1/calls/{id}", "gw-missing")).andExpect(status().isNotFound());
    }
}
