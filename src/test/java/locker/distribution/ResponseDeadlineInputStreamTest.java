package locker.distribution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResponseDeadlineInputStreamTest {
    @Test
    public void closesAStalledBodyAtTheWholeResponseDeadline()
            throws Exception {
        BlockingInputStream blocking = new BlockingInputStream();
        long started = System.nanoTime();

        try (ResponseDeadlineInputStream input =
                     new ResponseDeadlineInputStream(
                             blocking,
                             Duration.ofMillis(100)
                     )) {
            assertThrows(
                    SocketTimeoutException.class,
                    input::read
            );
        }

        assertTrue(blocking.closed.await(1, TimeUnit.SECONDS));
        assertTrue(
                Duration.ofNanos(
                        System.nanoTime() - started
                ).compareTo(Duration.ofSeconds(2)) < 0
        );
    }

    private static final class BlockingInputStream
            extends InputStream {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                if (!closed.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("fixture did not close");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(
                        "fixture interrupted",
                        exception
                );
            }
            throw new IOException("fixture closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
