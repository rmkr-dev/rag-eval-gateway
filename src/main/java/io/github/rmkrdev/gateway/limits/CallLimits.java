package io.github.rmkrdev.gateway.limits;

/**
 * Decides whether a client may make a call, before any provider is asked: budget first, then
 * rate. A call refused for its budget does not use up a request from the rate limit.
 */
public class CallLimits {

    private final BudgetLedger budgets;
    private final RateLimiter rates;

    /**
     * @param budgets budget checks, or {@code null} when no budget is configured
     * @param rates rate checks, or {@code null} when no rate limit is configured
     */
    public CallLimits(BudgetLedger budgets, RateLimiter rates) {
        this.budgets = budgets;
        this.rates = rates;
    }

    /**
     * Lets the call through or throws {@link LimitExceededException}.
     *
     * @return the budget warning for {@code X-Gateway-Budget-Warning}, or {@code null}
     */
    public String admit(String client) {
        String warning = budgets == null ? null : budgets.admit(client);
        if (rates != null) {
            rates.admit(client);
        }
        return warning;
    }
}
