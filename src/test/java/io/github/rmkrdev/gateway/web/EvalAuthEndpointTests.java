package io.github.rmkrdev.gateway.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.rmkrdev.entra.security.test.AutoConfigureEntraMockAuthority;
import io.github.rmkrdev.entra.security.test.EntraTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The eval endpoints hold prompts and answers, so they need the reports role like the reports API. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evalauth;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "entra.security.tenant-id=11111111-1111-4111-8111-111111111111",
        "entra.security.client-id=22222222-2222-4222-8222-222222222222",
        "gateway.eval.enabled=true"
})
@AutoConfigureMockMvc
@AutoConfigureEntraMockAuthority
class EvalAuthEndpointTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    EntraTestTokens tokens;

    @Test
    void onlyTheReportsRoleMayReadOrScoreSamples() throws Exception {
        String caller = tokens.service().appRoles("Gateway.Call").bearer();
        String reader = tokens.service().appRoles("Gateway.Reports").bearer();

        mvc.perform(get("/v1/eval/samples")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/eval/samples").header(HttpHeaders.AUTHORIZATION, caller)).andExpect(status().isForbidden());
        mvc.perform(post("/v1/eval/scores").header(HttpHeaders.AUTHORIZATION, caller)
                .contentType(MediaType.APPLICATION_JSON).content("{\"cases\":[]}")).andExpect(status().isForbidden());

        mvc.perform(get("/v1/eval/samples").header(HttpHeaders.AUTHORIZATION, reader)).andExpect(status().isOk());
        mvc.perform(get("/v1/eval/summary").header(HttpHeaders.AUTHORIZATION, reader)).andExpect(status().isOk());
        mvc.perform(post("/v1/eval/scores").header(HttpHeaders.AUTHORIZATION, reader)
                .contentType(MediaType.APPLICATION_JSON).content("{\"cases\":[]}")).andExpect(status().isOk());
    }
}
