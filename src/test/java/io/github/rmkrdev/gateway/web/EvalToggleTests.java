package io.github.rmkrdev.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.rmkrdev.gateway.eval.EvalSampler;
import io.github.rmkrdev.gateway.eval.EvalStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/** Eval hooks are off unless switched on: nothing is sampled, stored or served. */
@SpringBootTest(properties = "entra.security.enabled=false")
@AutoConfigureMockMvc
class EvalToggleTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    EvalSampler sampler;

    @Test
    void offByDefault() throws Exception {
        assertThat(sampler.enabled()).isFalse();
        assertThat(sampler.shouldSample("any", "chat")).isFalse();
        assertThat(context.getBeanNamesForType(EvalStore.class)).isEmpty();
        mvc.perform(get("/v1/eval/samples")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/eval/summary")).andExpect(status().isNotFound());
    }
}
