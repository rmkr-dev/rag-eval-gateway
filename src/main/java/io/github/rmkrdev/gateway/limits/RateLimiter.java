package io.github.rmkrdev.gateway.limits;

import io.github.rmkrdev.gateway.call.CallObserver;
import io.github.rmkrdev.gateway.call.CallOutcome;
import io.github.rmkrdev.gateway.limits.RateLimitProperties.Limit;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-client request and token rates. Requests are taken from the bucket when a call arrives;
 * tokens are taken when it ends, from the usage the provider reported, so a client whose token
 * bucket is empty waits until it refills.
 */
public class RateLimiter implements CallObserver {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    static final String REQUESTS_PER_MINUTE = "requests-per-minute";
    static final String TOKENS_PER_MINUTE = "tokens-per-minute";

    private final RateLimitProperties properties;
    private final RateLimitStore store;
    private final Map<String, Limit> clients = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public RateLimiter(RateLimitProperties properties, RateLimitStore store) {
        this.properties = properties;
        this.store = store;
        clients.putAll(properties.clients());
    }

    /** Lets the call through or throws {@link LimitExceededException}. */
    public void admit(String client) {
        Limit limit = limitFor(client);
        if (limit == null) {
            return;
        }
        if (limit.tokensPerMinute() != null) {
            Duration wait = store.untilAvailable(key(TOKENS_PER_MINUTE, client), limit.tokensPerMinute());
            if (!wait.isZero()) {
                throw refused(client, TOKENS_PER_MINUTE, limit.tokensPerMinute(), wait);
            }
        }
        if (limit.requestsPerMinute() != null) {
            Duration wait = store.tryTake(key(REQUESTS_PER_MINUTE, client), limit.requestsPerMinute(), 1);
            if (!wait.isZero()) {
                throw refused(client, REQUESTS_PER_MINUTE, limit.requestsPerMinute(), wait);
            }
        }
    }

    @Override
    public void completed(CallOutcome outcome) {
        Limit limit = limitFor(outcome.client());
        if (limit == null || limit.tokensPerMinute() == null || outcome.usage() == null) {
            return;
        }
        long tokens = outcome.usage().totalTokens();
        if (tokens > 0) {
            store.take(key(TOKENS_PER_MINUTE, outcome.client()), limit.tokensPerMinute(), tokens);
        }
    }

    private Limit limitFor(String client) {
        if (!properties.enabled()) {
            return null;
        }
        Limit limit = clients.getOrDefault(client, properties.defaults());
        return limit.limitsAnything() ? limit : null;
    }

    private static String key(String kind, String client) {
        return kind + ":" + client;
    }

    private static LimitExceededException refused(String client, String limit, long perMinute, Duration wait) {
        log.debug("client={} refused by {} ({}), retry in {} ms", client, limit, perMinute, wait.toMillis());
        return new LimitExceededException(LimitExceededException.RATE_LIMITED, limit, client, wait, null,
                "Client '%s' is over its rate limit of %d %s".formatted(client, perMinute,
                        limit.equals(REQUESTS_PER_MINUTE) ? "requests per minute" : "tokens per minute"));
    }
}
