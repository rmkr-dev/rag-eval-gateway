package io.github.rmkrdev.gateway.pii;

/**
 * What happens to personal data found in a prompt, from least to most strict.
 */
public enum PiiMode {

    /** Nothing is scanned or changed. */
    OFF,

    /** Values are partly hidden, for example {@code **** **** **** 1111}. */
    MASK,

    /** Values are replaced with numbered placeholders such as {@code [EMAIL_1]}. */
    REDACT,

    /** The call is refused with {@code 422} before any provider is asked. */
    BLOCK;

    /** The stricter of two modes. */
    public PiiMode strictest(PiiMode other) {
        return other == null || compareTo(other) >= 0 ? this : other;
    }
}
