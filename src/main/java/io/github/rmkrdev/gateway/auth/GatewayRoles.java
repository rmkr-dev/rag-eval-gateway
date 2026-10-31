package io.github.rmkrdev.gateway.auth;

/**
 * The two roles the gateway checks with {@code @Secured}. Map Entra app roles or groups to them
 * under {@code entra.security}; {@code application.yml} maps the {@code Gateway.Call} and
 * {@code Gateway.Reports} app roles by default.
 */
public final class GatewayRoles {

    /** Calls models: {@code /v1/chat/completions}, {@code /v1/models} and its own {@code /v1/calls/{id}}. */
    public static final String CALLER = "ROLE_GATEWAY_CALLER";

    /** Reads every call: {@code /v1/reports/*}, {@code /dashboard} and any {@code /v1/calls/{id}}. */
    public static final String REPORTS = "ROLE_GATEWAY_REPORTS";

    private GatewayRoles() {
    }
}
