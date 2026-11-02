package io.github.rmkrdev.gateway.pii;

import io.github.rmkrdev.gateway.config.GatewayProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the detector list and the {@link PiiRedactor}. Custom detectors from configuration
 * come first, then {@link PiiDetector} beans, then the built-ins, which is also the order that
 * settles overlapping matches. Startup fails on a bad pattern, a duplicate or unknown detector
 * id, or a model entry for an alias that does not exist.
 */
@Configuration(proxyBeanMethods = false)
class PiiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(PiiConfiguration.class);
    private static final Pattern LABEL = Pattern.compile("[A-Z][A-Z0-9_]{0,31}");

    @Bean
    PiiRedactor piiRedactor(PiiProperties properties, GatewayProperties gateway,
            ObjectProvider<PiiDetector> beans) {
        List<String> problems = new ArrayList<>();
        Map<String, PiiDetector> available = new LinkedHashMap<>();
        properties.custom().forEach((id, custom) -> add(available, custom(id, custom, problems), problems));
        beans.orderedStream().forEach(detector -> add(available, detector, problems));
        BuiltInDetectors.all().forEach(detector -> add(available, detector, problems));

        List<PiiDetector> enabled = new ArrayList<>();
        if (properties.detectors().isEmpty()) {
            enabled.addAll(available.values());
        } else {
            properties.detectors().forEach(id -> {
                if (!available.containsKey(id)) {
                    problems.add("gateway.pii.detectors names unknown detector '%s'".formatted(id));
                }
            });
            available.forEach((id, detector) -> {
                if (properties.detectors().contains(id)) {
                    enabled.add(detector);
                }
            });
        }
        properties.models().keySet().forEach(alias -> {
            if (!gateway.models().containsKey(alias)) {
                problems.add("gateway.pii.models names unknown model alias '%s'".formatted(alias));
            }
        });
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid PII configuration: " + String.join("; ", problems));
        }
        if (properties.active()) {
            log.info("PII handling on: default mode {}, {} model and {} client entries, detectors {}",
                    properties.mode().name().toLowerCase(Locale.ROOT), properties.models().size(),
                    properties.clients().size(), enabled.stream().map(PiiDetector::id).toList());
        }
        return new PiiRedactor(enabled, properties);
    }

    private static void add(Map<String, PiiDetector> available, PiiDetector detector, List<String> problems) {
        if (detector == null) {
            return;
        }
        if (available.putIfAbsent(detector.id(), detector) != null) {
            problems.add("PII detector id '%s' is used twice".formatted(detector.id()));
        }
    }

    private static PiiDetector custom(String id, PiiProperties.Custom custom, List<String> problems) {
        String label = custom.label() == null || custom.label().isBlank()
                ? id.toUpperCase(Locale.ROOT).replace('-', '_') : custom.label();
        if (!LABEL.matcher(label).matches()) {
            problems.add("gateway.pii.custom.%s label '%s' must be upper case letters, digits and _"
                    .formatted(id, label));
            return null;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(custom.pattern());
        } catch (PatternSyntaxException e) {
            problems.add("gateway.pii.custom.%s pattern does not compile: %s".formatted(id, e.getDescription()));
            return null;
        }
        if (pattern.matcher("").matches()) {
            problems.add("gateway.pii.custom.%s pattern matches empty text".formatted(id));
            return null;
        }
        return new PatternDetector(id, label, pattern);
    }
}
