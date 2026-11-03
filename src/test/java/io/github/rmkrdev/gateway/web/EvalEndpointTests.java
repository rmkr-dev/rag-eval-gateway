package io.github.rmkrdev.gateway.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.closeTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.rmkrdev.gateway.eval.EvalSample;
import io.github.rmkrdev.gateway.eval.EvalSampler;
import io.github.rmkrdev.gateway.eval.EvalStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sampling end to end: what is kept for a plain and a streamed call, with PII redaction and
 * restore on, and the export, score and summary endpoints on top.
 */
@SpringBootTest(properties = {
        "entra.security.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:eval;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gateway.retry.max-attempts=1",
        "gateway.models.rag.targets[0].provider=primary",
        "gateway.models.rag.targets[0].model=gpt-4o-mini",
        "gateway.models.quiet.targets[0].provider=primary",
        "gateway.models.quiet.targets[0].model=gpt-4o-mini",
        "gateway.pii.mode=redact",
        "gateway.pii.restore=true",
        "gateway.eval.enabled=true",
        "gateway.eval.sample-rate=1.0",
        "gateway.eval.models.quiet=0",
        "gateway.eval.tags=test",
        "gateway.eval.flush-interval=50ms"
})
@AutoConfigureMockMvc
class EvalEndpointTests {

    @RegisterExtension
    static WireMockExtension primary = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.primary.base-url", () -> primary.baseUrl() + "/v1");
    }

    static final String EMAIL = "priya.raman@example.com";
    static final String CONTEXT = "Refunds for annual plans are accepted within 30 days. Contact " + EMAIL + ".";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    EvalSampler sampler;

    @Autowired
    EvalStore store;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void plainAnswer() {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":"Within 30 days; write to [EMAIL_1]."},
                 "finish_reason":"stop"}],"usage":{"prompt_tokens":40,"completion_tokens":8}}
                """)));
    }

    private static String body(String model, boolean stream, List<String> context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", stream);
        body.put("messages", List.of(
                Map.of("role", "system", "content", "Answer only from the context."),
                Map.of("role", "user", "content", "I am " + EMAIL + ". What is the refund window?")));
        if (context != null) {
            body.put("eval_context", context);
        }
        return JSON.writeValueAsString(body);
    }

    private MvcResult chat(String model, boolean stream, List<String> context, String client) throws Exception {
        return mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .header(ChatController.CLIENT_HEADER, client).content(body(model, stream, context))).andReturn();
    }

    /** Report ranges end at the current second by default; samples from this second come after that. */
    private static String later() {
        return Instant.now().plusSeconds(60).toString();
    }

    private static String plainId(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).path("id").asString();
    }

    private EvalSample stored(String id) {
        sampler.flush();
        return await().atMost(Duration.ofSeconds(5)).until(() -> store.find(id), Optional::isPresent).orElseThrow();
    }

    @Test
    void plainCallsAreKeptAsTheProviderSawThem() throws Exception {
        MvcResult result = chat("rag", false, List.of(CONTEXT, "Monthly plans are not refundable."), "help-desk");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String id = plainId(result);
        assertThat(JSON.readTree(result.getResponse().getContentAsString()).path("message").path("content").asString())
                .as("the caller still gets the restored answer").contains(EMAIL);
        String sent = primary.findAll(WireMock.postRequestedFor(WireMock.anyUrl())).getLast().getBodyAsString();
        assertThat(sent).doesNotContain("eval_context").doesNotContain("Monthly plans");

        EvalSample sample = stored(id);
        assertThat(sample.client()).isEqualTo("help-desk");
        assertThat(sample.model()).isEqualTo("rag");
        assertThat(sample.provider()).isEqualTo("primary");
        assertThat(sample.providerModel()).isEqualTo("gpt-4o-mini");
        assertThat(sample.streamed()).isFalse();
        assertThat(sample.question()).isEqualTo("I am [EMAIL_1]. What is the refund window?");
        assertThat(sample.context()).containsExactly(
                "Refunds for annual plans are accepted within 30 days. Contact [EMAIL_1].",
                "Monthly plans are not refundable.");
        assertThat(sample.answer()).isEqualTo("Within 30 days; write to [EMAIL_1].");
        assertThat(sample.messages()).hasSize(2);
        assertThat(sample.tags()).containsExactly("test");
        assertThat(sample.piiRedactions()).isEqualTo(1);
        assertThat(sample.completedAt()).isAfterOrEqualTo(sample.startedAt());
        assertThat(jdbc.queryForList("SELECT * FROM eval_sample").toString()).doesNotContain(EMAIL);

        mvc.perform(get("/v1/eval/samples/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("I am [EMAIL_1]. What is the refund window?"))
                .andExpect(jsonPath("$.provider_model").value("gpt-4o-mini"));
    }

    @Test
    void streamedCallsAreKeptOnceTheStreamCompletes() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"choices":[{"index":0,"delta":{"content":"Within 30 days, "},"finish_reason":null}]}

                        data: {"choices":[{"index":0,"delta":{"content":"ask [EMAIL_1]."},"finish_reason":null}]}

                        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """)));

        MvcResult result = chat("rag", true, List.of(CONTEXT), "stream-desk");

        String events = result.getResponse().getContentAsString();
        assertThat(events).contains(EMAIL).endsWith("data: [DONE]\n\n");
        String id = events.lines().filter(l -> l.startsWith("data: {"))
                .map(l -> JSON.readTree(l.substring(6)).path("id").asString()).findFirst().orElseThrow();
        EvalSample sample = stored(id);
        assertThat(sample.streamed()).isTrue();
        assertThat(sample.answer()).isEqualTo("Within 30 days, ask [EMAIL_1].");
        assertThat(sample.context()).singleElement().asString().contains("[EMAIL_1]").doesNotContain(EMAIL);
    }

    @Test
    void unsampledAliasesAndFailedCallsKeepNothing() throws Exception {
        String quiet = plainId(chat("quiet", false, List.of(CONTEXT), "quiet-desk"));
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(500)));
        MvcResult failed = chat("rag", false, List.of(CONTEXT), "quiet-desk");
        assertThat(failed.getResponse().getStatus()).isEqualTo(502);
        sampler.flush();

        assertThat(store.find(quiet)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM eval_sample WHERE client = 'quiet-desk'", Long.class))
                .isZero();
    }

    @Test
    void exportsJsonJsonLinesAndHarnessFixtures() throws Exception {
        String first = plainId(chat("rag", false, List.of(CONTEXT), "export-desk"));
        String second = plainId(chat("rag", false, null, "export-desk"));
        stored(first);
        stored(second);

        mvc.perform(get("/v1/eval/samples").param("client", "export-desk").param("to", later()).param("range", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.samples[0].id").value(first))
                .andExpect(jsonPath("$.samples[1].context").isEmpty());

        MvcResult lines = mvc.perform(get("/v1/eval/samples").param("client", "export-desk").param("to", later()).param("format", "jsonl"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(EvalController.NDJSON))
                .andReturn();
        List<String> rows = lines.getResponse().getContentAsString(StandardCharsets.UTF_8).lines().toList();
        assertThat(rows).hasSize(2);
        assertThat(JSON.readTree(rows.get(0)).path("answer").asString()).isEqualTo("Within 30 days; write to [EMAIL_1].");

        MvcResult zip = mvc.perform(get("/v1/eval/samples").param("client", "export-desk").param("to", later()).param("format", "fixtures"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(EvalController.ZIP))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"eval-fixtures.zip\""))
                .andReturn();
        List<String> entries = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.getResponse().getContentAsByteArray()))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.add(e.getName());
            }
        }
        assertThat(entries).contains(first + "-faithfulness/expected.txt", first + "-relevance/meta.json",
                second + "-relevance/actual.txt").noneMatch(name -> name.startsWith(second + "-faithfulness"));

        mvc.perform(get("/v1/eval/samples").param("format", "csv"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid eval request"));
        mvc.perform(get("/v1/eval/samples").param("limit", "10001")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/eval/samples").param("range", "90d")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/eval/samples/{id}", "gw-missing")).andExpect(status().isNotFound());
    }

    @Test
    void scoresFromTheHarnessAndDirectlyShowUpInTheSummary() throws Exception {
        String first = plainId(chat("rag", false, List.of(CONTEXT), "score-desk"));
        String second = plainId(chat("rag", false, List.of(CONTEXT), "score-desk"));
        stored(first);
        stored(second);

        String report = """
                {"harness":"llm-eval-harness","version":"1.0.0","summary":{"total":3},"cases":[
                  {"id":"%1$s-faithfulness","path":"%1$s-faithfulness","pass":true,"skipped":false,
                   "metrics":[{"name":"token_overlap","pass":true,"score":0.8}]},
                  {"id":"%1$s-relevance","path":"%1$s-relevance","pass":true,"skipped":false,
                   "metrics":[{"name":"token_overlap","pass":true,"score":0.4}]},
                  {"id":"gw-gone-relevance","path":"gw-gone-relevance","pass":true,"skipped":false,
                   "metrics":[{"name":"token_overlap","pass":true,"score":0.4}]}
                ]}
                """.formatted(first);
        mvc.perform(post("/v1/eval/scores").contentType(MediaType.APPLICATION_JSON).content(report))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.unknown[0]").value("gw-gone"));

        mvc.perform(post("/v1/eval/samples/{id}/scores", second).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"faithfulness\":0.4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scores.faithfulness").value(0.4))
                .andExpect(jsonPath("$.scores.relevance").doesNotExist());
        mvc.perform(post("/v1/eval/samples/{id}/scores", second).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"relevance\":1.0}"))
                .andExpect(jsonPath("$.scores.faithfulness").value(0.4))
                .andExpect(jsonPath("$.scores.relevance").value(1.0));

        mvc.perform(get("/v1/eval/summary").param("group_by", "client").param("client", "score-desk").param("to", later()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].client").value("score-desk"))
                .andExpect(jsonPath("$.rows[0].samples").value(2))
                .andExpect(jsonPath("$.rows[0].faithfulness_scored").value(2))
                .andExpect(jsonPath("$.rows[0].faithfulness_avg", closeTo(0.6, 1e-9)))
                .andExpect(jsonPath("$.rows[0].relevance_avg", closeTo(0.7, 1e-9)));

        mvc.perform(post("/v1/eval/samples/{id}/scores", second).contentType(MediaType.APPLICATION_JSON)
                .content("{\"faithfulness\":1.5}")).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/eval/samples/{id}/scores", second).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/v1/eval/samples/{id}/scores", "gw-missing").contentType(MediaType.APPLICATION_JSON)
                .content("{\"relevance\":0.5}")).andExpect(status().isNotFound());
        mvc.perform(post("/v1/eval/scores").contentType(MediaType.APPLICATION_JSON).content("{\"summary\":{}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/eval/summary").param("group_by", "provider")).andExpect(status().isBadRequest());
    }

    @Test
    void samplingCountsAreKept() throws Exception {
        long before = sampler.sampled();
        chat("rag", false, null, "count-desk");
        assertThat(sampler.sampled()).isEqualTo(before + 1);
        assertThat(sampler.dropped()).isZero();
    }
}
