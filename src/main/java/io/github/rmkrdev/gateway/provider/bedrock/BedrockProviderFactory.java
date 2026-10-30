package io.github.rmkrdev.gateway.provider.bedrock;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import io.github.rmkrdev.gateway.config.ProviderType;
import io.github.rmkrdev.gateway.provider.ChatProvider;
import io.github.rmkrdev.gateway.provider.ChatProviderFactory;
import io.github.rmkrdev.gateway.provider.http.RequestSigner;
import java.time.Clock;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Builds providers that speak the Bedrock Converse API. With a Bedrock {@code api-key} the key
 * is sent as a bearer token; otherwise every request is SigV4-signed with the credentials from
 * {@code aws}, or from the standard AWS environment variables when those are blank. Missing
 * region or credentials fail startup.
 */
@Component
class BedrockProviderFactory implements ChatProviderFactory {

    private final UnaryOperator<String> env;
    private final Clock clock;

    BedrockProviderFactory() {
        this(System::getenv, Clock.systemUTC());
    }

    BedrockProviderFactory(UnaryOperator<String> env, Clock clock) {
        this.env = env;
        this.clock = clock;
    }

    @Override
    public ProviderType type() {
        return ProviderType.BEDROCK;
    }

    @Override
    public ChatProvider create(String id, GatewayProperties.Provider config) {
        if (StringUtils.hasText(config.apiKey())) {
            return new BedrockProvider(id, config, null);
        }
        return new BedrockProvider(id, config, signer(id, config));
    }

    RequestSigner signer(String id, GatewayProperties.Provider config) {
        GatewayProperties.Aws aws = config.aws();
        String region = aws == null ? null : aws.region();
        if (!StringUtils.hasText(region)) {
            throw new IllegalStateException("Bedrock provider '" + id + "' needs aws.region");
        }
        String accessKeyId = pick(aws.accessKeyId(), "AWS_ACCESS_KEY_ID");
        String secretAccessKey = pick(aws.secretAccessKey(), "AWS_SECRET_ACCESS_KEY");
        String sessionToken = StringUtils.hasText(aws.accessKeyId())
                ? aws.sessionToken() : pick(aws.sessionToken(), "AWS_SESSION_TOKEN");
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(secretAccessKey)) {
            throw new IllegalStateException("Bedrock provider '" + id + "' needs aws.access-key-id and "
                    + "aws.secret-access-key (or AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY), or an api-key");
        }
        return new BedrockSigner(new SigV4Signer(region.strip(), BedrockProvider.SIGNING_NAME),
                new SigV4Signer.Credentials(accessKeyId, secretAccessKey,
                        StringUtils.hasText(sessionToken) ? sessionToken : null),
                clock);
    }

    private String pick(String configured, String variable) {
        return StringUtils.hasText(configured) ? configured : env.apply(variable);
    }
}
