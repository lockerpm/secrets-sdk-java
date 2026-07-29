package locker.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CliProcessRunnerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    public void sendsRequestOnStdinAndKeepsStderrSeparate() throws Exception {
        CliProcessRunner runner = fixtureRunner(
                "echo",
                Duration.ofSeconds(5),
                4096,
                4096
        );

        CliProcessRunner.Result result = runner.execute(
                "sensitive-request-body".getBytes(StandardCharsets.UTF_8)
        );

        assertEquals(0, result.getExitCode());
        String stdout = new String(result.getStdout(), StandardCharsets.UTF_8);
        String stderr = new String(result.getStderr(), StandardCharsets.UTF_8);
        assertTrue(stdout.contains("\"argument\":\"sdk\""));
        assertTrue(stdout.contains("\"request_bytes\":22"));
        assertFalse(stdout.contains("safe diagnostic"));
        assertEquals("safe diagnostic", stderr);
    }

    @Test
    public void enforcesProcessTimeout() {
        CliProcessRunner runner = fixtureRunner(
                "sleep",
                Duration.ofMillis(150),
                4096,
                4096
        );

        long started = System.nanoTime();
        CliProcessException exception = assertThrows(
                CliProcessException.class,
                () -> runner.execute(new byte[0])
        );
        long elapsedMillis = Duration.ofNanos(
                System.nanoTime() - started
        ).toMillis();

        assertEquals(CliProcessException.Reason.TIMEOUT, exception.getReason());
        assertTrue(elapsedMillis < 5_000);
    }

    @Test
    public void preExecutionVerificationUsesTheProcessTimeoutBudget()
            throws Exception {
        AtomicBoolean verifierInterrupted = new AtomicBoolean();
        CliProcessRunner runner = new CliProcessRunner(
                fixtureLauncher("echo"),
                Duration.ofMillis(100),
                4096,
                4096,
                deadlineNanos -> {
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException exception) {
                        verifierInterrupted.set(true);
                        Thread.currentThread().interrupt();
                        throw exception;
                    }
                }
        );

        long started = System.nanoTime();
        CliProcessException exception = assertThrows(
                CliProcessException.class,
                () -> runner.execute(new byte[0])
        );
        long elapsedMillis = Duration.ofNanos(
                System.nanoTime() - started
        ).toMillis();

        assertEquals(
                CliProcessException.Reason.TIMEOUT,
                exception.getReason()
        );
        assertTrue(elapsedMillis < 5_000);
        for (int attempt = 0;
             attempt < 100 && !verifierInterrupted.get();
             attempt++) {
            Thread.sleep(10);
        }
        assertTrue(verifierInterrupted.get());
    }

    @Test
    public void timeoutTerminatesRecordedDescendants() throws Exception {
        Path childPidFile = temporaryDirectory.resolve("child.pid");
        CliProcessRunner runner = fixtureRunner(
                "spawn",
                Duration.ofMillis(500),
                4096,
                4096,
                "-Dlocker.fixture.childPidFile=" + childPidFile
        );

        CliProcessException exception = assertThrows(
                CliProcessException.class,
                () -> runner.execute(new byte[0])
        );
        assertEquals(
                CliProcessException.Reason.TIMEOUT,
                exception.getReason()
        );
        assertTrue(Files.isRegularFile(childPidFile));

        long childPid = Long.parseLong(
                Files.readString(
                        childPidFile,
                        StandardCharsets.US_ASCII
                ).trim()
        );
        try {
            assertTrue(
                    awaitProcessExit(childPid, Duration.ofSeconds(5)),
                    "timed-out CLI descendant remained alive"
            );
        } finally {
            ProcessHandle.of(childPid).ifPresent(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
        }
    }

    @Test
    public void successfulRootExitStillTerminatesRecordedDescendants()
            throws Exception {
        Path childPidFile = temporaryDirectory.resolve(
                "successful-root-child.pid"
        );
        CliProcessRunner runner = fixtureRunner(
                "spawn-and-exit",
                Duration.ofSeconds(5),
                4096,
                4096,
                "-Dlocker.fixture.childPidFile=" + childPidFile
        );

        CliProcessRunner.Result result = runner.execute(new byte[0]);
        assertEquals(0, result.getExitCode());
        assertTrue(Files.isRegularFile(childPidFile));

        long childPid = Long.parseLong(
                Files.readString(
                        childPidFile,
                        StandardCharsets.US_ASCII
                ).trim()
        );
        try {
            assertTrue(
                    awaitProcessExit(childPid, Duration.ofSeconds(5)),
                    "successful CLI descendant remained alive"
            );
        } finally {
            ProcessHandle.of(childPid).ifPresent(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
        }
    }

    @Test
    public void boundsProtocolOutput() {
        CliProcessRunner runner = fixtureRunner(
                "overflow",
                Duration.ofSeconds(5),
                128,
                128
        );

        CliProcessException exception = assertThrows(
                CliProcessException.class,
                () -> runner.execute(new byte[0])
        );

        assertEquals(
                CliProcessException.Reason.OUTPUT_LIMIT,
                exception.getReason()
        );
        assertFalse(exception.getMessage().contains("sensitive"));
    }

    @Test
    public void terminatesUnboundedOutputBeforeTheRequestDeadline() {
        CliProcessRunner runner = fixtureRunner(
                "overflow-forever",
                Duration.ofSeconds(10),
                128,
                128
        );

        long started = System.nanoTime();
        CliProcessException exception = assertThrows(
                CliProcessException.class,
                () -> runner.execute(new byte[0])
        );
        long elapsedMillis = Duration.ofNanos(
                System.nanoTime() - started
        ).toMillis();

        assertEquals(
                CliProcessException.Reason.OUTPUT_LIMIT,
                exception.getReason()
        );
        assertTrue(
                elapsedMillis < 5_000,
                "output overflow waited for the request deadline"
        );
    }

    @Test
    public void rejectsStdoutLimitAboveProtocolHardCap() {
        assertThrows(
                IllegalArgumentException.class,
                () -> fixtureRunner(
                        "echo",
                        Duration.ofSeconds(5),
                        CliProcessRunner.DEFAULT_MAX_STDOUT_BYTES + 1,
                        4096
                )
        );
    }

    @Test
    public void removesCredentialVariablesFromChildEnvironment() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("LOCKER_ACCESS_KEY_ID", "sensitive-access");
        environment.put("locker_secret_access_key", "sensitive-secret");
        environment.put("GITHUB_TOKEN", "sensitive-token");
        environment.put("DATABASE_PASSWORD", "sensitive-password");
        environment.put("LOCKER_ACCESS_KEY_SECRET", "legacy-sensitive-secret");
        environment.put("ACCESS_KEY_SECRET", "older-sensitive-secret");
        environment.put("SAFE_VALUE", "not-allowlisted");
        environment.put("PATH", "safe-path");
        environment.put("https_proxy", "safe-proxy");

        CliProcessRunner.sanitizeEnvironment(environment);

        assertFalse(environment.containsKey("LOCKER_ACCESS_KEY_ID"));
        assertFalse(environment.containsKey("locker_secret_access_key"));
        assertFalse(environment.containsKey("GITHUB_TOKEN"));
        assertFalse(environment.containsKey("DATABASE_PASSWORD"));
        assertFalse(environment.containsKey("LOCKER_ACCESS_KEY_SECRET"));
        assertFalse(environment.containsKey("ACCESS_KEY_SECRET"));
        assertFalse(environment.containsKey("SAFE_VALUE"));
        assertFalse(environment.containsKey("PATH"));
        assertEquals("safe-proxy", environment.get("https_proxy"));
    }

    @Test
    public void preservesInterruptionAndStopsTheExchange()
            throws Exception {
        CliProcessRunner runner = fixtureRunner(
                "sleep",
                Duration.ofSeconds(30),
                4096,
                4096
        );
        AtomicReference<CliProcessException> failure =
                new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                runner.execute(new byte[0]);
            } catch (CliProcessException exception) {
                failure.set(exception);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });

        worker.start();
        Thread.sleep(300);
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive());
        assertEquals(
                CliProcessException.Reason.INTERRUPTED,
                failure.get().getReason()
        );
        assertTrue(interrupted.get());
    }

    private static CliProcessRunner fixtureRunner(
            String mode,
            Duration timeout,
            int stdoutLimit,
            int stderrLimit,
            String... extraJvmArguments
    ) {
        List<String> launcher = fixtureLauncher(
                mode,
                extraJvmArguments
        );
        return new CliProcessRunner(
                launcher,
                timeout,
                stdoutLimit,
                stderrLimit
        );
    }

    private static List<String> fixtureLauncher(
            String mode,
            String... extraJvmArguments
    ) {
        String executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toString();
        List<String> launcher = new ArrayList<>();
        launcher.add(executable);
        launcher.add("-Dlocker.fixture.mode=" + mode);
        launcher.addAll(List.of(extraJvmArguments));
        launcher.add("-cp");
        launcher.add(System.getProperty("java.class.path"));
        launcher.add(CliProcessFixture.class.getName());
        return launcher;
    }

    private static boolean awaitProcessExit(
            long processId,
            Duration timeout
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            boolean alive = ProcessHandle.of(processId)
                    .map(CliProcessRunner::isEffectivelyAlive)
                    .orElse(false);
            if (!alive) {
                return true;
            }
            Thread.sleep(25);
        }
        return ProcessHandle.of(processId)
                .map(handle -> !CliProcessRunner.isEffectivelyAlive(handle))
                .orElse(true);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase()
                .contains("win");
    }
}
