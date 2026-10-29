package io.github.rmkrdev.gateway.routing;

import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.provider.ChatChunk;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ChatStream;
import io.github.rmkrdev.gateway.provider.ProviderException;
import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import io.github.rmkrdev.gateway.provider.ProviderRegistry;
import io.github.rmkrdev.gateway.provider.ProviderReply;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resolves a model alias to its ordered targets and calls them until one answers.
 *
 * <ul>
 *   <li>Transient failures are retried on the same target with exponential backoff, honouring
 *       {@code Retry-After} up to {@code max-backoff}, then the next target is tried.</li>
 *   <li>Provider failures (bad key, unknown model) move to the next target straight away.</li>
 *   <li>Request failures (the provider rejected the request itself) stop routing and go back
 *       to the caller, except on an alias with {@code fallback-on-bad-request}, where a 400, 413
 *       or 422 moves to the next target like a provider failure. The last target's rejection
 *       still goes back to the caller.</li>
 * </ul>
 */
@Service
public class ChatRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatRouter.class);

    /** Statuses that {@code fallback-on-bad-request} moves to the next target. */
    private static final Set<Integer> FALLBACK_REQUEST_STATUSES = Set.of(400, 413, 422);

    /** Blocks the current thread; replaced in tests. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final GatewayProperties properties;
    private final ProviderRegistry registry;
    private final Backoff backoff;
    private final Sleeper sleeper;

    @Autowired
    public ChatRouter(GatewayProperties properties, ProviderRegistry registry) {
        this(properties, registry, new Backoff(properties.retry()), duration -> Thread.sleep(duration));
    }

    ChatRouter(GatewayProperties properties, ProviderRegistry registry, Backoff backoff, Sleeper sleeper) {
        this.properties = properties;
        this.registry = registry;
        this.backoff = backoff;
        this.sleeper = sleeper;
    }

    public RoutedReply route(ChatRequest request) {
        long started = System.nanoTime();
        Routed<ProviderReply> routed = callTargets(request, (provider, target) -> provider.chat(target.model(), request));
        return new RoutedReply(routed.value(), routed.target(), routed.failures(),
                Duration.ofNanos(System.nanoTime() - started));
    }

    /**
     * Opens a streaming call. Retries and fallback work as for {@link #route}, up to and
     * including the first chunk: a target only wins once it has produced content, so a stream
     * that fails before its first token still falls back. After that the caller owns the
     * stream and any failure ends it.
     */
    public RoutedStream routeStream(ChatRequest request) {
        long started = System.nanoTime();
        Routed<RoutedStream.Opened> routed = callTargets(request, (provider, target) -> open(provider, target, request));
        return new RoutedStream(routed.value(), routed.target(), routed.failures(), started);
    }

    private static RoutedStream.Opened open(ChatProvider provider, GatewayProperties.Target target, ChatRequest request) {
        ChatStream stream = provider.stream(target.model(), request);
        try {
            ChatChunk first = stream.next();
            if (first == null) {
                throw new ProviderException(provider.id(), Failure.PROVIDER, null, "stream ended without content");
            }
            return new RoutedStream.Opened(stream, first, System.nanoTime());
        } catch (RuntimeException e) {
            stream.close();
            throw e;
        }
    }

    private <T> Routed<T> callTargets(ChatRequest request, ProviderCall<T> call) {
        String alias = request.model();
        GatewayProperties.ModelAlias model = properties.models().get(alias);
        if (model == null) {
            throw new UnknownModelException(alias);
        }
        List<Attempt> failures = new ArrayList<>();
        List<GatewayProperties.Target> targets = model.targets();
        for (int index = 0; index < targets.size(); index++) {
            GatewayProperties.Target target = targets.get(index);
            boolean hasNext = index < targets.size() - 1;
            ChatProvider provider = registry.find(target.provider())
                    .orElseThrow(() -> new IllegalStateException("provider not built: " + target.provider()));
            int maxAttempts = properties.retry().maxAttempts();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    return new Routed<>(call.call(provider, target), target, List.copyOf(failures));
                } catch (ProviderException e) {
                    failures.add(new Attempt(target.provider(), target.model(), attempt, e.failure(), e.status()));
                    log.warn("model={} provider={} target={} attempt={} failure={} status={}",
                            alias, target.provider(), target.model(), attempt, e.failure(), e.status());
                    log.debug("provider error detail: {}", e.getMessage());
                    if (e.failure() == Failure.REQUEST) {
                        if (hasNext && fallsBackOn(model, e)) {
                            break;
                        }
                        throw new RequestRejectedException(e.status() == null ? 400 : e.status(),
                                e.getMessage(), failures);
                    }
                    if (e.failure() != Failure.TRANSIENT || attempt == maxAttempts) {
                        break;
                    }
                    pause(backoff.delay(attempt, e.retryAfter()));
                }
            }
        }
        throw new AllTargetsFailedException(alias, failures);
    }

    private static boolean fallsBackOn(GatewayProperties.ModelAlias model, ProviderException e) {
        return model.fallbackOnBadRequest() && e.status() != null
                && FALLBACK_REQUEST_STATUSES.contains(e.status());
    }

    @FunctionalInterface
    private interface ProviderCall<T> {
        T call(ChatProvider provider, GatewayProperties.Target target);
    }

    private record Routed<T>(T value, GatewayProperties.Target target, List<Attempt> failures) {
    }

    private void pause(Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            return;
        }
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry", e);
        }
    }
}
