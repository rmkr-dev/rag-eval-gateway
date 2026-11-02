package io.github.rmkrdev.gateway.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** With {@code restore} on, callers get their own values back while providers never see them. */
@SpringBootTest(properties = {
        "entra.security.enabled=false",
        "gateway.retry.max-attempts=1",
        "gateway.models.chat.targets[0].provider=primary",
        "gateway.models.chat.targets[0].model=gpt-4o-mini",
        "gateway.pii.mode=redact",
        "gateway.pii.restore=true"
})
@AutoConfigureMockMvc
class PiiRestoreEndpointTests {

    @RegisterExtension
    static WireMockExtension primary = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.primary.base-url", () -> primary.baseUrl() + "/v1");
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String EMAIL = "priya.raman@example.com";

    @Autowired
    MockMvc mvc;

    private static String body(boolean stream) {
        return "{\"model\":\"chat\",\"stream\":" + stream
                + ",\"messages\":[{\"role\":\"user\",\"content\":\"Write to " + EMAIL + " and 10.20.30.40\"}]}";
    }

    private static String delta(String text) {
        return "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + text + "\"},\"finish_reason\":null}]}\n\n";
    }

    @Test
    void plainAnswersGetTheValuesBack() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":"Sent to [EMAIL_1] from [IP_1], not [EMAIL_2]."},
                 "finish_reason":"stop"}],"usage":{"prompt_tokens":9,"completion_tokens":5}}
                """)));

        mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.content").value("Sent to " + EMAIL + " from 10.20.30.40, not [EMAIL_2]."));

        String sent = primary.findAll(WireMock.postRequestedFor(WireMock.anyUrl())).get(0).getBodyAsString();
        assertThat(sent).contains("Write to [EMAIL_1] and [IP_1]").doesNotContain(EMAIL, "10.20.30.40");
    }

    @Test
    void streamedAnswersRestorePlaceholdersSplitAcrossChunks() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(delta("Sent to [EMA") + delta("IL_1") + delta("] from [IP_") + delta("1] [x")
                        + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n")));

        String body = mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body(true)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        StringBuilder text = new StringBuilder();
        String finish = null;
        for (String line : body.split("\n\n")) {
            if (line.startsWith("data: {")) {
                JsonNode choice = JSON.readTree(line.substring(6)).path("choices").path(0);
                if (choice.path("delta").path("content").isString()) {
                    String part = choice.path("delta").path("content").asString();
                    assertThat(part).doesNotContain("[EMA", "IL_1]");
                    text.append(part);
                }
                if (choice.path("finish_reason").isString()) {
                    finish = choice.path("finish_reason").asString();
                }
            }
        }
        assertThat(text).hasToString("Sent to " + EMAIL + " from 10.20.30.40 [x");
        assertThat(finish).isEqualTo("stop");
        assertThat(body).endsWith("data: [DONE]\n\n");
    }
}
