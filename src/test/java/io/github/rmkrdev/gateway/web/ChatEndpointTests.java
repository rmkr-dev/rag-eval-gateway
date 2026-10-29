package io.github.rmkrdev.gateway.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "gateway.retry.max-attempts=2",
        "gateway.retry.initial-backoff=10ms",
        "gateway.retry.max-backoff=20ms",
        "gateway.models.chat.targets[0].provider=primary",
        "gateway.models.chat.targets[0].model=gpt-4o-mini",
        "gateway.models.chat.targets[1].provider=backup",
        "gateway.models.chat.targets[1].model=llama3.1",
        "gateway.models.lenient.targets[0].provider=primary",
        "gateway.models.lenient.targets[0].model=gpt-4o-mini",
        "gateway.models.lenient.targets[1].provider=backup",
        "gateway.models.lenient.targets[1].model=llama3.1",
        "gateway.models.lenient.fallback-on-bad-request=true"
})
@AutoConfigureMockMvc
class ChatEndpointTests {

    @RegisterExtension
    static WireMockExtension primary = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @RegisterExtension
    static WireMockExtension backup = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.primary.base-url", () -> primary.baseUrl() + "/v1");
        registry.add("gateway.providers.primary.api-key", () -> "primary-secret");
        registry.add("gateway.providers.backup.base-url", () -> backup.baseUrl() + "/v1");
    }

    private static final String BODY = """
            {"model":"chat","messages":[{"role":"user","content":"What is RAG?"}],"max_tokens":50}
            """;

    private static String completion(String text) {
        return """
                {"choices":[{"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":7,"completion_tokens":4}}
                """.formatted(text);
    }

    @Autowired
    MockMvc mvc;

    private ResultActions chat(String body) throws Exception {
        return mvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void answersFromFirstTarget() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("Retrieval augmented generation"))));

        chat(BODY)
                .andExpect(status().isOk())
                .andExpect(header().string(ChatController.CALLS_HEADER, "1"))
                .andExpect(jsonPath("$.id").value(startsWith("gw-")))
                .andExpect(jsonPath("$.model").value("chat"))
                .andExpect(jsonPath("$.provider").value("primary"))
                .andExpect(jsonPath("$.provider_model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.message.content").value("Retrieval augmented generation"))
                .andExpect(jsonPath("$.finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.total_tokens").value(11));
        primary.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer primary-secret"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-4o-mini"))));
    }

    @Test
    void fallsBackToSecondProviderAfterRetries() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(503)));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("from backup"))));

        chat(BODY)
                .andExpect(status().isOk())
                .andExpect(header().string(ChatController.CALLS_HEADER, "3"))
                .andExpect(jsonPath("$.provider").value("backup"))
                .andExpect(jsonPath("$.provider_model").value("llama3.1"))
                .andExpect(jsonPath("$.message.content").value("from backup"));
        primary.verify(2, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void returnsBadGatewayWithAttemptsButNoSecretsWhenAllFail() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(401)));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(500)));

        chat(BODY)
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.attempts.length()").value(3))
                .andExpect(jsonPath("$.attempts[0].provider").value("primary"))
                .andExpect(jsonPath("$.attempts[0].status").value(401))
                .andExpect(jsonPath("$.attempts[0].failure").value("PROVIDER"))
                .andExpect(jsonPath("$.attempts[2].provider").value("backup"))
                .andExpect(jsonPath("$.attempts[2].attempt").value(2))
                .andExpect(content().string(not(containsString("primary-secret"))))
                .andExpect(content().string(not(containsString("localhost"))));
    }

    @Test
    void passesProviderRejectionBackWithoutFallback() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"message\":\"context length exceeded\"}}")));

        chat(BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("context length exceeded")));
        backup.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void badRequestFallsBackWhenTheAliasAllowsIt() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"message\":\"unsupported parameter: max_tokens\"}}")));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("from backup"))));

        chat(BODY.replace("\"chat\"", "\"lenient\""))
                .andExpect(status().isOk())
                .andExpect(header().string(ChatController.CALLS_HEADER, "2"))
                .andExpect(jsonPath("$.provider").value("backup"))
                .andExpect(jsonPath("$.message.content").value("from backup"));
        primary.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void badRequestFromTheLastTargetStillReachesTheCaller() throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(400)));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(400)
                .withBody("{\"error\":{\"message\":\"context length exceeded\"}}")));

        chat(BODY.replace("\"chat\"", "\"lenient\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("context length exceeded")));
        primary.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        backup.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @ParameterizedTest
    @ValueSource(ints = {413, 422})
    void tooLargeAndUnprocessableGoBackWithoutFallbackByDefault(int status) throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(status)
                .withBody("{\"error\":{\"message\":\"rejected by primary\"}}")));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("never"))));

        chat(BODY)
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.detail").value(containsString("rejected by primary")));
        primary.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        backup.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @ParameterizedTest
    @ValueSource(ints = {413, 422})
    void tooLargeAndUnprocessableFallBackWhenTheAliasAllowsIt(int status) throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(status)
                .withBody("{\"error\":{\"message\":\"request too large for this model\"}}")));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(okJson(completion("from backup"))));

        chat(BODY.replace("\"chat\"", "\"lenient\""))
                .andExpect(status().isOk())
                .andExpect(header().string(ChatController.CALLS_HEADER, "2"))
                .andExpect(jsonPath("$.provider").value("backup"))
                .andExpect(jsonPath("$.message.content").value("from backup"));
        primary.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @ParameterizedTest
    @ValueSource(ints = {413, 422})
    void tooLargeAndUnprocessableFromTheLastTargetReachTheCaller(int status) throws Exception {
        primary.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(400)));
        backup.stubFor(WireMock.post("/v1/chat/completions").willReturn(aResponse().withStatus(status)
                .withBody("{\"error\":{\"message\":\"rejected by backup\"}}")));

        chat(BODY.replace("\"chat\"", "\"lenient\""))
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("rejected by backup")))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[1].status").value(status));
        primary.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
        backup.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void unknownModelIsNotFound() throws Exception {
        chat("""
                {"model":"missing","messages":[{"role":"user","content":"hi"}]}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("unknown model 'missing'"));
    }

    @Test
    void invalidRequestIsRejectedBeforeAnyProviderCall() throws Exception {
        chat("""
                {"model":"chat","messages":[]}
                """)
                .andExpect(status().isBadRequest());
        primary.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }
}
