package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.api.ChatResponse;
import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.auth.CallerIdentity;
import io.github.rmkrdev.gateway.auth.GatewayRoles;
import io.github.rmkrdev.gateway.call.CallObservers;
import io.github.rmkrdev.gateway.call.CallOutcome;
import io.github.rmkrdev.gateway.provider.ProviderReply;
import io.github.rmkrdev.gateway.routing.AllTargetsFailedException;
import io.github.rmkrdev.gateway.routing.Attempt;
import io.github.rmkrdev.gateway.routing.ChatRouter;
import io.github.rmkrdev.gateway.routing.RequestRejectedException;
import io.github.rmkrdev.gateway.routing.RoutedReply;
import io.github.rmkrdev.gateway.routing.RoutedStream;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one chat endpoint callers use, whatever provider ends up serving the call. With
 * {@code "stream": true} the answer comes back as server-sent events; routing finishes, and
 * errors are still plain problem responses, until the first chunk is ready.
 * <p>
 * Needs {@link GatewayRoles#CALLER}. The client recorded for the call comes from the token;
 * {@code X-Gateway-Client} counts only while auth is off.
 */
@RestController
@RequestMapping("/v1/chat/completions")
@Secured(GatewayRoles.CALLER)
class ChatController {

    static final String CALLS_HEADER = "X-Gateway-Calls";
    static final String CLIENT_HEADER = "X-Gateway-Client";

    private final ChatRouter router;
    private final CallObservers observers;
    private final JsonMapper json;
    private final CallerIdentity callers;

    ChatController(ChatRouter router, CallObservers observers, JsonMapper json, CallerIdentity callers) {
        this.router = router;
        this.observers = observers;
        this.json = json;
        this.callers = callers;
    }

    /**
     * Streams are written on the request thread, which is a virtual thread, so a long answer
     * holds no platform thread and needs no async timeout.
     */
    @PostMapping
    ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request,
            @RequestHeader(name = CLIENT_HEADER, required = false) String clientHeader,
            HttpServletResponse response) throws IOException {
        Call call = new Call("gw-" + UUID.randomUUID(), Instant.now(), callers.client(clientHeader), System.nanoTime());
        try {
            if (request.streaming()) {
                stream(call, request, response);
                return null;
            }
            return complete(call, request);
        } catch (AllTargetsFailedException e) {
            failed(call, request, e.attempts(), "all_targets_failed");
            throw e;
        } catch (RequestRejectedException e) {
            failed(call, request, e.attempts(), "request_rejected");
            throw e;
        }
    }

    /** Identity of one call, fixed when it arrives. */
    record Call(String id, Instant startedAt, String client, long startedNanos) {
    }

    private ResponseEntity<ChatResponse> complete(Call call, ChatRequest request) {
        String id = call.id();
        RoutedReply routed = router.route(request);
        ProviderReply reply = routed.reply();
        ChatResponse body = new ChatResponse(
                id,
                request.model(),
                routed.target().provider(),
                routed.target().model(),
                reply.message(),
                reply.finishReason(),
                reply.usage());
        observers.completed(new CallOutcome(id, call.startedAt(), call.client(), request.model(),
                routed.target().provider(), routed.target().model(), false, routed.failedAttempts(), reply.usage(), reply.finishReason(), routed.elapsed(),
                routed.elapsed(), null));
        return ResponseEntity.ok().header(CALLS_HEADER, String.valueOf(routed.calls())).body(body);
    }

    private void stream(Call call, ChatRequest request, HttpServletResponse response) throws IOException {
        long created = Instant.now().getEpochSecond();
        RoutedStream routed = router.routeStream(request);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(CALLS_HEADER, String.valueOf(routed.calls()));
        new ChatStreamWriter(call, created, request.model(), routed, json, observers).writeTo(response.getOutputStream());
    }

    private void failed(Call call, ChatRequest request, List<Attempt> attempts, String error) {
        observers.completed(new CallOutcome(call.id(), call.startedAt(), call.client(), request.model(), null, null,
                request.streaming(), attempts, Usage.NONE, null, null,
                Duration.ofNanos(System.nanoTime() - call.startedNanos()), error));
    }
}
