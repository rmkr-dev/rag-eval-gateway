package io.github.rmkrdev.gateway;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "entra.security.enabled=false",
        "gateway.providers.openrouter.base-url=https://openrouter.example/api/v1",
        "gateway.providers.openrouter.api-key=secret-key-value",
        "gateway.providers.local.base-url=http://ollama.local:11434/v1",
        "gateway.models.chat-default.targets[0].provider=openrouter",
        "gateway.models.chat-default.targets[0].model=meta-llama/llama-3.1-70b-instruct",
        "gateway.models.chat-default.targets[1].provider=local",
        "gateway.models.chat-default.targets[1].model=llama3.1",
        "gateway.models.embed.targets[0].provider=local",
        "gateway.models.embed.targets[0].model=nomic-embed-text"
})
@AutoConfigureMockMvc
class GatewayApplicationTests {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void probesAreExposed() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void listsAliasesInFallbackOrderWithoutSecrets() throws Exception {
        mvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("chat-default"))
                .andExpect(jsonPath("$.data[0].providers[0]").value("openrouter"))
                .andExpect(jsonPath("$.data[0].providers[1]").value("local"))
                .andExpect(jsonPath("$.data[1].id").value("embed"))
                .andExpect(content().string(not(containsString("secret-key-value"))))
                .andExpect(content().string(not(containsString("openrouter.example"))));
    }
}
