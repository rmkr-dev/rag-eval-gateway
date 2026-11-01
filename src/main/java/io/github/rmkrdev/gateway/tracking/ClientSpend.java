package io.github.rmkrdev.gateway.tracking;

import java.math.BigDecimal;

/**
 * What one client has used in a period.
 *
 * @param tokens prompt plus completion tokens
 * @param cost cost of the priced calls, in the price table currency
 */
public record ClientSpend(long tokens, BigDecimal cost) {

    public static final ClientSpend NONE = new ClientSpend(0, BigDecimal.ZERO);

    public ClientSpend plus(ClientSpend other) {
        return new ClientSpend(tokens + other.tokens, cost.add(other.cost));
    }
}
