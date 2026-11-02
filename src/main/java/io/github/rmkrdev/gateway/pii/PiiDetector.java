package io.github.rmkrdev.gateway.pii;

import java.util.Locale;

/**
 * Finds one kind of personal data in text. The built-in detectors cover common identifiers;
 * any {@code PiiDetector} bean is picked up as well, so an organisation can add its own kinds
 * (employee numbers, customer ids) without touching the gateway.
 * <p>
 * Implementations must be thread-safe and fast: they run on every prompt message before the
 * call is routed. Compile patterns once.
 */
public interface PiiDetector {

    /** Name used in configuration, counts and problems, for example {@code email}. */
    String id();

    /** Placeholder prefix, for example {@code EMAIL} for {@code [EMAIL_1]}. */
    default String label() {
        return id().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** Reports every span of {@code text} that holds this kind of personal data. */
    void detect(String text, Sink sink);

    /** Masked form of a detected value for {@code mask} mode; by default every letter and digit. */
    default String mask(String value) {
        return Masking.all(value);
    }

    /** Receives detected spans, start inclusive and end exclusive. */
    @FunctionalInterface
    interface Sink {
        void found(int start, int end);
    }
}
