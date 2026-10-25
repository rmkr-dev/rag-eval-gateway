package io.github.rmkrdev.gateway.call;

/**
 * Hears about every finished chat call. Register a bean to record cost and latency; the
 * gateway calls every observer after the answer is sent, and an observer that throws is
 * logged and ignored so it can never break a response.
 */
@FunctionalInterface
public interface CallObserver {

    void completed(CallOutcome outcome);
}
