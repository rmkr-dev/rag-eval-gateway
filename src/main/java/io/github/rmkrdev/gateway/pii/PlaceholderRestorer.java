package io.github.rmkrdev.gateway.pii;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts original values back in place of the placeholders one call created. Only placeholders
 * from that call are replaced; any other bracketed text is left alone. For streams, a
 * placeholder split across chunks is held back until it is complete, so the caller never sees
 * half of one.
 */
public final class PlaceholderRestorer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\[[A-Z][A-Z0-9_]*_\\d+]");
    private static final Pattern PARTIAL = Pattern.compile("\\[[A-Z0-9_]*");

    private final Map<String, String> originals;
    private final int longest;
    private String pending = "";

    PlaceholderRestorer(Map<String, String> originals) {
        this.originals = originals;
        this.longest = originals.keySet().stream().mapToInt(String::length).max().orElse(0);
    }

    /** Restores a complete text. */
    public String restore(String text) {
        if (text == null || originals.isEmpty() || text.indexOf('[') < 0) {
            return text;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            String original = originals.get(matcher.group());
            if (original != null) {
                if (out == null) {
                    out = new StringBuilder(text.length() + 32);
                }
                out.append(text, last, matcher.start()).append(original);
                last = matcher.end();
            }
        }
        if (out == null) {
            return text;
        }
        return out.append(text, last, text.length()).toString();
    }

    /** Restores the next streamed chunk, holding back a trailing placeholder that may be cut off. */
    public String next(String chunk) {
        String text = pending + (chunk == null ? "" : chunk);
        pending = "";
        int open = text.lastIndexOf('[');
        if (open >= 0 && text.indexOf(']', open) < 0 && text.length() - open < longest
                && PARTIAL.matcher(text).region(open, text.length()).matches()) {
            pending = text.substring(open);
            text = text.substring(0, open);
        }
        return restore(text);
    }

    /** Whatever was held back when the stream ends; it was never a complete placeholder. */
    public String finish() {
        String rest = pending;
        pending = "";
        return rest;
    }
}
