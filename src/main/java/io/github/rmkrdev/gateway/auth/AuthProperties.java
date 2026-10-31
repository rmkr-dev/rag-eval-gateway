package io.github.rmkrdev.gateway.auth;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the gateway names the calling client once Entra has validated the token. Token
 * validation itself is configured under {@code entra.security}.
 *
 * @param clientClaim token claim that names the client; blank means the calling application id
 *     ({@code azp} in v2 tokens, {@code appid} in v1)
 * @param clientNames optional readable names for client ids, for example an application id to
 *     {@code billing-api}, used in stored calls and reports
 */
@ConfigurationProperties("gateway.auth")
public record AuthProperties(
        @DefaultValue("") String clientClaim,
        @DefaultValue Map<String, String> clientNames) {
}
