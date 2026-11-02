package io.github.rmkrdev.gateway.pii;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A detector built from a precompiled pattern, with an optional check on each match (a
 * checksum, a digit count) to keep false positives down.
 */
public class PatternDetector implements PiiDetector {

    private final String id;
    private final String label;
    private final Pattern pattern;
    private final Predicate<String> valid;
    private final UnaryOperator<String> masker;

    public PatternDetector(String id, String label, Pattern pattern, Predicate<String> valid,
            UnaryOperator<String> masker) {
        this.id = Objects.requireNonNull(id);
        this.label = Objects.requireNonNull(label);
        this.pattern = Objects.requireNonNull(pattern);
        this.valid = valid == null ? value -> true : valid;
        this.masker = masker == null ? Masking::all : masker;
    }

    public PatternDetector(String id, String label, Pattern pattern) {
        this(id, label, pattern, null, null);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public void detect(String text, Sink sink) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.end() > matcher.start() && valid.test(matcher.group())) {
                sink.found(matcher.start(), matcher.end());
            }
        }
    }

    @Override
    public String mask(String value) {
        return masker.apply(value);
    }

    @Override
    public String toString() {
        return "PatternDetector[" + id + "]";
    }
}
