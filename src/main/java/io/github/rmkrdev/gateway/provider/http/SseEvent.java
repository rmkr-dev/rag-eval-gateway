package io.github.rmkrdev.gateway.provider.http;

/**
 * One server-sent event.
 *
 * @param event the {@code event:} field, or {@code null} when absent
 * @param data the {@code data:} lines joined with {@code \n}
 */
public record SseEvent(String event, String data) {
}
