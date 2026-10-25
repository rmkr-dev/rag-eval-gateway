package io.github.rmkrdev.gateway.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.rmkrdev.gateway.call.CallObserver;
import io.github.rmkrdev.gateway.call.CallOutcome;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
        "gateway.retry.max-attempts=2",
        "gateway.retry.initial-backoff=10ms",
        "gateway.retry.max-backoff=20ms",
        "gateway.models.chat.targets[0].provider=primary",
        "gateway.models.chat.targets[0].model=gpt-4o-mini",
        "gateway.models.chat.targets[1].provider=backup",
        "gateway.models.chat.targets[1].model=llama3.1",
        "gateway.models.claude.targets[0].provider=claude",
        "gateway.models.claude.targets[0].model=claude-sonnet-4-5",
        "gateway.providers.claude.type=anthropic"
})
@AutoConfigureMockMvc
class ChatStreamingEndpointTests {

    @RegisterExtension
    static WireMockExtension primary = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension backup = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension claude = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.primary.base-url", () -> primary.baseUrl() + "/v1");
        registry.add("gateway.providers.backup.base-url", () -> backup.baseUrl() + "/v1");
        registry.add("gateway.providers.claude.base-url", claude::baseUrl);
    }

    @TestConfiguration
    static class Recording {

        @Bean
        RecordingObserver recordingObserver() {
            return new RecordingObserver();
        }
    }

    static class RecordingObserver implements CallObserver {

        final List<CallOutcome> outcomes = new CopyOnWriteArrayList<>();

        @Override
        public void completed(CallOutcome outcome) {
            outcomes.add(outcome);
        }
    }

    private static final String BODY = """
            {"model":"chat","messages":[{"role":"user","content":"What is RAG?"}],"stream":true}
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    RecordingObserver observer;

    @BeforeEach
    void reset() {
        observer.outcomes.clear();
    }

    private static String sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        return body.toString();
    }

    private static String delta(String text) {
        return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":null}]}";
    }

    private static final String FINISH = "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}";
    private static final String USAGE = "{\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}";

    private ResultActions chat(String body) throws Exception {
        return mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static List<String> dataLines(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<String> data = new ArrayList<>();
        for (String event : body.split("\n\n")) {
            if (!event.isBlank()) {
                assertThat(event).startsWith("data: ");
                data.add(event.substring("data: ".length()));
            }
        }
        return data;
    }

    @Test
    void streamsOpenAiStyleChunksThenUsageThenDone() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(delta("Retrieval"), delta(" augmented"), FINISH, USAGE, "[DONE]"))));

        ResultActions result = chat(BODY)
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(header().string("Cache-Control", "no-cache"))
                .andExpect(header().string(ChatController.CALLS_HEADER, "1"));

        List<String> data = dataLines(result);
        assertThat(data).hasSize(5).last().isEqualTo("[DONE]");
        JsonNode first = JSON.readTree(data.get(0));
        assertThat(first.path("object").asString()).isEqualTo("chat.completion.chunk");
        assertThat(first.path("id").asString()).startsWith("gw-");
        assertThat(first.path("model").asString()).isEqualTo("chat");
        assertThat(first.path("provider").asString()).isEqualTo("primary");
        assertThat(first.path("provider_model").asString()).isEqualTo("gpt-4o-mini");
        assertThat(first.path("choices").path(0).path("delta").path("role").asString()).isEqualTo("assistant");
        assertThat(first.path("choices").path(0).path("delta").path("content").asString()).isEqualTo("Retrieval");
        assertThat(first.path("choices").path(0).has("finish_reason")).isTrue();
        assertThat(first.has("usage")).isFalse();
        JsonNode second = JSON.readTree(data.get(1));
        assertThat(second.path("id").asString()).isEqualTo(first.path("id").asString());
        assertThat(second.path("choices").path(0).path("delta").has("role")).isFalse();
        JsonNode finish = JSON.readTree(data.get(2));
        assertThat(finish.path("choices").path(0).path("finish_reason").asString()).isEqualTo("stop");
        JsonNode usage = JSON.readTree(data.get(3));
        assertThat(usage.path("choices").isEmpty()).isTrue();
        assertThat(usage.path("usage").path("total_tokens").asInt()).isEqualTo(10);

        assertThat(observer.outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.streamed()).isTrue();
            assertThat(outcome.succeeded()).isTrue();
            assertThat(outcome.id()).isEqualTo(first.path("id").asString());
            assertThat(outcome.provider()).isEqualTo("primary");
            assertThat(outcome.usage().totalTokens()).isEqualTo(10);
            assertThat(outcome.finishReason()).isEqualTo("stop");
            assertThat(outcome.timeToFirstToken()).isPositive().isLessThanOrEqualTo(outcome.total());
        });
    }

    @Test
    void fallsBackBeforeTheFirstByte() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(503)));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(delta("from backup"), FINISH, "[DONE]"))));

        ResultActions result = chat(BODY)
                .andExpect(status().isOk())
                .andExpect(header().string(ChatController.CALLS_HEADER, "3"));

        List<String> data = dataLines(result);
        assertThat(JSON.readTree(data.get(0)).path("provider").asString()).isEqualTo("backup");
        assertThat(data).last().isEqualTo("[DONE]");
        primary.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        assertThat(observer.outcomes).singleElement()
                .satisfies(outcome -> assertThat(outcome.failedAttempts()).hasSize(2));
    }

    @Test
    void midStreamFailureEndsWithAnErrorEventAndNoDone() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(delta("partial")))));

        ResultActions result = chat(BODY).andExpect(status().isOk());

        List<String> data = dataLines(result);
        assertThat(data).hasSize(2).doesNotContain("[DONE]");
        JsonNode error = JSON.readTree(data.get(1)).path("error");
        assertThat(error.path("type").asString()).isEqualTo("upstream_error");
        assertThat(error.path("code").asString()).isEqualTo("transient");
        assertThat(error.path("provider").asString()).isEqualTo("primary");
        assertThat(data.get(1)).doesNotContain("localhost");
        backup.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        assertThat(observer.outcomes).singleElement()
                .satisfies(outcome -> assertThat(outcome.error()).isEqualTo("stream_interrupted"));
    }

    @Test
    void rejectionBeforeStreamingIsAPlainProblemResponse() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"message\":\"context length exceeded\"}}")));

        chat(BODY)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("context length exceeded")));
        backup.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        assertThat(observer.outcomes).singleElement()
                .satisfies(outcome -> assertThat(outcome.error()).isEqualTo("request_rejected"));
    }

    @Test
    void allTargetsFailingBeforeStreamingIsABadGateway() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(401)));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(404)));

        chat(BODY)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.attempts.length()").value(2));
        assertThat(observer.outcomes).singleElement()
                .satisfies(outcome -> assertThat(outcome.error()).isEqualTo("all_targets_failed"));
    }

    @Test
    void anthropicStreamIsTranslatedIntoTheSameFormat() throws Exception {
        claude.stubFor(WireMock.post("/v1/messages").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        event: message_start
                        data: {"type":"message_start","message":{"usage":{"input_tokens":11,"output_tokens":1}}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":4}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """)));

        List<String> data = dataLines(chat("""
                {"model":"claude","messages":[{"role":"user","content":"hello"}],"stream":true}
                """).andExpect(status().isOk()));

        assertThat(data).hasSize(4).last().isEqualTo("[DONE]");
        assertThat(JSON.readTree(data.get(0)).path("choices").path(0).path("delta").path("content").asString())
                .isEqualTo("Hi");
        assertThat(JSON.readTree(data.get(1)).path("choices").path(0).path("finish_reason").asString())
                .isEqualTo("stop");
        assertThat(JSON.readTree(data.get(2)).path("usage").path("prompt_tokens").asInt()).isEqualTo(11);
        assertThat(JSON.readTree(data.get(2)).path("usage").path("completion_tokens").asInt()).isEqualTo(4);
    }

    @Test
    void plainCallsAreReportedToObserversToo() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(WireMock.okJson("""
                {"choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":2,"completion_tokens":1}}
                """)));

        chat("""
                {"model":"chat","messages":[{"role":"user","content":"hi"}]}
                """).andExpect(status().isOk());

        assertThat(observer.outcomes).singleElement().satisfies(outcome -> {
            assertThat(outcome.streamed()).isFalse();
            assertThat(outcome.usage().totalTokens()).isEqualTo(3);
            assertThat(outcome.timeToFirstToken()).isEqualTo(outcome.total());
        });
    }
}
