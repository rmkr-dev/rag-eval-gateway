package io.github.rmkrdev.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

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
    @EnableConfigurationProperties(GatewayProperties.class)
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
    void rejectsProviderWithoutBaseUrl() {
        runner.withPropertyValues("gateway.providers.broken.api-key=x")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsAliasWithoutTargets() {
        runner.withPropertyValues("gateway.models.empty.targets=")
                .run(context -> assertThat(context).hasFailed());
    }
}
