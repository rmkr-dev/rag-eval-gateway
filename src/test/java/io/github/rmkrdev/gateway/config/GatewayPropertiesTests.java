package io.github.rmkrdev.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rmkrdev.gateway.tracking.PricingProperties;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class GatewayPropertiesTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({GatewayProperties.class, PricingProperties.class})
    @Import(GatewayConfigValidator.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void appliesOpenAiCompatibleDefaults() {
        runner.withPropertyValues("gateway.providers.together.base-url=https://api.together.example/v1")
                .run(context -> {
                    GatewayProperties.Provider provider =
                            context.getBean(GatewayProperties.class).providers().get("together");
                    assertThat(provider.type()).isEqualTo(ProviderType.OPENAI_COMPATIBLE);
                    assertThat(provider.apiKeyHeader()).isEqualTo("Authorization");
                    assertThat(provider.apiKeyPrefix()).isEqualTo("Bearer ");
                    assertThat(provider.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(provider.readTimeout()).isEqualTo(Duration.ofSeconds(60));
                });
    }

    @Test
    void customHeaderAndNativeTypeBind() {
        runner.withPropertyValues(
                        "gateway.providers.anthropic.type=anthropic",
                        "gateway.providers.anthropic.base-url=https://api.anthropic.example",
                        "gateway.providers.anthropic.api-key-header=x-api-key",
                        "gateway.providers.anthropic.api-key-prefix=")
                .run(context -> {
                    GatewayProperties.Provider provider =
                            context.getBean(GatewayProperties.class).providers().get("anthropic");
                    assertThat(provider.type()).isEqualTo(ProviderType.ANTHROPIC);
                    assertThat(provider.apiKeyHeader()).isEqualTo("x-api-key");
                    assertThat(provider.apiKeyPrefix()).isEmpty();
                });
    }

    @Test
    void appliesRetryDefaultsAndBindsChatPathAndHeaders() {
        runner.withPropertyValues(
                        "gateway.providers.openrouter.base-url=https://openrouter.example/api/v1",
                        "gateway.providers.openrouter.chat-path=/v2/chat",
                        "gateway.providers.openrouter.headers.HTTP-Referer=https://example.org")
                .run(context -> {
                    GatewayProperties properties = context.getBean(GatewayProperties.class);
                    GatewayProperties.Provider provider = properties.providers().get("openrouter");
                    assertThat(provider.chatPath()).isEqualTo("/v2/chat");
                    assertThat(provider.headers()).containsEntry("HTTP-Referer", "https://example.org");
                    assertThat(properties.retry().maxAttempts()).isEqualTo(2);
                    assertThat(properties.retry().initialBackoff()).isEqualTo(Duration.ofMillis(200));
                    assertThat(properties.retry().maxBackoff()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.retry().multiplier()).isEqualTo(2.0);
                    assertThat(properties.retry().jitter()).isEqualTo(0.2);
                });
    }

    @Test
    void bedrockDefaultsToTheRegionalEndpointAndHidesKeys() {
        runner.withPropertyValues(
                        "gateway.providers.bedrock.type=bedrock",
                        "gateway.providers.bedrock.aws.region=eu-central-1",
                        "gateway.providers.bedrock.aws.access-key-id=AKIDEXAMPLE",
                        "gateway.providers.bedrock.aws.secret-access-key=secret-example",
                        "gateway.providers.bedrock.aws.session-token=token-example")
                .run(context -> {
                    GatewayProperties.Provider provider =
                            context.getBean(GatewayProperties.class).providers().get("bedrock");
                    assertThat(provider.type()).isEqualTo(ProviderType.BEDROCK);
                    assertThat(provider.baseUrl()).isEqualTo("https://bedrock-runtime.eu-central-1.amazonaws.com");
                    assertThat(provider.chatPath()).isEqualTo("/model");
                    assertThat(provider.apiKeyPrefix()).isEqualTo("Bearer ");
                    assertThat(provider.aws().region()).isEqualTo("eu-central-1");
                    assertThat(provider.toString()).contains("eu-central-1")
                            .doesNotContain("AKIDEXAMPLE", "secret-example", "token-example");
                });
    }

    @Test
    void bedrockWithoutRegionOrBaseUrlFailsValidation() {
        runner.withPropertyValues("gateway.providers.bedrock.type=bedrock")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsZeroRetryAttempts() {
        runner.withPropertyValues("gateway.retry.max-attempts=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void emptyConfigurationStarts() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GatewayProperties.class).models()).isEmpty();
        });
    }

    @Test
    void rejectsAliasPointingAtUnknownProvider() {
        runner.withPropertyValues(
                        "gateway.models.chat.targets[0].provider=missing",
                        "gateway.models.chat.targets[0].model=gpt-4o-mini")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("unknown provider 'missing'"));
    }

    @Test
    void rejectsPriceForUnknownProvider() {
        runner.withPropertyValues(
                        "gateway.pricing.prices[0].provider=missing",
                        "gateway.pricing.prices[0].model=gpt-4o-mini",
                        "gateway.pricing.prices[0].input=0.15",
                        "gateway.pricing.prices[0].output=0.60")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("names unknown provider 'missing'"));
    }

    @Test
    void bindsPricesWithCacheRatesDefaultingToInput() {
        runner.withPropertyValues(
                        "gateway.providers.openai.base-url=https://api.openai.example/v1",
                        "gateway.pricing.prices[0].provider=openai",
                        "gateway.pricing.prices[0].model=gpt-4o-mini",
                        "gateway.pricing.prices[0].input=0.15",
                        "gateway.pricing.prices[0].output=0.60",
                        "gateway.pricing.prices[0].effective-from=2026-11-01")
                .run(context -> {
                    PricingProperties pricing = context.getBean(PricingProperties.class);
                    assertThat(pricing.currency()).isEqualTo("USD");
                    PricingProperties.Price price = pricing.prices().getFirst();
                    assertThat(price.cacheRead()).isEqualByComparingTo(new BigDecimal("0.15"));
                    assertThat(price.version()).isEqualTo("2026-11-01");
                });
    }

    @Test
    void rejectsNegativePrice() {
        runner.withPropertyValues(
                        "gateway.providers.openai.base-url=https://api.openai.example/v1",
                        "gateway.pricing.prices[0].provider=openai",
                        "gateway.pricing.prices[0].model=gpt-4o-mini",
                        "gateway.pricing.prices[0].input=-1",
                        "gateway.pricing.prices[0].output=0.60")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsProviderWithoutBaseUrl() {
        runner.withPropertyValues("gateway.providers.broken.api-key=x")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAliasWithoutTargets() {
        runner.withPropertyValues("gateway.models.empty.targets=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void nativeProvidersDefaultToTheirOwnKeyHeaderAndPath() {
        runner.withPropertyValues(
                        "gateway.providers.claude.type=anthropic",
                        "gateway.providers.claude.base-url=https://api.anthropic.example",
                        "gateway.providers.gemini.type=gemini",
                        "gateway.providers.gemini.base-url=https://gemini.example/v1beta",
                        "gateway.providers.gemini.default-max-tokens=1024")
                .run(context -> {
                    GatewayProperties properties = context.getBean(GatewayProperties.class);
                    GatewayProperties.Provider claude = properties.providers().get("claude");
                    assertThat(claude.apiKeyHeader()).isEqualTo("x-api-key");
                    assertThat(claude.apiKeyPrefix()).isEmpty();
                    assertThat(claude.chatPath()).isEqualTo("/v1/messages");
                    assertThat(claude.defaultMaxTokens()).isNull();
                    GatewayProperties.Provider gemini = properties.providers().get("gemini");
                    assertThat(gemini.apiKeyHeader()).isEqualTo("x-goog-api-key");
                    assertThat(gemini.apiKeyPrefix()).isEmpty();
                    assertThat(gemini.chatPath()).isEqualTo("/models");
                    assertThat(gemini.defaultMaxTokens()).isEqualTo(1024);
                });
    }

    @Test
    void explicitEmptyKeyPrefixIsKept() {
        runner.withPropertyValues(
                        "gateway.providers.azure.base-url=https://res.openai.example/openai/v1",
                        "gateway.providers.azure.api-key-header=api-key",
                        "gateway.providers.azure.api-key-prefix=")
                .run(context -> {
                    GatewayProperties.Provider azure = context.getBean(GatewayProperties.class).providers().get("azure");
                    assertThat(azure.apiKeyHeader()).isEqualTo("api-key");
                    assertThat(azure.apiKeyPrefix()).isEmpty();
                });
    }

    @Test
    void rejectsNonPositiveDefaultMaxTokens() {
        runner.withPropertyValues(
                        "gateway.providers.claude.type=anthropic",
                        "gateway.providers.claude.base-url=https://api.anthropic.example",
                        "gateway.providers.claude.default-max-tokens=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
