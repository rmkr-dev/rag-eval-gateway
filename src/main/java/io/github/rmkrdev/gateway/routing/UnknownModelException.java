package io.github.rmkrdev.gateway.routing;

/** The caller asked for a model alias that is not configured. */
public class UnknownModelException extends RuntimeException {

    private final String alias;

    public UnknownModelException(String alias) {
        super("unknown model '" + alias + "'");
        this.alias = alias;
    }

    public String alias() {
        return alias;
    }
}
