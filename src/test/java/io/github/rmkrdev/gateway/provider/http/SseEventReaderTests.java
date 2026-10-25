package io.github.rmkrdev.gateway.provider.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SseEventReaderTests {

    private static SseEventReader reader(String body) {
        InputStream in = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return new SseEventReader(in, Duration.ofSeconds(5));
    }

    @Test
    void readsEventNamesDataAndSkipsComments() throws IOException {
        SseEventReader reader = reader("""
                : keep-alive

                event: message_start
                data: {"a":1}

                data:{"b":2}

                """);

        assertThat(reader.next()).isEqualTo(new SseEvent("message_start", "{\"a\":1}"));
        assertThat(reader.next()).isEqualTo(new SseEvent(null, "{\"b\":2}"));
        assertThat(reader.next()).isNull();
    }

    @Test
    void joinsMultiLineDataAndHandlesCrlfAndMissingFinalBlankLine() throws IOException {
        SseEventReader reader = reader("data: first\r\ndata: second\r\n\r\nid: 7\r\ndata: [DONE]");

        assertThat(reader.next()).isEqualTo(new SseEvent(null, "first\nsecond"));
        assertThat(reader.next()).isEqualTo(new SseEvent(null, "[DONE]"));
        assertThat(reader.next()).isNull();
    }
}
