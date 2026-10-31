package io.github.rmkrdev.gateway.auth;

import io.github.rmkrdev.entra.security.core.principal.EntraAuthenticationToken;
import io.github.rmkrdev.entra.security.core.principal.EntraPrincipal;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Names the caller of the current request for cost and latency tracking.
 * <p>
 * With Entra auth on, the name comes from the validated token: the configured claim, or the
 * calling application id, optionally mapped to a readable name. Any {@code X-Gateway-Client}
 * header is ignored. With auth off (the {@code local} profile) the header is the only source.
 */
public class CallerIdentity {

    /** Recorded when the caller cannot be named. */
    public static final String ANONYMOUS = "anonymous";

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    private final boolean authEnabled;
    private final String clientClaim;
    private final Map<String, String> names;

    public CallerIdentity(boolean authEnabled, AuthProperties properties) {
        this.authEnabled = authEnabled;
        this.clientClaim = properties.clientClaim() == null ? "" : properties.clientClaim().trim();
        this.names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        properties.clientNames().forEach((id, name) -> {
            if (name == null || !VALID.matcher(name).matches()) {
                throw new IllegalStateException("gateway.auth.client-names." + id
                        + " must be 1-64 letters, digits, '.', '_', ':' or '-'");
            }
            names.put(id.trim(), name);
        });
    }

    public boolean authEnabled() {
        return authEnabled;
    }

    /**
     * The client to record for the current request.
     *
     * @param header value of {@code X-Gateway-Client}; used only while auth is off
     */
    public String client(String header) {
        if (!authEnabled) {
            return sanitise(header);
        }
        if (current() instanceof EntraAuthenticationToken token) {
            return fromToken(token);
        }
        return ANONYMOUS;
    }

    /** Whether the current caller may read every stored call, not only its own. */
    public boolean canReadAllCalls() {
        if (!authEnabled) {
            return true;
        }
        Authentication authentication = current();
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (GatewayRoles.REPORTS.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private String fromToken(EntraAuthenticationToken token) {
        EntraPrincipal principal = token.getEntraPrincipal();
        String id = null;
        if (!clientClaim.isEmpty()) {
            Object claim = token.getToken().getClaims().get(clientClaim);
            id = claim instanceof String value ? value.trim() : null;
        }
        if (id == null || id.isEmpty()) {
            id = principal.appId();
        }
        if (id == null || id.isEmpty()) {
            return ANONYMOUS;
        }
        String name = names.get(id);
        return name != null ? name : sanitise(id.toLowerCase(Locale.ROOT));
    }

    private static Authentication current() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    static String sanitise(String value) {
        if (value == null) {
            return ANONYMOUS;
        }
        String trimmed = value.trim();
        return VALID.matcher(trimmed).matches() ? trimmed : ANONYMOUS;
    }
}
