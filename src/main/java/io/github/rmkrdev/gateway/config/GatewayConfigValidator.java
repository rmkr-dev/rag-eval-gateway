package io.github.rmkrdev.gateway.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Fails startup when a model alias points at a provider that is not configured, so a typo
 * shows up at deploy time instead of on the first request.
 */
@Component
class GatewayConfigValidator implements InitializingBean {

    private final GatewayProperties properties;

    GatewayConfigValidator(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        properties.models().forEach((alias, model) -> model.targets().forEach(target -> {
            if (!properties.providers().containsKey(target.provider())) {
                problems.add("model '%s' targets unknown provider '%s'".formatted(alias, target.provider()));
            }
        }));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid gateway configuration: " + String.join("; ", problems));
        }
    }
}
