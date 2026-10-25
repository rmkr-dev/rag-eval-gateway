package io.github.rmkrdev.gateway.call;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Fans a finished call out to every {@link CallObserver} bean. */
@Component
public class CallObservers {

    private static final Logger log = LoggerFactory.getLogger(CallObservers.class);

    private final ObjectProvider<CallObserver> observers;

    public CallObservers(ObjectProvider<CallObserver> observers) {
        this.observers = observers;
    }

    public void completed(CallOutcome outcome) {
        log.debug("call id={} model={} provider={} streamed={} calls={} ttft_ms={} total_ms={} tokens={} error={}",
                outcome.id(), outcome.model(), outcome.provider(), outcome.streamed(),
                outcome.failedAttempts().size() + (outcome.provider() == null ? 0 : 1),
                outcome.timeToFirstToken() == null ? null : outcome.timeToFirstToken().toMillis(),
                outcome.total().toMillis(), outcome.usage().totalTokens(), outcome.error());
        observers.orderedStream().forEach(observer -> {
            try {
                observer.completed(outcome);
            } catch (RuntimeException e) {
                log.warn("call observer {} failed for call {}", observer.getClass().getName(), outcome.id(), e);
            }
        });
    }
}
