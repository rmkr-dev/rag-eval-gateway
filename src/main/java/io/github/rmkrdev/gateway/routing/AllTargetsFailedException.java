package io.github.rmkrdev.gateway.routing;

import java.util.List;

/** Every target of an alias failed, after retries. */
public class AllTargetsFailedException extends RuntimeException {

    private final String alias;
    private final List<Attempt> attempts;

    public AllTargetsFailedException(String alias, List<Attempt> attempts) {
        super("all targets failed for model '" + alias + "'");
        this.alias = alias;
        this.attempts = List.copyOf(attempts);
    }

    public String alias() {
        return alias;
    }

    public List<Attempt> attempts() {
        return attempts;
    }
}
