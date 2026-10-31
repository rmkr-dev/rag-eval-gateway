package io.github.rmkrdev.gateway.web;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.rmkrdev.entra.security.test.AutoConfigureEntraMockAuthority;
import io.github.rmkrdev.entra.security.test.EntraTestTokens;
import io.github.rmkrdev.gateway.tracking.CallRecord;
import io.github.rmkrdev.gateway.tracking.CallRecorder;
import io.github.rmkrdev.gateway.tracking.CallStore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entra auth end to end: real tokens signed by the starter's mock authority, validated against
 * its JWKS, mapped to roles and checked by {@code @Secured}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "entra.security.tenant-id=11111111-1111-4111-8111-111111111111",
        "entra.security.client-id=22222222-2222-4222-8222-222222222222",
        "gateway.retry.max-attempts=1",
        "gateway.models.chat.targets[0].provider=primary",
        "gateway.models.chat.targets[0].model=gpt-4o-mini",
        "gateway.auth.client-names[" + AuthEndpointTests.BILLING_APP + "]=billing-api"
})
@AutoConfigureMockMvc
@AutoConfigureEntraMockAuthority
class AuthEndpointTests {

    static final String BILLING_APP = "b1b1b1b1-0000-4000-8000-000000000001";
    static final String SEARCH_APP = "5e5e5e5e-0000-4000-8000-000000000002";

    private static final String CHAT_BODY =
            "{\"model\":\"chat\",\"messages\":[{\"role\":\"user\",\"content\":\"What is RAG?\"}]}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @RegisterExtension
    static WireMockExtension primary = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.primary.base-url", () -> primary.baseUrl() + "/v1");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    EntraTestTokens tokens;

    @Autowired
    CallRecorder recorder;

    @Autowired
    CallStore store;

    @BeforeEach
    void upstream() {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson("""
                {"id":"up-1","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":2}}
                """)));
    }

    private String caller(String appId) {
        return tokens.service().appId(appId).appRoles("Gateway.Call").bearer();
    }

    private String reader() {
        return tokens.service().appId(SEARCH_APP).appRoles("Gateway.Reports").bearer();
    }

    private String chat(String bearer, String clientHeader) throws Exception {
        var request = post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY)
                .header(HttpHeaders.AUTHORIZATION, bearer);
        if (clientHeader != null) {
            request.header(ChatController.CLIENT_HEADER, clientHeader);
        }
        MvcResult result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        recorder.flush();
        return JSON.readTree(result.getResponse().getContentAsString()).path("id").asString();
    }

    @Test
    void healthNeedsNoToken() throws Exception {
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void everyApiNeedsAToken() throws Exception {
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/v1/models")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/calls/gw-1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/reports/summary")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/reports/series")).andExpect(status().isUnauthorized());
        mvc.perform(get("/dashboard")).andExpect(status().isUnauthorized());
        primary.verify(0, WireMock.postRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void invalidTokensAreRejected() throws Exception {
        for (String bearer : new String[] {
                tokens.service().appRoles("Gateway.Call").expired().bearer(),
                tokens.service().appRoles("Gateway.Call").audience("api://someone-else").bearer(),
                tokens.service().appRoles("Gateway.Call").signedWithUnknownKey().bearer()}) {
            mvc.perform(get("/v1/models").header(HttpHeaders.AUTHORIZATION, bearer))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aTokenWithoutTheCallerRoleCannotCallModels() throws Exception {
        String noRoles = tokens.service().appId(BILLING_APP).bearer();
        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY)
                        .header(HttpHeaders.AUTHORIZATION, noRoles))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/v1/models").header(HttpHeaders.AUTHORIZATION, noRoles)).andExpect(status().isForbidden());
        primary.verify(0, WireMock.postRequestedFor(WireMock.anyUrl()));
    }

    @Test
    void callersCannotReadReportsOrTheDashboard() throws Exception {
        String bearer = caller(BILLING_APP);
        mvc.perform(get("/v1/reports/summary").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/reports/series").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden());
        mvc.perform(get("/dashboard").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
    }

    @Test
    void readersSeeReportsAndTheDashboard() throws Exception {
        mvc.perform(get("/v1/reports/summary").header(HttpHeaders.AUTHORIZATION, reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.group_by").value("model"));
        mvc.perform(get("/dashboard").header(HttpHeaders.AUTHORIZATION, reader()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("UTC")));
    }

    @Test
    void usersWithTheReportsAppRoleSeeTheDashboard() throws Exception {
        String user = tokens.user().appRoles("Gateway.Reports").bearer();
        mvc.perform(get("/dashboard").header(HttpHeaders.AUTHORIZATION, user)).andExpect(status().isOk());
    }

    @Test
    void theClientComesFromTheTokenNotTheHeader() throws Exception {
        String id = chat(caller(SEARCH_APP), "pretending-to-be-billing");

        CallRecord record = store.find(id).orElseThrow();
        assertThat(record.client()).isEqualTo(SEARCH_APP);
    }

    @Test
    void mappedClientIdsGetTheirReadableName() throws Exception {
        String id = chat(caller(BILLING_APP.toUpperCase()), null);

        assertThat(store.find(id).orElseThrow().client()).isEqualTo("billing-api");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        mvc.perform(get("/v1/reports/summary").param("group_by", "client")
                        .param("from", now.minusSeconds(3600).toString())
                        .param("to", now.plusSeconds(60).toString())
                        .header(HttpHeaders.AUTHORIZATION, reader()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"billing-api\"")));
    }

    @Test
    void callersSeeOnlyTheirOwnCalls() throws Exception {
        String id = chat(caller(BILLING_APP), null);

        mvc.perform(get("/v1/calls/{id}", id).header(HttpHeaders.AUTHORIZATION, caller(BILLING_APP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client").value("billing-api"));
        mvc.perform(get("/v1/calls/{id}", id).header(HttpHeaders.AUTHORIZATION, caller(SEARCH_APP)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/v1/calls/{id}", id).header(HttpHeaders.AUTHORIZATION, reader()))
                .andExpect(status().isOk());
    }

    @Test
    void authTimeIsReported() throws Exception {
        mvc.perform(get("/v1/models").header(HttpHeaders.AUTHORIZATION, caller(BILLING_APP)))
                .andExpect(status().isOk())
                .andExpect(header().string("Server-Timing", containsString("auth;dur=")));
    }
}
