package io.github.rmkrdev.gateway.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.rmkrdev.gateway.tracking.CallRecord;
import io.github.rmkrdev.gateway.tracking.CallRecorder;
import io.github.rmkrdev.gateway.tracking.CallStore;
import io.github.rmkrdev.gateway.tracking.ClientSpend;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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

/** Budgets and rate limits end to end: refused calls never reach the provider. */
@SpringBootTest(properties = {
        "entra.security.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:limits;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "gateway.retry.max-attempts=1",
        "gateway.models.chat.targets[0].provider=primary",
        "gateway.models.chat.targets[0].model=gpt-4o-mini",
        "gateway.pricing.prices[0].provider=primary",
        "gateway.pricing.prices[0].model=gpt-4o-mini",
        "gateway.pricing.prices[0].input=1.00",
        "gateway.pricing.prices[0].output=2.00",
        "gateway.budgets.default.daily-tokens=1000000",
        "gateway.budgets.clients.capped.daily-tokens=100",
        "gateway.budgets.clients.warned.daily-tokens=1000",
        "gateway.budgets.clients.restarted.daily-cost=0.01",
        "gateway.rate-limits.clients.chatty.requests-per-minute=2",
        "gateway.rate-limits.clients.streamer.tokens-per-minute=100"
})
@AutoConfigureMockMvc
class LimitsEndpointTests {

    @RegisterExtension
    static WireMockExtension primary = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.primary.base-url", () -> primary.baseUrl() + "/v1");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    CallRecorder recorder;

    @Autowired
    CallStore store;

    @BeforeEach
    void answer() {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson("""
                {"id":"up-1","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":120,"completion_tokens":30}}
                """)));
    }

    private ResultActions chat(String client, boolean stream) throws Exception {
        return mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                .header(ChatController.CLIENT_HEADER, client)
                .content("{\"model\":\"chat\",\"stream\":" + stream
                        + ",\"messages\":[{\"role\":\"user\",\"content\":\"What is RAG?\"}]}"));
    }

    @Test
    void overTheRequestRateIsRefusedWithRetryAfter() throws Exception {
        chat("chatty", false).andExpect(status().isOk());
        chat("chatty", false).andExpect(status().isOk());

        chat("chatty", false)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Retry-After", matchesRegex("2[5-9]|30")))
                .andExpect(jsonPath("$.title").value("Rate limit reached"))
                .andExpect(jsonPath("$.code").value("rate_limited"))
                .andExpect(jsonPath("$.limit").value("requests-per-minute"))
                .andExpect(jsonPath("$.client").value("chatty"))
                .andExpect(jsonPath("$.resets_at").doesNotExist());

        primary.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        chat("someone-else", false).andExpect(status().isOk());
    }

    @Test
    void overBudgetIsRefusedBeforeTheProviderStreamingOrNot() throws Exception {
        chat("capped", false).andExpect(status().isOk());

        chat("capped", false)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.title").value("Budget used up"))
                .andExpect(jsonPath("$.code").value("budget_exceeded"))
                .andExpect(jsonPath("$.limit").value("daily-tokens"))
                .andExpect(jsonPath("$.detail").value("Client 'capped' has used its daily-tokens budget of 100 tokens"))
                .andExpect(jsonPath("$.resets_at").value(startsWith(
                        Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS).toString().substring(0, 10))));
        chat("capped", true)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("budget_exceeded"));

        primary.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void nearTheBudgetAnswersCarryAWarning() throws Exception {
        // 150 tokens a call against 1,000 a day, warning from 80%
        for (int i = 0; i < 6; i++) {
            chat("warned", false).andExpect(status().isOk()).andExpect(header().doesNotExist("X-Gateway-Budget-Warning"));
        }
        chat("warned", false).andExpect(status().isOk()).andExpect(header().string("X-Gateway-Budget-Warning",
                "daily-tokens=90%"));
        chat("warned", false).andExpect(status().isTooManyRequests());
    }

    @Test
    void streamedUsageCountsTowardsTokensPerMinute() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody("""
                        data: {"choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":"stop"}]}

                        data: {"choices":[],"usage":{"prompt_tokens":90,"completion_tokens":20}}

                        data: [DONE]

                        """)));

        chat("streamer", true).andExpect(status().isOk());

        chat("streamer", true)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.limit").value("tokens-per-minute"));
    }

    @Test
    void budgetsCountCallsStoredBeforeTheGatewayStarted() throws Exception {
        store.insertAll(List.of(new CallRecord("gw-limits-earlier", Instant.now().minusSeconds(1), "restarted", "chat",
                "primary", "gpt-4o-mini", false, CallRecord.OK, null, "stop", 4_000, 3_000, null, null,
                new BigDecimal("0.01"), "USD", null, 100L, 900L, 0)));

        chat("restarted", false)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.limit").value("daily-cost"))
                .andExpect(jsonPath("$.detail").value("Client 'restarted' has used its daily-cost budget of 0.01 USD"));
    }

    @Test
    void storeSumsTokensAndCostPerClientFromAStart() {
        Instant now = Instant.now();
        store.insertAll(List.of(
                new CallRecord("gw-sum-1", now, "sum-a", "chat", "primary", "m", false, CallRecord.OK, null, null,
                        100, 50, null, null, new BigDecimal("0.25"), "USD", null, 1L, 1L, 0),
                new CallRecord("gw-sum-2", now, "sum-a", "chat", "primary", "m", false, CallRecord.OK, null, null,
                        10, 5, null, null, null, null, null, 1L, 1L, 0),
                new CallRecord("gw-sum-3", now.minus(2, ChronoUnit.DAYS), "sum-a", "chat", "primary", "m", false,
                        CallRecord.OK, null, null, 999, 0, null, null, BigDecimal.ONE, "USD", null, 1L, 1L, 0),
                new CallRecord("gw-sum-4", now, "sum-b", "chat", null, null, false, CallRecord.ERROR,
                        "all_targets_failed", null, 0, 0, null, null, null, null, null, null, 1L, 2)));

        Map<String, ClientSpend> spend = store.spendByClientSince(now.minus(1, ChronoUnit.HOURS));

        assertThat(spend.get("sum-a").tokens()).isEqualTo(165);
        assertThat(spend.get("sum-a").cost()).isEqualByComparingTo("0.25");
        assertThat(spend.get("sum-b")).isEqualTo(new ClientSpend(0, BigDecimal.ZERO));
    }
}
