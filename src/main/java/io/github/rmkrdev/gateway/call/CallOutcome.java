package io.github.rmkrdev.gateway.call;

import io.github.rmkrdev.gateway.api.Usage;
import io.github.rmkrdev.gateway.routing.Attempt;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * What one gateway call cost and how long it took. Holds no prompt or answer text.
 *
 * @param id gateway call id returned to the caller
 * @param startedAt when the gateway received the call
 * @param client caller identity, {@code anonymous} until authentication is configured
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
 *     {@code all_targets_failed}, {@code request_rejected}, {@code stream_interrupted} or
 *     {@code pii_blocked}
 * @param piiRedactions personal data values found in the prompt (redacted, masked or blocked);
 *     never the values themselves
 */
public record CallOutcome(
        String id,
        Instant startedAt,
        String client,
        String model,
        String provider,
        String providerModel,
        boolean streamed,
        List<Attempt> failedAttempts,
        Usage usage,
        String finishReason,
        Duration timeToFirstToken,
        Duration total,
        String error,
        int piiRedactions) {

    /** An outcome without personal data found in the prompt. */
    public CallOutcome(String id, Instant startedAt, String client, String model, String provider,
            String providerModel, boolean streamed, List<Attempt> failedAttempts, Usage usage, String finishReason,
            Duration timeToFirstToken, Duration total, String error) {
        this(id, startedAt, client, model, provider, providerModel, streamed, failedAttempts, usage, finishReason,
                timeToFirstToken, total, error, 0);
    }

    public boolean succeeded() {
        return error == null;
    }
}
