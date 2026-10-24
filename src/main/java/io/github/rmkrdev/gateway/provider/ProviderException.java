package io.github.rmkrdev.gateway.provider;

import java.time.Duration;

/** A failed provider call, classified so the router knows what to do next. */
public class ProviderException extends RuntimeException {

    /** How the router should react to a failure. */
    public enum Failure {
        /** Timeouts, connection errors, 408, 429 and 5xx: retry with backoff, then fall back. */
        TRANSIENT,
        /** Auth, unknown model and similar provider-side problems: fall back without retrying. */
        PROVIDER,
        /** The provider rejected the caller's request (400, 413, 422): return it to the caller. */
        REQUEST
    }

    private final String provider;
    private final Failure failure;
    private final Integer status;
    private final Duration retryAfter;

    public ProviderException(String provider, Failure failure, Integer status, Duration retryAfter,
            String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
        this.failure = failure;
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public ProviderException(String provider, Failure failure, Integer status, String message) {
        this(provider, failure, status, null, message, null);
    }

    /**
     * Classifies an HTTP status returned by a provider.
     *
     * @param status HTTP status code, never 2xx
     */
    public static Failure classify(int status) {
        if (status == 408 || status == 425 || status == 429 || status >= 500) {
            return Failure.TRANSIENT;
        }
        if (status == 400 || status == 413 || status == 422) {
            return Failure.REQUEST;
        }
        return Failure.PROVIDER;
    }

    public String provider() {
        return provider;
    }

    public Failure failure() {
        return failure;
    }

    /** HTTP status from the provider, or {@code null} when no response arrived. */
    public Integer status() {
        return status;
    }

    /** Delay the provider asked for in {@code Retry-After}, or {@code null}. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
