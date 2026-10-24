package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.api.ChatResponse;
import io.github.rmkrdev.gateway.provider.ProviderReply;
import io.github.rmkrdev.gateway.routing.ChatRouter;
import io.github.rmkrdev.gateway.routing.RoutedReply;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The one chat endpoint callers use, whatever provider ends up serving the call. */
@RestController
@RequestMapping("/v1/chat/completions")
class ChatController {

    static final String CALLS_HEADER = "X-Gateway-Calls";

    private final ChatRouter router;

    ChatController(ChatRouter router) {
        this.router = router;
    }

    @PostMapping
    ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        if (request.streaming()) {
            throw new StreamingNotSupportedException();
        }
        RoutedReply routed = router.route(request);
        ProviderReply reply = routed.reply();
        ChatResponse body = new ChatResponse(
                "gw-" + UUID.randomUUID(),
                request.model(),
                routed.target().provider(),
                routed.target().model(),
                reply.message(),
                reply.finishReason(),
                reply.usage());
        return ResponseEntity.ok().header(CALLS_HEADER, String.valueOf(routed.calls())).body(body);
    }

    static class StreamingNotSupportedException extends RuntimeException {
        StreamingNotSupportedException() {
            super("streaming is not available yet; send stream=false");
        }
    }
}
