package io.github.rmkrdev.gateway.routing;

import io.github.rmkrdev.gateway.api.ChatRequest;
import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ProviderException;
import io.github.rmkrdev.gateway.provider.ProviderException.Failure;
import io.github.rmkrdev.gateway.provider.ProviderRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 *       to the caller.</li>
 * </ul>
 */
@Service
public class ChatRouter {

    private static final Logger log = LoggerFactory.getLogger(ChatRouter.class);

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
        String alias = request.model();
        GatewayProperties.ModelAlias model = properties.models().get(alias);
        if (model == null) {
            throw new UnknownModelException(alias);
        }
        List<Attempt> failures = new ArrayList<>();
        for (GatewayProperties.Target target : model.targets()) {
            ChatProvider provider = registry.find(target.provider())
                    .orElseThrow(() -> new IllegalStateException("provider not built: " + target.provider()));
            int maxAttempts = properties.retry().maxAttempts();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    return new RoutedReply(provider.chat(target.model(), request), target, List.copyOf(failures));
                } catch (ProviderException e) {
                    failures.add(new Attempt(target.provider(), target.model(), attempt, e.failure(), e.status()));
                    log.warn("model={} provider={} target={} attempt={} failure={} status={}",
                            alias, target.provider(), target.model(), attempt, e.failure(), e.status());
                    log.debug("provider error detail: {}", e.getMessage());
                    if (e.failure() == Failure.REQUEST) {
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
