package io.github.rmkrdev.gateway.web;

import java.util.regex.Pattern;

/**
 * Caller identity used to attribute cost and latency. Until Entra authentication arrives the
 * caller names itself with the {@code X-Gateway-Client} header; anything missing or malformed
 * is recorded as {@value #ANONYMOUS}.
 */
final class ClientIds {

    static final String ANONYMOUS = "anonymous";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private ClientIds() {
    }

    static String of(String header) {
        if (header == null) {
            return ANONYMOUS;
        }
        String trimmed = header.trim();
        return VALID.matcher(trimmed).matches() ? trimmed : ANONYMOUS;
    }
}
