package io.github.rmkrdev.gateway.config;

import io.github.rmkrdev.gateway.tracking.PricingProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Fails startup when a model alias or a price entry points at a provider that is not
 * configured, so a typo shows up at deploy time instead of on the first request or as a call
 * silently stored without a cost.
 */
@Component
class GatewayConfigValidator implements InitializingBean {

    private final GatewayProperties properties;
    private final PricingProperties pricing;

    GatewayConfigValidator(GatewayProperties properties, PricingProperties pricing) {
        this.properties = properties;
        this.pricing = pricing;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        properties.models().forEach((alias, model) -> model.targets().forEach(target -> {
            if (!properties.providers().containsKey(target.provider())) {
                problems.add("model '%s' targets unknown provider '%s'".formatted(alias, target.provider()));
            }
        }));
        pricing.prices().forEach(price -> {
            if (!properties.providers().containsKey(price.provider())) {
                problems.add("price for model '%s' names unknown provider '%s'".formatted(price.model(), price.provider()));
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid gateway configuration: " + String.join("; ", problems));
        }
    }
}
