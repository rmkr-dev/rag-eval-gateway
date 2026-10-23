package io.github.rmkrdev.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ApiContractTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void readsSnakeCaseRequest() {
        ChatRequest request = mapper.readValue("""
                {"model":"chat-default","messages":[{"role":"user","content":"hi"}],
                 "max_tokens":128,"stream":true,"metadata":{"feature":"search"}}
                """, ChatRequest.class);
        assertThat(request.maxTokens()).isEqualTo(128);
        assertThat(request.streaming()).isTrue();
        assertThat(request.metadata()).containsEntry("feature", "search");
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void streamDefaultsToFalse() {
        ChatRequest request = new ChatRequest("m", List.of(new ChatMessage("user", "x")), null, null, null, null);
        assertThat(request.streaming()).isFalse();
    }

    @Test
    void rejectsInvalidRequest() {
        ChatRequest request = new ChatRequest(" ", List.of(), 3.0, 0, null, null);
        assertThat(validator.validate(request)).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("model", "messages", "temperature", "maxTokens");
    }

    @Test
    void writesUsageWithTotal() {
        String json = mapper.writeValueAsString(new Usage(10, 5));
        assertThat(json).contains("\"prompt_tokens\":10", "\"completion_tokens\":5", "\"total_tokens\":15");
    }
}
