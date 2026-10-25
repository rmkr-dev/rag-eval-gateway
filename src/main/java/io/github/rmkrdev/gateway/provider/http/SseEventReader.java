package io.github.rmkrdev.gateway.provider.http;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Reads server-sent events from a response body. Each read is guarded by an idle timeout: if
 * the provider sends nothing for that long, the body is closed and the read fails with
 * {@link HttpTimeoutException}. A total timeout would cut off long answers, so streams only get
 * the idle one.
 */
public final class SseEventReader implements Closeable {

    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    private final InputStream body;
    private final BufferedReader reader;
    private final Duration idleTimeout;
    private volatile boolean timedOut;

    public SseEventReader(InputStream body, Duration idleTimeout) {
        this.body = body;
        this.reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        this.idleTimeout = idleTimeout;
    }

    /**
     * @return the next event, or {@code null} at the end of the body
     */
    public SseEvent next() throws IOException {
        String event = null;
        StringBuilder data = null;
        while (true) {
            String line = readLine();
            if (line == null) {
                return data == null ? null : new SseEvent(event, data.toString());
            }
            if (line.isEmpty()) {
                if (data != null) {
                    return new SseEvent(event, data.toString());
                }
                event = null;
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (field.equals("event")) {
                event = value;
            } else if (field.equals("data")) {
                data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
            }
        }
    }

    private String readLine() throws IOException {
        ScheduledFuture<?> guard = WATCHDOG.schedule(this::expire, idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return reader.readLine();
        } catch (IOException e) {
            if (timedOut) {
                throw new HttpTimeoutException("no data from provider for " + idleTimeout.toMillis() + " ms");
            }
            throw e;
        } finally {
            guard.cancel(false);
        }
    }

    private void expire() {
        timedOut = true;
        closeQuietly();
    }

    @Override
    public void close() {
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            body.close();
        } catch (IOException ignored) {
            // nothing left to release
        }
    }

    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "provider-stream-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
