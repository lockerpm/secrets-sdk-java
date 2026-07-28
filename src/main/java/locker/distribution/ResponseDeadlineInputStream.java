package locker.distribution;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Closes a response stream when its whole-body deadline expires.
 */
final class ResponseDeadlineInputStream extends FilterInputStream {
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(
                    new DaemonThreadFactory()
            );

    private final ScheduledFuture<?> timeout;
    private volatile boolean timedOut;

    ResponseDeadlineInputStream(
            InputStream input,
            Duration remaining
    ) throws IOException {
        super(input);
        if (remaining == null
                || remaining.isZero()
                || remaining.isNegative()) {
            input.close();
            throw timeout();
        }
        timeout = DEADLINES.schedule(
                this::expire,
                remaining.toNanos(),
                TimeUnit.NANOSECONDS
        );
    }

    @Override
    public int read() throws IOException {
        try {
            int value = super.read();
            requireWithinDeadline();
            return value;
        } catch (IOException exception) {
            throw translate(exception);
        }
    }

    @Override
    public int read(
            byte[] bytes,
            int offset,
            int length
    ) throws IOException {
        try {
            int count = super.read(bytes, offset, length);
            requireWithinDeadline();
            return count;
        } catch (IOException exception) {
            throw translate(exception);
        }
    }

    @Override
    public void close() throws IOException {
        timeout.cancel(false);
        super.close();
    }

    private void expire() {
        timedOut = true;
        try {
            super.close();
        } catch (IOException ignored) {
            // A following read reports the deadline, not close details.
        }
    }

    private void requireWithinDeadline() throws IOException {
        if (timedOut) {
            throw timeout();
        }
    }

    private IOException translate(IOException exception) {
        if (!timedOut) {
            return exception;
        }
        SocketTimeoutException timeout = timeout();
        timeout.initCause(exception);
        return timeout;
    }

    private static SocketTimeoutException timeout() {
        return new SocketTimeoutException(
                "Locker CLI update response body deadline expired"
        );
    }

    private static final class DaemonThreadFactory
            implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    runnable,
                    "locker-cli-update-deadline"
            );
            thread.setDaemon(true);
            return thread;
        }
    }
}
