package io.github.rmkrdev.gateway.eval;

/** A bad parameter or body on an eval endpoint; answered with a {@code 400} problem. */
public class InvalidEvalRequestException extends RuntimeException {

    public InvalidEvalRequestException(String message) {
        super(message);
    }
}
