package io.github.rmkrdev.gateway.limits;

import io.github.rmkrdev.gateway.tracking.CallRecorder;
import io.github.rmkrdev.gateway.tracking.CallStore;
import io.github.rmkrdev.gateway.tracking.PriceTable;
import java.time.Clock;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires budgets and rate limits. Both are always present but check nothing until a limit is
 * configured. Budgets read the stored calls, so they need tracking on: without it the ledger
 * stays idle, and startup fails if a budget is set.
 */
@Configuration(proxyBeanMethods = false)
class LimitsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LimitsConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    RateLimitStore rateLimitStore() {
        return new InMemoryRateLimitStore();
    }

    @Bean
    RateLimiter rateLimiter(RateLimitProperties properties, RateLimitStore store) {
        return new RateLimiter(properties, store);
    }

    @Bean
    BudgetLedger budgetLedger(BudgetProperties properties, ObjectProvider<CallStore> store,
            ObjectProvider<CallRecorder> recorder, ObjectProvider<PriceTable> prices) {
        CallStore calls = store.getIfAvailable();
        PriceTable table = prices.getIfAvailable();
        if (calls == null || table == null) {
            if (properties.active()) {
                throw new IllegalStateException(
                        "gateway.budgets needs stored usage: set gateway.tracking.enabled=true or remove the budgets");
            }
            return new BudgetLedger(properties, start -> Map.of(), () -> { }, null, Clock.systemUTC());
        }
        CallRecorder writer = recorder.getIfAvailable();
        return new BudgetLedger(properties, calls::spendByClientSince, writer == null ? () -> { } : writer::flush,
                table, Clock.systemUTC());
    }

    @Bean
    CallLimits callLimits(BudgetProperties budgetProperties, RateLimitProperties rateProperties,
            BudgetLedger budgets, RateLimiter rates) {
        BudgetLedger ledger = budgetProperties.active() ? budgets : null;
        RateLimiter limiter = rateProperties.active() ? rates : null;
        if (ledger != null) {
            log.info("budgets on: {} default, {} client entries, days start in {}",
                    budgetProperties.defaults().limitsAnything() ? "with" : "no",
                    budgetProperties.clients().size(), budgetProperties.zone());
        }
        if (limiter != null) {
            log.info("rate limits on: {} default, {} client entries",
                    rateProperties.defaults().limitsAnything() ? "with" : "no", rateProperties.clients().size());
        }
        return new CallLimits(ledger, limiter);
    }
}
