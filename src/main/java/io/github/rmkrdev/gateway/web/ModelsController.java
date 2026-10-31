package io.github.rmkrdev.gateway.web;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import java.util.List;
import io.github.rmkrdev.gateway.auth.GatewayRoles;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lists the model aliases callers can use. Never exposes provider keys or base URLs. */
@RestController
@RequestMapping("/v1/models")
@Secured(GatewayRoles.CALLER)
class ModelsController {

    private final GatewayProperties properties;

    ModelsController(GatewayProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    ModelList list() {
        List<ModelInfo> models = properties.models().entrySet().stream()
                .map(entry -> new ModelInfo(
                        entry.getKey(),
                        entry.getValue().targets().stream().map(GatewayProperties.Target::provider).toList()))
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .toList();
        return new ModelList(models);
    }

    record ModelList(List<ModelInfo> data) {
    }

    /**
     * @param id alias name
     * @param providers provider ids in fallback order
     */
    record ModelInfo(String id, List<String> providers) {
    }
}
