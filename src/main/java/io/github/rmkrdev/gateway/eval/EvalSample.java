package io.github.rmkrdev.gateway.eval;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.rmkrdev.gateway.api.ChatMessage;
import java.time.Instant;
import java.util.List;

/**
 * One sampled call as the provider saw it: the prompt after PII redaction, never the original
 * values, the retrieved context the caller passed, and the answer before any placeholder was
 * restored. This is also the line format of {@code format=jsonl} exports and the {@code jsonl}
 * file sink.
 *
 * @param id gateway call id
 * @param startedAt when the gateway received the call
 * @param completedAt when the answer was complete
 * @param client caller identity
 * @param model alias the caller asked for
 * @param provider provider that answered
 * @param providerModel model name on that provider
 * @param streamed whether the answer was streamed
 * @param question the last user message
 * @param context retrieved passages from {@code eval_context}; empty when the caller sent none
 * @param answer the answer as the provider gave it
 * @param messages every prompt message as sent to the provider
 * @param tags tags from {@code gateway.eval.tags}
 * @param piiRedactions personal data values replaced in the prompt
 * @param scores scores attached later, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EvalSample(
        String id,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt,
        String client,
        String model,
        String provider,
        @JsonProperty("provider_model") String providerModel,
        boolean streamed,
        String question,
        List<String> context,
        String answer,
        List<ChatMessage> messages,
        List<String> tags,
        @JsonProperty("pii_redactions") int piiRedactions,
        Scores scores) {

    /**
     * Scores from 0 to 1, each {@code null} until attached.
     *
     * @param faithfulness how well the answer sticks to the context
     * @param relevance how well the answer addresses the question
     * @param scoredAt when the last score was attached
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Scores(Double faithfulness, Double relevance, @JsonProperty("scored_at") Instant scoredAt) {
    }

    /** The last user message, or the last message when no user message exists. */
    static String question(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return messages.isEmpty() ? "" : messages.getLast().content();
    }
}
