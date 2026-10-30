package io.github.rmkrdev.gateway.provider.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class EventStreamReaderTests {

    private static EventStreamReader reader(byte[] bytes) {
        return new EventStreamReader(new ByteArrayInputStream(bytes), Duration.ofSeconds(2));
    }

    @Test
    void decodesAFrameThatBotocoreAccepts() throws IOException {
        // No headers, payload {'foo':'bar'}; the bytes were checked with botocore's decoder.
        byte[] frame = HexFormat.of().parseHex("0000001d00000000fd528c5a7b27666f6f273a27626172277dc3653936");

        EventStreamReader.Message message = reader(frame).next();

        assertThat(message.headers()).isEmpty();
        assertThat(message.payloadText()).isEqualTo("{'foo':'bar'}");
        assertThat(EventStreamFrames.encode(Map.of(), "{'foo':'bar'}".getBytes())).isEqualTo(frame);
    }

    @Test
    void readsFramesInOrderAndEndsCleanly() throws IOException {
        byte[] bytes = EventStreamFrames.stream()
                .event("contentBlockDelta", "{\"delta\":{\"text\":\"Hi\"}}")
                .event("messageStop", "{\"stopReason\":\"end_turn\"}")
                .bytes();
        EventStreamReader reader = reader(bytes);

        EventStreamReader.Message first = reader.next();
        EventStreamReader.Message second = reader.next();

        assertThat(first.header(":event-type")).isEqualTo("contentBlockDelta");
        assertThat(first.header(":message-type")).isEqualTo("event");
        assertThat(first.payloadText()).isEqualTo("{\"delta\":{\"text\":\"Hi\"}}");
        assertThat(second.header(":event-type")).isEqualTo("messageStop");
        assertThat(reader.next()).isNull();
    }

    @Test
    void skipsHeadersOfOtherTypes() throws IOException {
        // :event-type string header followed by a bool, an int and a timestamp header
        ByteBuffer headers = ByteBuffer.allocate(64);
        headers.put((byte) 11).put(":event-type".getBytes()).put((byte) 7).putShort((short) 8).put("metadata".getBytes());
        headers.put((byte) 1).put("b".getBytes()).put((byte) 0);
        headers.put((byte) 1).put("i".getBytes()).put((byte) 4).putInt(42);
        headers.put((byte) 1).put("t".getBytes()).put((byte) 8).putLong(1L);
        byte[] h = Arrays.copyOf(headers.array(), headers.position());
        byte[] payload = "{}".getBytes();
        int total = 16 + h.length + payload.length;
        ByteBuffer frame = ByteBuffer.allocate(total);
        frame.putInt(total).putInt(h.length).putInt(crc(frame.array(), 8)).put(h).put(payload);
        frame.putInt(crc(frame.array(), total - 4));

        EventStreamReader.Message message = reader(frame.array()).next();

        assertThat(message.headers()).containsExactly(Map.entry(":event-type", "metadata"));
        assertThat(message.payloadText()).isEqualTo("{}");
    }

    @Test
    void rejectsCorruptedAndTruncatedFrames() {
        byte[] good = EventStreamFrames.stream().event("messageStop", "{\"stopReason\":\"end_turn\"}").bytes();

        byte[] badPayload = good.clone();
        badPayload[badPayload.length - 6] ^= 1;
        byte[] badPrelude = good.clone();
        badPrelude[3] ^= 1;
        byte[] truncated = Arrays.copyOf(good, good.length - 3);

        assertThatThrownBy(() -> reader(badPayload).next()).isInstanceOf(IOException.class).hasMessageContaining("message checksum");
        assertThatThrownBy(() -> reader(badPrelude).next()).isInstanceOf(IOException.class).hasMessageContaining("prelude checksum");
        assertThatThrownBy(() -> reader(truncated).next()).isInstanceOf(IOException.class).hasMessageContaining("inside a message");
    }

    @Test
    void timesOutWhenTheProviderGoesQuiet() {
        EventStreamReader reader = new EventStreamReader(new SilentBody(), Duration.ofMillis(200));

        assertThatThrownBy(reader::next).isInstanceOf(HttpTimeoutException.class);
    }

    /** A body that sends nothing and fails reads once closed, like an HTTP body. */
    private static final class SilentBody extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static int crc(byte[] data, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, 0, length);
        return (int) crc.getValue();
    }
}
