package io.github.rmkrdev.gateway.provider.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Builds AWS event stream frames the way Bedrock sends them, for tests. */
public final class EventStreamFrames {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private EventStreamFrames() {
    }

    public static EventStreamFrames stream() {
        return new EventStreamFrames();
    }

    public EventStreamFrames event(String eventType, String json) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":event-type", eventType);
        headers.put(":content-type", "application/json");
        headers.put(":message-type", "event");
        return frame(headers, json);
    }

    public EventStreamFrames exception(String exceptionType, String json) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":exception-type", exceptionType);
        headers.put(":content-type", "application/json");
        headers.put(":message-type", "exception");
        return frame(headers, json);
    }

    public EventStreamFrames frame(Map<String, String> headers, String payload) {
        out.writeBytes(encode(headers, payload.getBytes(StandardCharsets.UTF_8)));
        return this;
    }

    public byte[] bytes() {
        return out.toByteArray();
    }

    public static byte[] encode(Map<String, String> headers, byte[] payload) {
        ByteArrayOutputStream encodedHeaders = new ByteArrayOutputStream();
        headers.forEach((name, value) -> {
            byte[] n = name.getBytes(StandardCharsets.UTF_8);
            byte[] v = value.getBytes(StandardCharsets.UTF_8);
            encodedHeaders.write(n.length);
            encodedHeaders.writeBytes(n);
            encodedHeaders.write(7);
            encodedHeaders.write(v.length >> 8);
            encodedHeaders.write(v.length & 0xff);
            encodedHeaders.writeBytes(v);
        });
        byte[] h = encodedHeaders.toByteArray();
        int total = 16 + h.length + payload.length;
        ByteBuffer buffer = ByteBuffer.allocate(total);
        buffer.putInt(total).putInt(h.length).putInt(crc(buffer.array(), 0, 8));
        buffer.put(h).put(payload);
        buffer.putInt(crc(buffer.array(), 0, total - 4));
        return buffer.array();
    }

    private static int crc(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }
}
