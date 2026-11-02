package io.github.rmkrdev.gateway.pii;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A prompt held personal data and the {@code block} mode applies, so the call was refused
 * before any provider was asked. Carries counts by detector, never the values.
 */
public class PiiBlockedException extends RuntimeException {

    /** Problem {@code code} for a refused prompt. */
    public static final String CODE = "pii_detected";

    private final Map<String, Integer> counts;

    public PiiBlockedException(Map<String, Integer> counts) {
        super("The request contains personal data (" + String.join(", ", counts.keySet())
                + ") and the policy for this caller blocks it");
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
    }

    /** Values found, by detector id. */
    public Map<String, Integer> counts() {
        return counts;
    }

    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }
}
