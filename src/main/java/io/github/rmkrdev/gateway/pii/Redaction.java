package io.github.rmkrdev.gateway.pii;

import io.github.rmkrdev.gateway.api.ChatRequest;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The outcome of scanning one request: the request to route, what was found, and, when
 * restoring is on, how to put the values back. The original values live only in memory for
 * the length of the call.
 *
 * @param request request with every found value replaced; the original when nothing was found
 * @param mode mode that applied
 * @param counts values found by detector id, in detector order
 * @param restorer restores placeholders in the answer, or {@code null} when restoring is off
 */
public record Redaction(ChatRequest request, PiiMode mode, Map<String, Integer> counts,
        PlaceholderRestorer restorer) {

    static Redaction unchanged(ChatRequest request, PiiMode mode) {
        return new Redaction(request, mode, Map.of(), null);
    }

    /** Values found in the whole request. */
    public int total() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Counts for the {@code X-Gateway-Pii} header, for example {@code email=2,phone=1}. */
    public String summary() {
        return counts.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    /** Restores placeholders in a complete answer; returns it unchanged when restoring is off. */
    public String restore(String answer) {
        return restorer == null ? answer : restorer.restore(answer);
    }
}
