package io.github.rmkrdev.gateway.eval;

import java.util.List;

/**
 * Receives sampled calls in batches, on a background thread and never on a caller's request.
 * Register a bean to send samples somewhere new; it is used next to the built-in
 * {@code database} and {@code file} sinks. A sink that throws is logged, the batch is counted as
 * failed for that sink, and the other sinks still get it.
 */
@FunctionalInterface
public interface EvalSink {

    void write(List<EvalSample> samples);

    /** Short name for logs and metrics; the class name by default. */
    default String name() {
        return getClass().getSimpleName();
    }
}
