package io.github.rmkrdev.gateway.routing;

import java.util.List;

/**
 * A provider rejected the caller's request itself (400, 413, 422). Retrying or falling back
 * would get the same answer, so it goes straight back to the caller.
 */
public class RequestRejectedException extends RuntimeException {

    private final int status;
    private final List<Attempt> attempts;

    public RequestRejectedException(int status, String message, List<Attempt> attempts) {
        super(message);
        this.status = status;
        this.attempts = List.copyOf(attempts);
    }

    public int status() {
        return status;
    }

    public List<Attempt> attempts() {
        return attempts;
    }
}
