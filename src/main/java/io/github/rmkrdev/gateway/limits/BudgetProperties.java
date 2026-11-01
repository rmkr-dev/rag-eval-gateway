package io.github.rmkrdev.gateway.limits;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * Per-client token and cost budgets, checked before every call against the usage the gateway
 * has stored. Nothing is limited until {@code default} or a client sets a budget.
 *
 * <pre>
 * gateway:
 *   budgets:
 *     default:
 *       daily-cost: 5.00
 *     clients:
 *       billing-api:
 *         monthly-cost: 300
 *         daily-tokens: 2000000
 *       batch-indexer:
 *         unlimited: true
 * </pre>
 *
 * @param enabled check budgets at all; off skips every check
 * @param zone time zone in which days and months start
 * @param refreshInterval how often stored usage is read again; spend from other replicas
 *     shows up within this time
 * @param warnAt share of a budget (0 to 1) from which answers carry
 *     {@code X-Gateway-Budget-Warning}; {@code 1} turns warnings off
 * @param defaults budget for every client not listed under {@code clients}
 * @param clients budgets by client name, as recorded for calls; an entry replaces the default
 *     completely
 */
@Validated
@ConfigurationProperties("gateway.budgets")
public record BudgetProperties(
        @DefaultValue("true") boolean enabled,
        @NotNull @DefaultValue("UTC") ZoneId zone,
        @NotNull @DefaultValue("15s") Duration refreshInterval,
        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0.8") double warnAt,
        @Name("default") @Valid @DefaultValue Budget defaults,
        @DefaultValue Map<String, @Valid Budget> clients) {

    /**
     * Limits for one client. Leave a field out for no limit of that kind; {@code 0} blocks
     * every call.
     *
     * @param dailyTokens prompt plus completion tokens per day
     * @param monthlyTokens prompt plus completion tokens per calendar month
     * @param dailyCost cost per day, in the price table currency
     * @param monthlyCost cost per calendar month
     * @param unlimited no budget at all, even when the default has one
     */
    public record Budget(
            @PositiveOrZero Long dailyTokens,
            @PositiveOrZero Long monthlyTokens,
            @PositiveOrZero BigDecimal dailyCost,
            @PositiveOrZero BigDecimal monthlyCost,
            boolean unlimited) {

        public boolean limitsAnything() {
            return !unlimited && (dailyTokens != null || monthlyTokens != null || dailyCost != null
                    || monthlyCost != null);
        }
    }

    /** Whether any client can be refused for its budget. */
    public boolean active() {
        return enabled && (defaults.limitsAnything()
                || clients.values().stream().anyMatch(Budget::limitsAnything));
    }
}
