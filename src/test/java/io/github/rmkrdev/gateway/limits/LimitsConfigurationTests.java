package io.github.rmkrdev.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** Binding, validation and wiring without tracking; the full wiring runs in the endpoint tests. */
class LimitsConfigurationTests {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({BudgetProperties.class, RateLimitProperties.class})
    @Import(LimitsConfiguration.class)
    static class Config {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({BudgetProperties.class, RateLimitProperties.class})
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    private final ApplicationContextRunner binding = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void nothingIsLimitedByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            BudgetProperties budgets = context.getBean(BudgetProperties.class);
            assertThat(budgets.enabled()).isTrue();
            assertThat(budgets.active()).isFalse();
            assertThat(budgets.zone()).isEqualTo(ZoneId.of("UTC"));
            assertThat(budgets.refreshInterval()).isEqualTo(Duration.ofSeconds(15));
            assertThat(budgets.warnAt()).isEqualTo(0.8);
            assertThat(context.getBean(RateLimitProperties.class).active()).isFalse();
            assertThat(context.getBean(CallLimits.class).admit("anyone")).isNull();
        });
    }

    @Test
    void bindsDefaultsAndClientEntries() {
        binding.withPropertyValues(
                        "gateway.budgets.zone=Asia/Kolkata",
                        "gateway.budgets.default.daily-cost=5.00",
                        "gateway.budgets.clients.billing-api.monthly-tokens=2000000",
                        "gateway.budgets.clients.batch-indexer.unlimited=true",
                        "gateway.rate-limits.default.requests-per-minute=60",
                        "gateway.rate-limits.clients.billing-api.tokens-per-minute=400000")
                .run(context -> {
                    BudgetProperties budgets = context.getBean(BudgetProperties.class);
                    assertThat(budgets.zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
                    assertThat(budgets.defaults().dailyCost()).isEqualByComparingTo(new BigDecimal("5.00"));
                    assertThat(budgets.clients().get("billing-api").monthlyTokens()).isEqualTo(2_000_000L);
                    assertThat(budgets.clients().get("billing-api").dailyCost()).isNull();
                    assertThat(budgets.clients().get("batch-indexer").unlimited()).isTrue();
                    RateLimitProperties rates = context.getBean(RateLimitProperties.class);
                    assertThat(rates.defaults().requestsPerMinute()).isEqualTo(60L);
                    assertThat(rates.clients().get("billing-api").tokensPerMinute()).isEqualTo(400_000L);
                    assertThat(rates.active()).isTrue();
                });
    }

    @Test
    void rejectsNegativeBudgetsAndZeroRates() {
        binding.withPropertyValues("gateway.budgets.default.daily-tokens=-1")
                .run(context -> assertThat(context).hasFailed());
        binding.withPropertyValues("gateway.rate-limits.default.requests-per-minute=0")
                .run(context -> assertThat(context).hasFailed());
        binding.withPropertyValues("gateway.budgets.warn-at=1.5")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aBudgetWithoutTrackingFailsStartup() {
        runner.withPropertyValues("gateway.budgets.default.daily-tokens=1000")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("gateway.tracking.enabled=true"));
    }

    @Test
    void rateLimitsWorkWithoutTracking() {
        runner.withPropertyValues("gateway.rate-limits.default.requests-per-minute=1")
                .run(context -> {
                    CallLimits limits = context.getBean(CallLimits.class);
                    limits.admit("a");
                    assertThat(catchThrowableOfType(LimitExceededException.class,
                            () -> limits.admit("a"))).isNotNull();
                });
    }
}
