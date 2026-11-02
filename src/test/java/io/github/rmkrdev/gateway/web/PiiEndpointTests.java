package io.github.rmkrdev.gateway.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.rmkrdev.gateway.tracking.CallRecord;
import io.github.rmkrdev.gateway.tracking.CallRecorder;
import io.github.rmkrdev.gateway.tracking.CallStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * The whole path with personal data in the prompt: what providers receive, what callers get
 * back, and what ends up in the database and the logs.
 */
@SpringBootTest(properties = {
        "entra.security.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:pii;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "logging.level.io.github.rmkrdev.gateway=DEBUG",
        "gateway.retry.max-attempts=1",
        "gateway.models.chat.targets[0].provider=primary",
        "gateway.models.chat.targets[0].model=gpt-4o-mini",
        "gateway.models.masked.targets[0].provider=primary",
        "gateway.models.masked.targets[0].model=gpt-4o-mini",
        "gateway.models.plain.targets[0].provider=primary",
        "gateway.models.plain.targets[0].model=gpt-4o-mini",
        "gateway.models.anthropic-chat.targets[0].provider=anthropic",
        "gateway.models.anthropic-chat.targets[0].model=sonnet-4-5",
        "gateway.providers.anthropic.type=anthropic",
        "gateway.pii.mode=redact",
        "gateway.pii.models.masked=mask",
        "gateway.pii.models.plain=off",
        "gateway.pii.clients.strict-app=block"
})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PiiEndpointTests {

    @RegisterExtension
    static WireMockExtension primary = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension anthropic = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.primary.base-url", () -> primary.baseUrl() + "/v1");
        registry.add("gateway.providers.anthropic.base-url", anthropic::baseUrl);
    }

    static final String EMAIL = "priya.raman@example.com";
    static final String CARD = "4111 1111 1111 1111";
    static final String PHONE = "+91 98765 43210";
    static final String AADHAAR = "2345 6789 0124";
    static final List<String> RAW = List.of(EMAIL, CARD, PHONE, AADHAAR, "4111111111111111", "9876543210");

    static final String PROMPT = "Customer " + EMAIL + " (phone " + PHONE + ", aadhaar " + AADHAAR
            + ") disputes a charge on card " + CARD + ". Draft a reply to " + EMAIL + ".";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    CallRecorder recorder;

    @Autowired
    CallStore store;

    @Autowired
    JdbcTemplate jdbc;

    private static String body(String model, boolean stream) {
        return JSON.writeValueAsString(java.util.Map.of(
                "model", model,
                "stream", stream,
                "messages", List.of(
                        java.util.Map.of("role", "system", "content", "You help the support team."),
                        java.util.Map.of("role", "user", "content", PROMPT))));
    }

    private MvcResult chat(String model, boolean stream, String client) throws Exception {
        var request = post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body(model, stream));
        if (client != null) {
            request.header(ChatController.CLIENT_HEADER, client);
        }
        MvcResult result = mvc.perform(request).andReturn();
        recorder.flush();
        return result;
    }

    private static String completion(String text) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":40,"completion_tokens":8}}
                """.formatted(text);
    }

    private static void assertNoRawValues(String where, String text) {
        for (String raw : RAW) {
            assertThat(text).as(where).doesNotContain(raw);
        }
    }

    private static String sentTo(WireMockExtension provider) {
        List<LoggedRequest> requests = provider.findAll(WireMock.postRequestedFor(WireMock.anyUrl()));
        assertThat(requests).hasSize(1);
        return requests.get(0).getBodyAsString();
    }

    private void assertDatabaseHoldsNoRawValues() {
        assertNoRawValues("gateway_call rows", jdbc.queryForList("SELECT * FROM gateway_call").toString());
    }

    @Test
    void providerSeesPlaceholdersAndTheCallStoresOnlyCounts(CapturedOutput output) throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("Drafted for [EMAIL_1]."))));

        MvcResult result = chat("chat", false, "billing-api");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("X-Gateway-Pii")).isEqualTo("credit-card=1,in-aadhaar=1,email=2,phone=1");
        String sent = sentTo(primary);
        assertNoRawValues("provider request", sent);
        assertThat(sent).contains("Customer [EMAIL_1] (phone [PHONE_1], aadhaar [AADHAAR_1])",
                "card [CARD_1]", "reply to [EMAIL_1].", "You help the support team.");
        String id = JSON.readTree(result.getResponse().getContentAsString()).path("id").asString();
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("message").path("content").asString())
                .isEqualTo("Drafted for [EMAIL_1].");

        CallRecord record = store.find(id).orElseThrow();
        assertThat(record.piiRedactions()).isEqualTo(5);
        assertThat(record.status()).isEqualTo(CallRecord.OK);
        mvc.perform(get("/v1/calls/{id}", id)).andExpect(jsonPath("$.pii_redactions").value(5));
        assertDatabaseHoldsNoRawValues();
        assertNoRawValues("log output", output.getAll());
    }

    @Test
    void streamingCallsToNativeProvidersAreRedactedToo(CapturedOutput output) throws Exception {
        anthropic.stubFor(WireMock.post("/v1/messages").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        event: message_start
                        data: {"type":"message_start","message":{"usage":{"input_tokens":30,"output_tokens":1}}}

                        event: content_block_delta
                        data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Done"}}

                        event: message_delta
                        data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

                        event: message_stop
                        data: {"type":"message_stop"}

                        """)));

        MvcResult result = chat("anthropic-chat", true, "billing-api");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(result.getResponse().getHeader("X-Gateway-Pii")).isEqualTo("credit-card=1,in-aadhaar=1,email=2,phone=1");
        assertThat(result.getResponse().getContentAsString()).contains("Done").endsWith("data: [DONE]\n\n");
        String sent = sentTo(anthropic);
        assertNoRawValues("provider request", sent);
        assertThat(sent).contains("[EMAIL_1]", "[CARD_1]");
        String id = result.getResponse().getContentAsString().lines().filter(l -> l.startsWith("data: {"))
                .map(l -> JSON.readTree(l.substring(6)).path("id").asString()).findFirst().orElseThrow();
        CallRecord record = store.find(id).orElseThrow();
        assertThat(record.streamed()).isTrue();
        assertThat(record.piiRedactions()).isEqualTo(5);
        assertDatabaseHoldsNoRawValues();
        assertNoRawValues("log output", output.getAll());
    }

    @Test
    void maskModeForAModelAlias() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("ok"))));

        assertThat(chat("masked", false, null).getResponse().getStatus()).isEqualTo(200);

        String sent = sentTo(primary);
        assertNoRawValues("provider request", sent);
        assertThat(sent).contains("card **** **** **** 1111", "aadhaar **** **** 0124", "p***@example.com");
    }

    @Test
    void blockedClientsAreRefusedBeforeAnyProvider(CapturedOutput output) throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("ok"))));

        for (boolean stream : new boolean[] {false, true}) {
            mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                            .header(ChatController.CLIENT_HEADER, "Strict-App").content(body("chat", stream)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                    .andExpect(jsonPath("$.code").value("pii_detected"))
                    .andExpect(jsonPath("$.title").value("Personal data not allowed"))
                    .andExpect(jsonPath("$.detected.email").value(2))
                    .andExpect(jsonPath("$.detected.credit-card").value(1))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString(EMAIL))));
        }
        recorder.flush();

        assertThat(primary.findAll(WireMock.postRequestedFor(WireMock.anyUrl()))).isEmpty();
        List<CallRecord> blocked = store.recent(50).stream().filter(r -> "pii_blocked".equals(r.error())).toList();
        assertThat(blocked).hasSizeGreaterThanOrEqualTo(2).allSatisfy(r -> {
            assertThat(r.client()).isEqualTo("Strict-App");
            assertThat(r.piiRedactions()).isEqualTo(5);
            assertThat(r.provider()).isNull();
        });
        assertDatabaseHoldsNoRawValues();
        assertNoRawValues("log output", output.getAll());
    }

    @Test
    void providerErrorsEchoingThePromptAreScrubbedInLogs(CapturedOutput output) throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"Unsupported content near " + EMAIL + " and " + CARD + "\"}}")));

        MvcResult result = chat("plain", false, null);

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(sentTo(primary)).contains(EMAIL);
        assertThat(output.getAll()).contains("provider error detail").contains("[EMAIL]", "[CARD]");
        assertNoRawValues("log output", output.getAll());
        assertDatabaseHoldsNoRawValues();
    }

    @Test
    void cleanPromptsGetNoHeader() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("ok"))));

        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"chat\",\"messages\":[{\"role\":\"user\",\"content\":\"What is RAG?\"}]}"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Gateway-Pii"));
        assertThat(sentTo(primary)).contains("What is RAG?");
    }
}
