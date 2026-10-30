package io.github.rmkrdev.gateway.provider.bedrock;

import io.github.rmkrdev.gateway.provider.http.IdleWatchdog;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.zip.CRC32;

/**
 * Reads the AWS event stream binary framing ({@code application/vnd.amazon.eventstream}) used
 * by Bedrock {@code ConverseStream}. Each message is:
 *
 * <pre>
 * total length (4) | headers length (4) | prelude CRC32 (4) | headers | payload | message CRC32 (4)
 * </pre>
 *
 * <p>Both checksums are verified. Like the SSE reader, every read is guarded by an idle
 * timeout rather than a total one.
 */
final class EventStreamReader implements Closeable {

    /** Prelude and trailing checksum. */
    private static final int OVERHEAD = 16;
    /** AWS caps a message at 16 MiB of payload plus 128 KiB of headers. */
    private static final int MAX_MESSAGE = 16 * 1024 * 1024 + 128 * 1024 + OVERHEAD;

    /**
     * One decoded message.
     *
     * @param headers string-valued headers such as {@code :message-type} and {@code :event-type};
     *     headers of other types are skipped
     * @param payload message payload, JSON for Bedrock
     */
    record Message(Map<String, String> headers, byte[] payload) {

        String header(String name) {
            return headers.get(name);
        }

        String payloadText() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private final InputStream body;
    private final Duration idleTimeout;
    private volatile boolean timedOut;

    EventStreamReader(InputStream body, Duration idleTimeout) {
        this.body = body;
        this.idleTimeout = idleTimeout;
    }

    /**
     * @return the next message, or {@code null} when the body ends cleanly between messages
     * @throws IOException on a read failure, a timeout, a truncated or oversized message, or a
     *     checksum mismatch
     */
    Message next() throws IOException {
        byte[] prelude = new byte[12];
        int first = read(prelude, 0, prelude.length, true);
        if (first < 0) {
            return null;
        }
        ByteBuffer preludeBuffer = ByteBuffer.wrap(prelude);
        int totalLength = preludeBuffer.getInt();
        int headersLength = preludeBuffer.getInt();
        int preludeCrc = preludeBuffer.getInt();
        if (crc(prelude, 0, 8) != preludeCrc) {
            throw new IOException("event stream prelude checksum mismatch");
        }
        if (totalLength < OVERHEAD || totalLength > MAX_MESSAGE || headersLength < 0
                || headersLength > totalLength - OVERHEAD) {
            throw new IOException("invalid event stream message length " + totalLength);
        }
        byte[] message = new byte[totalLength];
        System.arraycopy(prelude, 0, message, 0, prelude.length);
        read(message, prelude.length, totalLength - prelude.length, false);
        int messageCrc = ByteBuffer.wrap(message, totalLength - 4, 4).getInt();
        if (crc(message, 0, totalLength - 4) != messageCrc) {
            throw new IOException("event stream message checksum mismatch");
        }
        Map<String, String> headers = headers(ByteBuffer.wrap(message, 12, headersLength).slice());
        byte[] payload = new byte[totalLength - OVERHEAD - headersLength];
        System.arraycopy(message, 12 + headersLength, payload, 0, payload.length);
        return new Message(headers, payload);
    }

    private static Map<String, String> headers(ByteBuffer buffer) throws IOException {
        Map<String, String> headers = new HashMap<>();
        try {
            while (buffer.hasRemaining()) {
                byte[] name = new byte[buffer.get() & 0xff];
                buffer.get(name);
                int type = buffer.get();
                switch (type) {
                    case 0, 1 -> {
                        // boolean true or false, no value bytes
                    }
                    case 2 -> buffer.get();
                    case 3 -> buffer.getShort();
                    case 4 -> buffer.getInt();
                    case 5, 8 -> buffer.getLong();
                    case 6 -> buffer.position(buffer.position() + (buffer.getShort() & 0xffff));
                    case 7 -> {
                        byte[] value = new byte[buffer.getShort() & 0xffff];
                        buffer.get(value);
                        headers.put(new String(name, StandardCharsets.UTF_8), new String(value, StandardCharsets.UTF_8));
                    }
                    case 9 -> buffer.position(buffer.position() + 16);
                    default -> throw new IOException("unknown event stream header type " + type);
                }
            }
        } catch (RuntimeException e) {
            throw new IOException("malformed event stream headers", e);
        }
        return headers;
    }

    /**
     * Fills {@code length} bytes.
     *
     * @param eofAllowed whether a clean end of the body before the first byte is fine
     * @return {@code -1} at a clean end, otherwise the number of bytes read
     */
    private int read(byte[] target, int offset, int length, boolean eofAllowed) throws IOException {
        int done = 0;
        while (done < length) {
            int n = guardedRead(target, offset + done, length - done);
            if (n < 0) {
                if (done == 0 && eofAllowed) {
                    return -1;
                }
                throw new EOFException("event stream ended inside a message");
            }
            done += n;
        }
        return done;
    }

    private int guardedRead(byte[] target, int offset, int length) throws IOException {
        ScheduledFuture<?> guard = IdleWatchdog.guard(idleTimeout, this::expire);
        try {
            int n = body.read(target, offset, length);
            if (n < 0 && timedOut) {
                throw new HttpTimeoutException("no data from provider for " + idleTimeout.toMillis() + " ms");
            }
            return n;
        } catch (HttpTimeoutException e) {
            throw e;
        } catch (IOException e) {
            if (timedOut) {
                throw new HttpTimeoutException("no data from provider for " + idleTimeout.toMillis() + " ms");
            }
            throw e;
        } finally {
            guard.cancel(false);
        }
    }

    private static int crc(byte[] data, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(data, offset, length);
        return (int) crc.getValue();
    }

    private void expire() {
        timedOut = true;
        close();
    }

    @Override
    public void close() {
        try {
            body.close();
        } catch (IOException ignored) {
            // nothing left to release
        }
    }
}
