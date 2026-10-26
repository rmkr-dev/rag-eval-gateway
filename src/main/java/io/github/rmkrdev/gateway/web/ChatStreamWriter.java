package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.api.ChatCompletionChunk;
import io.github.rmkrdev.gateway.api.ChatCompletionChunk.Choice;
import io.github.rmkrdev.gateway.api.ChatCompletionChunk.Delta;
import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.call.CallObservers;
import io.github.rmkrdev.gateway.call.CallOutcome;
import io.github.rmkrdev.gateway.provider.ChatChunk;
import io.github.rmkrdev.gateway.provider.ProviderException;
import io.github.rmkrdev.gateway.routing.RoutedStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a routed stream to the caller as OpenAI-style server-sent events: content chunks, a
 * usage chunk, then {@code data: [DONE]}. If the provider fails after the first chunk the
 * stream ends with a single {@code {"error": ...}} event and no {@code [DONE]}, so clients can
 * tell a cut-off answer from a complete one.
 */
final class ChatStreamWriter {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamWriter.class);
    private static final byte[] DONE = "data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);

    private final ChatController.Call call;
    private final String id;
    private final long created;
    private final String model;
    private final RoutedStream routed;
    private final JsonMapper json;
    private final CallObservers observers;

    ChatStreamWriter(ChatController.Call call, long created, String model, RoutedStream routed, JsonMapper json,
            CallObservers observers) {
        this.call = call;
        this.id = call.id();
        this.created = created;
        this.model = model;
        this.routed = routed;
        this.json = json;
        this.observers = observers;
    }

    /** Drains the stream into {@code out}, always closing the upstream and reporting the outcome. */
    void writeTo(OutputStream out) {
        Usage usage = null;
        String finishReason = null;
        String error = null;
        boolean first = true;
        try (routed) {
            for (ChatChunk chunk = routed.next(); chunk != null; chunk = routed.next()) {
                if (chunk.usage() != null) {
                    usage = chunk.usage();
                }
                boolean hasText = chunk.content() != null && !chunk.content().isEmpty();
                if (hasText || chunk.finishReason() != null) {
                    if (chunk.finishReason() != null) {
                        finishReason = chunk.finishReason();
                    }
                    Delta delta = new Delta(first ? "assistant" : null, hasText ? chunk.content() : null);
                    send(out, chunk(List.of(new Choice(0, delta, chunk.finishReason())), null));
                    first = false;
                }
            }
            if (usage != null) {
                send(out, chunk(List.of(), usage));
            }
            out.write(DONE);
            out.flush();
        } catch (ProviderException e) {
            error = "stream_interrupted";
            log.warn("model={} provider={} target={} stream failed mid-answer: failure={} status={}",
                    model, routed.target().provider(), routed.target().model(), e.failure(), e.status());
            log.debug("provider error detail: {}", e.getMessage());
            sendQuietly(out, Map.of("error", Map.of(
                    "message", "provider stream failed after the answer started",
                    "type", "upstream_error",
                    "code", e.failure().name().toLowerCase(Locale.ROOT),
                    "provider", routed.target().provider())));
        } catch (IOException e) {
            error = "client_disconnected";
            log.debug("caller went away during stream {}: {}", id, e.getMessage());
        } finally {
            observers.completed(new CallOutcome(id, call.startedAt(), call.client(), model,
                    routed.target().provider(), routed.target().model(), true,
                    routed.failedAttempts(), usage == null ? Usage.NONE : usage, finishReason,
                    routed.timeToFirstToken(), routed.elapsed(), error));
        }
    }

    private ChatCompletionChunk chunk(List<Choice> choices, Usage usage) {
        return new ChatCompletionChunk(id, ChatCompletionChunk.OBJECT, created, model, routed.target().provider(),
                routed.target().model(), choices, usage);
    }

    private void send(OutputStream out, Object event) throws IOException {
        out.write("data: ".getBytes(StandardCharsets.UTF_8));
        out.write(json.writeValueAsBytes(event));
        out.write("\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendQuietly(OutputStream out, Object event) {
        try {
            send(out, event);
        } catch (IOException e) {
            log.debug("caller went away before the error event of stream {}", id);
        }
    }
}
