package io.github.rmkrdev.gateway.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * One server-sent event of a streamed answer, in the OpenAI {@code chat.completion.chunk}
 * format so existing OpenAI clients can read it. The last chunk before {@code [DONE]} has no
 * choices and carries the usage.
 *
 * @param id gateway call id, the same on every chunk of one answer
 * @param object always {@code chat.completion.chunk}
 * @param created Unix time in seconds when the call started
 * @param model alias the caller asked for
 * @param provider provider id that is serving the call
 * @param providerModel model name on that provider
 * @param choices zero or one choice
 * @param usage token counts, only on the final chunk
 */
public record ChatCompletionChunk(
        String id,
        String object,
        long created,
        String model,
        String provider,
        @JsonProperty("provider_model") String providerModel,
        List<Choice> choices,
        @JsonInclude(JsonInclude.Include.NON_NULL) Usage usage) {

    public static final String OBJECT = "chat.completion.chunk";

    /**
     * @param index always 0
     * @param delta text added by this chunk
     * @param finishReason set on the last content chunk, {@code null} before
     */
    public record Choice(int index, Delta delta, @JsonProperty("finish_reason") String finishReason) {
    }

    /**
     * @param role {@code assistant} on the first chunk only
     * @param content text to append
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Delta(String role, String content) {
    }
}
