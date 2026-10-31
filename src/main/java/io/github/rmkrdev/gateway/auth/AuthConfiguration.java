package io.github.rmkrdev.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Entra auth is on unless {@code entra.security.enabled=false}. The starter then validates
 * every bearer token, and {@code @Secured} on the controllers checks {@link GatewayRoles}.
 */
@Configuration(proxyBeanMethods = false)
class AuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AuthConfiguration.class);

    @Bean
    CallerIdentity callerIdentity(@Value("${entra.security.enabled:true}") boolean authEnabled,
            AuthProperties properties) {
        return new CallerIdentity(authEnabled, properties);
    }

    /**
     * Local development without Entra: every request is allowed and nothing is authenticated,
     * so every endpoint, reports included, is open. The {@code /v1} API is skipped by CSRF
     * protection because it has no session or cookie to forge; the dashboard is read-only.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBooleanProperty(name = "entra.security.enabled", havingValue = false)
    static class AuthDisabled {

        @Bean
        SecurityFilterChain authDisabledFilterChain(HttpSecurity http) throws Exception {
            log.warn("Entra auth is OFF (entra.security.enabled=false): every endpoint is open and callers are "
                    + "named by the X-Gateway-Client header. Use this for local development only.");
            return http
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.ignoringRequestMatchers("/v1/**"))
                    .build();
        }
    }
}
