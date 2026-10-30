package io.github.rmkrdev.gateway.provider.http;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * One daemon thread that fires idle timeouts for streaming reads. A stream schedules a guard
 * before each blocking read and cancels it afterwards; if the guard fires, it closes the body
 * so the read fails instead of hanging.
 */
public final class IdleWatchdog {

    private static final ScheduledThreadPoolExecutor EXECUTOR = executor();

    private IdleWatchdog() {
    }

    /**
     * @param timeout how long the read may block
     * @param onExpiry what to run if it blocks for longer, normally closing the body
     * @return the guard to cancel once the read returns
     */
    public static ScheduledFuture<?> guard(Duration timeout, Runnable onExpiry) {
        return EXECUTOR.schedule(onExpiry, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static ScheduledThreadPoolExecutor executor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "provider-stream-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
