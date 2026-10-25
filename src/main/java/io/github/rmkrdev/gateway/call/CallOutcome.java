package io.github.rmkrdev.gateway.call;

import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.routing.Attempt;
import java.time.Duration;
import java.util.List;

/**
 * What one gateway call cost and how long it took. Holds no prompt or answer text.
 *
 * @param id gateway call id returned to the caller
 * @param model alias the caller asked for
 * @param provider provider that answered, or {@code null} when none did
 * @param providerModel model name on that provider, or {@code null}
 * @param streamed whether the answer was streamed
 * @param failedAttempts provider calls that failed on the way
 * @param usage token counts, {@link Usage#NONE} when unknown
 * @param finishReason why generation stopped, or {@code null}
 * @param timeToFirstToken time to the first streamed chunk; for plain calls the same as
 *     {@code total}; {@code null} when nothing arrived
 * @param total time from the start of routing to the end of the answer
 * @param error {@code null} on success, otherwise a short failure label such as
 *     {@code all_targets_failed}, {@code request_rejected} or {@code stream_interrupted}
 */
public record CallOutcome(
        String id,
        String model,
        String provider,
        String providerModel,
        boolean streamed,
        List<Attempt> failedAttempts,
        Usage usage,
        String finishReason,
        Duration timeToFirstToken,
        Duration total,
        String error) {

    public boolean succeeded() {
        return error == null;
    }
}
