package io.github.rmkrdev.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rmkrdev.entra.security.core.principal.EntraAuthenticationToken;
import io.github.rmkrdev.entra.security.core.principal.EntraPrincipal;
import io.github.rmkrdev.entra.security.core.principal.PrincipalType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

class CallerIdentityTests {

    private static final String APP_ID = "C0C0C0C0-0000-4000-8000-000000000002";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(Map<String, Object> claims, String... roles) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").claims(c -> c.putAll(claims))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        EntraPrincipal principal = new EntraPrincipal(PrincipalType.SERVICE, "tid", "oid", "sub", APP_ID, null, null,
                Set.of(roles), Set.of(), Set.of(), Set.of(), "2.0");
        SecurityContextHolder.getContext().setAuthentication(new EntraAuthenticationToken(jwt, principal,
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private static CallerIdentity identity(String claim, Map<String, String> names) {
        return new CallerIdentity(true, new AuthProperties(claim, names));
    }

    @Test
    void usesTheCallingApplicationIdByDefault() {
        signIn(Map.of("azp", APP_ID));
        assertThat(identity("", Map.of()).client("header-is-ignored")).isEqualTo(APP_ID.toLowerCase());
    }

    @Test
    void mapsIdsToNamesIgnoringCase() {
        signIn(Map.of("azp", APP_ID));
        assertThat(identity("", Map.of(APP_ID.toLowerCase(), "billing-api")).client(null)).isEqualTo("billing-api");
    }

    @Test
    void usesTheConfiguredClaimAndFallsBackToTheAppId() {
        signIn(Map.of("azp", APP_ID, "client_name", "search"));
        assertThat(identity("client_name", Map.of()).client(null)).isEqualTo("search");
        assertThat(identity("missing", Map.of()).client(null)).isEqualTo(APP_ID.toLowerCase());
    }

    @Test
    void malformedClaimValuesAreAnonymous() {
        signIn(Map.of("azp", APP_ID, "team", "drop table;"));
        assertThat(identity("team", Map.of()).client(null)).isEqualTo(CallerIdentity.ANONYMOUS);
    }

    @Test
    void noAuthenticationIsAnonymousWhileAuthIsOn() {
        CallerIdentity identity = identity("", Map.of());
        assertThat(identity.client("billing-api")).isEqualTo(CallerIdentity.ANONYMOUS);
        assertThat(identity.canReadAllCalls()).isFalse();
    }

    @Test
    void onlyTheReportsRoleReadsEveryCall() {
        signIn(Map.of("azp", APP_ID), GatewayRoles.CALLER);
        assertThat(identity("", Map.of()).canReadAllCalls()).isFalse();
        signIn(Map.of("azp", APP_ID), GatewayRoles.REPORTS);
        assertThat(identity("", Map.of()).canReadAllCalls()).isTrue();
    }

    @Test
    void withAuthOffTheHeaderNamesTheCaller() {
        CallerIdentity identity = new CallerIdentity(false, new AuthProperties("", Map.of()));
        assertThat(identity.client("  team.search:prod ")).isEqualTo("team.search:prod");
        assertThat(identity.client(null)).isEqualTo(CallerIdentity.ANONYMOUS);
        assertThat(identity.client("drop table;")).isEqualTo(CallerIdentity.ANONYMOUS);
        assertThat(identity.client("x".repeat(65))).isEqualTo(CallerIdentity.ANONYMOUS);
        assertThat(identity.canReadAllCalls()).isTrue();
    }

    @Test
    void badClientNamesStopStartup() {
        assertThatThrownBy(() -> identity("", Map.of(APP_ID, "has spaces")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.auth.client-names");
    }
}
