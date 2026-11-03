package io.github.rmkrdev.gateway.pii;

import io.github.rmkrdev.gateway.api.ChatMessage;
import io.github.rmkrdev.gateway.api.ChatRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scans prompt messages with every enabled detector and applies the mode for the caller and
 * model alias. Found spans that overlap are settled by detector order: the earlier detector
 * wins, so a card number is never also reported as a phone number.
 * <p>
 * In {@code redact} mode the same value gets the same placeholder in every message of the
 * call, so the model can still tell two people apart. Values are never logged or stored; only
 * their counts leave this class.
 */
public class PiiRedactor {

    private final List<PiiDetector> detectors;
    private final PiiProperties properties;
    private final Map<String, PiiMode> clients = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    public PiiRedactor(List<PiiDetector> detectors, PiiProperties properties) {
        this.detectors = List.copyOf(detectors);
        this.properties = properties;
        this.clients.putAll(properties.clients());
    }

    public List<PiiDetector> detectors() {
        return detectors;
    }

    /** The mode for one call: the stricter of the model and client entries, else the default. */
    public PiiMode modeFor(String client, String model) {
        PiiMode byModel = model == null ? null : properties.models().get(model);
        PiiMode byClient = client == null ? null : clients.get(client);
        if (byModel == null && byClient == null) {
            return properties.mode();
        }
        return byModel == null ? byClient : byModel.strictest(byClient);
    }

    /**
     * Applies the caller's mode to every message, and to any eval context with the same
     * placeholders. Personal data in eval context is never sent anywhere, so it neither counts
     * nor blocks the call: under {@code block} it is redacted instead.
     *
     * @throws PiiBlockedException when the mode is {@code block} and anything was found
     */
    public Redaction redact(ChatRequest request, String client) {
        PiiMode mode = modeFor(client, request.model());
        if (mode == PiiMode.OFF) {
            return Redaction.unchanged(request, mode);
        }
        Session session = new Session(mode);
        List<ChatMessage> messages = new ArrayList<>(request.messages().size());
        boolean changed = false;
        for (ChatMessage message : request.messages()) {
            String content = message.content();
            String replaced = content == null ? null : session.apply(content);
            changed |= replaced != content;
            messages.add(replaced == content ? message : new ChatMessage(message.role(), replaced));
        }
        Map<String, Integer> counts = session.counts();
        if (mode == PiiMode.BLOCK && !counts.isEmpty()) {
            throw new PiiBlockedException(counts);
        }
        List<String> context = request.evalContext();
        if (context != null && !context.isEmpty()) {
            List<String> replaced = new ArrayList<>(context.size());
            for (String passage : context) {
                String out = passage == null ? null : session.applyUncounted(passage);
                changed |= out != passage;
                replaced.add(out);
            }
            context = replaced;
        }
        if (!changed) {
            return Redaction.unchanged(request, mode);
        }
        ChatRequest redacted = request.with(messages, context);
        PlaceholderRestorer restorer = mode == PiiMode.REDACT && properties.restore()
                ? new PlaceholderRestorer(session.originals) : null;
        return new Redaction(redacted, mode, counts, restorer);
    }

    /**
     * Replaces every value found with its label, for example {@code [EMAIL]}, for text that is
     * about to be logged. Uses every enabled detector, whatever the mode.
     */
    public String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<Span> spans = find(text);
        if (spans.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int last = 0;
        for (Span span : spans) {
            out.append(text, last, span.start()).append('[').append(span.detector().label()).append(']');
            last = span.end();
        }
        return out.append(text, last, text.length()).toString();
    }

    /** {@link #scrub} when {@code scrub-logs} is on, otherwise the text unchanged. */
    public String forLog(String text) {
        return properties.scrubLogs() ? scrub(text) : text;
    }

    /** Non-overlapping spans in text order. */
    List<Span> find(String text) {
        TreeMap<Integer, Span> accepted = new TreeMap<>();
        for (PiiDetector detector : detectors) {
            detector.detect(text, (start, end) -> {
                if (start < 0 || end > text.length() || start >= end) {
                    return;
                }
                Map.Entry<Integer, Span> before = accepted.floorEntry(start);
                if (before != null && before.getValue().end() > start) {
                    return;
                }
                Map.Entry<Integer, Span> after = accepted.ceilingEntry(start);
                if (after != null && after.getKey() < end) {
                    return;
                }
                accepted.put(start, new Span(start, end, detector));
            });
        }
        return new ArrayList<>(accepted.values());
    }

    record Span(int start, int end, PiiDetector detector) {
    }

    /** Placeholder numbering and counts for one call. */
    private final class Session {

        private final PiiMode mode;
        private final Map<String, Integer> counts = new HashMap<>();
        private final Map<String, Integer> numbers = new HashMap<>();
        private final Map<String, String> placeholders = new HashMap<>();
        final Map<String, String> originals = new HashMap<>();

        Session(PiiMode mode) {
            this.mode = mode;
        }

        /** Returns {@code text} itself when nothing was found. */
        String apply(String text) {
            return apply(text, mode, true);
        }

        /** Replaces without counting; {@code block} redacts, as nothing is refused for this text. */
        String applyUncounted(String text) {
            return apply(text, mode == PiiMode.BLOCK ? PiiMode.REDACT : mode, false);
        }

        private String apply(String text, PiiMode how, boolean count) {
            List<Span> spans = find(text);
            if (spans.isEmpty()) {
                return text;
            }
            StringBuilder out = new StringBuilder(text.length() + spans.size() * 8);
            int last = 0;
            for (Span span : spans) {
                PiiDetector detector = span.detector();
                String value = text.substring(span.start(), span.end());
                if (count) {
                    counts.merge(detector.id(), 1, Integer::sum);
                }
                out.append(text, last, span.start()).append(switch (how) {
                    case MASK -> detector.mask(value);
                    case REDACT -> placeholder(detector, value);
                    default -> "";
                });
                last = span.end();
            }
            return out.append(text, last, text.length()).toString();
        }

        private String placeholder(PiiDetector detector, String value) {
            return placeholders.computeIfAbsent(detector.label() + '\u0000' + value, key -> {
                int number = numbers.merge(detector.label(), 1, Integer::sum);
                String placeholder = "[" + detector.label() + "_" + number + "]";
                originals.put(placeholder, value);
                return placeholder;
            });
        }

        Map<String, Integer> counts() {
            Map<String, Integer> ordered = new LinkedHashMap<>();
            for (PiiDetector detector : detectors) {
                Integer count = counts.get(detector.id());
                if (count != null) {
                    ordered.put(detector.id(), count);
                }
            }
            return ordered;
        }
    }
}
