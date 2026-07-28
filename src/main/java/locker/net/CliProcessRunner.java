package locker.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Executes one bounded Locker SDK protocol exchange.
 *
 * <p>The production launcher contains only the Locker binary. This class adds
 * the single non-sensitive {@code sdk} argument and sends the request through
 * stdin. The list-based constructor exists only so hermetic tests can launch a
 * Java fixture without a platform-specific shell script.
 */
final class CliProcessRunner {
    static final int MAX_REQUEST_BYTES = 20 << 20;
    static final int DEFAULT_MAX_STDOUT_BYTES = 20 << 20;
    static final int DEFAULT_MAX_STDERR_BYTES = 256 << 10;
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration TERMINATION_GRACE = Duration.ofMillis(250);
    private static final Duration FORCE_TERMINATION_GRACE =
            Duration.ofSeconds(2);
    private static final long PROCESS_TREE_POLL_MILLIS = 10;
    private static final Set<String> SAFE_ENVIRONMENT_NAMES = new HashSet<>(
            Arrays.asList(
                    "ALL_PROXY",
                    "APPDATA",
                    "COMSPEC",
                    "HOME",
                    "HOMEDRIVE",
                    "HOMEPATH",
                    "HTTP_PROXY",
                    "HTTPS_PROXY",
                    "LANG",
                    "LANGUAGE",
                    "LOCALAPPDATA",
                    "LOGNAME",
                    "NO_PROXY",
                    "SSL_CERT_DIR",
                    "SSL_CERT_FILE",
                    "SYSTEMROOT",
                    "TEMP",
                    "TMP",
                    "TMPDIR",
                    "TZ",
                    "USER",
                    "USERNAME",
                    "USERPROFILE",
                    "WINDIR"
            )
    );

    private final List<String> launcher;
    private final Duration timeout;
    private final int maxStdoutBytes;
    private final int maxStderrBytes;

    CliProcessRunner(String binaryPath, Duration timeout) {
        this(
                List.of(requireBinaryPath(binaryPath)),
                timeout,
                DEFAULT_MAX_STDOUT_BYTES,
                DEFAULT_MAX_STDERR_BYTES
        );
    }

    CliProcessRunner(
            List<String> launcher,
            Duration timeout,
            int maxStdoutBytes,
            int maxStderrBytes
    ) {
        if (launcher == null || launcher.isEmpty()) {
            throw new IllegalArgumentException("CLI launcher must not be empty");
        }
        for (String argument : launcher) {
            if (argument == null || argument.isBlank()) {
                throw new IllegalArgumentException("CLI launcher contains an empty argument");
            }
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("CLI timeout must be positive");
        }
        if (maxStdoutBytes <= 0
                || maxStdoutBytes > DEFAULT_MAX_STDOUT_BYTES
                || maxStderrBytes <= 0) {
            throw new IllegalArgumentException("CLI output limits must be positive");
        }

        this.launcher = List.copyOf(launcher);
        this.timeout = timeout;
        this.maxStdoutBytes = maxStdoutBytes;
        this.maxStderrBytes = maxStderrBytes;
    }

    Result execute(byte[] request) throws CliProcessException {
        return execute(request, maxStdoutBytes);
    }

    String executableIdentity() throws CliProcessException {
        Path executable = resolveExecutable(launcher.get(0));
        try {
            Path canonical = executable.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(
                    canonical,
                    BasicFileAttributes.class
            );
            if (!attributes.isRegularFile()) {
                throw new IOException("CLI executable is not a regular file");
            }
            Object fileKey = attributes.fileKey();
            return canonical
                    + ":"
                    + (fileKey == null ? "unknown" : fileKey)
                    + ":"
                    + attributes.size()
                    + ":"
                    + attributes.creationTime().toMillis()
                    + ":"
                    + attributes.lastModifiedTime().toMillis();
        } catch (IOException exception) {
            throw new CliProcessException(
                    CliProcessException.Reason.START_FAILED,
                    "Locker CLI executable identity is unavailable",
                    exception
            );
        }
    }

    Result execute(
            byte[] request,
            int maxResponseBytes
    ) throws CliProcessException {
        if (request == null) {
            throw new IllegalArgumentException("Protocol request must not be null");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException(
                    "Protocol response limit must be positive"
            );
        }
        if (request.length > MAX_REQUEST_BYTES) {
            throw new CliProcessException(
                    CliProcessException.Reason.REQUEST_TOO_LARGE,
                    "Locker SDK protocol request exceeds 20 MiB"
            );
        }

        Path executable = resolveExecutable(launcher.get(0));
        List<String> command = new ArrayList<>(launcher);
        command.set(0, executable.toString());
        command.add("sdk");
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        sanitizeEnvironment(processBuilder.environment());

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException exception) {
            throw new CliProcessException(
                    CliProcessException.Reason.START_FAILED,
                    "Unable to start the Locker CLI",
                    exception
            );
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                4,
                new DaemonThreadFactory()
        );
        ProcessTreeTracker processTree = new ProcessTreeTracker(process);
        Future<?> processTreeFuture = executor.submit(processTree);
        Future<BoundedBytes> stdoutFuture = executor.submit(
                readBounded(
                        process.getInputStream(),
                        Math.min(maxStdoutBytes, maxResponseBytes)
                )
        );
        Future<BoundedBytes> stderrFuture = executor.submit(
                readBounded(process.getErrorStream(), maxStderrBytes)
        );
        Future<Void> stdinFuture = executor.submit(
                writeRequest(process.getOutputStream(), request)
        );

        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            long waitNanos = remainingNanos(deadline);
            if (!process.waitFor(waitNanos, TimeUnit.NANOSECONDS)) {
                terminateTree(process, processTree);
                throw new CliProcessException(
                        CliProcessException.Reason.TIMEOUT,
                        "Locker CLI protocol request timed out"
                );
            }

            await(stdinFuture, deadline, "write Locker CLI protocol request");
            BoundedBytes stdout = await(
                    stdoutFuture,
                    deadline,
                    "read Locker CLI protocol response"
            );
            BoundedBytes stderr = await(
                    stderrFuture,
                    deadline,
                    "read Locker CLI diagnostics"
            );

            if (stdout.overflow) {
                throw new CliProcessException(
                        CliProcessException.Reason.OUTPUT_LIMIT,
                        "Locker CLI protocol response exceeds the output limit"
                );
            }
            if (stderr.overflow) {
                throw new CliProcessException(
                        CliProcessException.Reason.OUTPUT_LIMIT,
                        "Locker CLI diagnostics exceed the output limit"
                );
            }
            if (processTree.hasAliveDescendants()
                    && !terminateTree(process, processTree)) {
                throw new CliProcessException(
                        CliProcessException.Reason.IO_FAILURE,
                        "Locker CLI descendant termination "
                                + "could not be verified"
                );
            }
            return new Result(process.exitValue(), stdout.bytes, stderr.bytes);
        } catch (InterruptedException exception) {
            terminateTree(process, processTree);
            Thread.currentThread().interrupt();
            throw new CliProcessException(
                    CliProcessException.Reason.INTERRUPTED,
                    "Locker CLI protocol request was interrupted",
                    exception
            );
        } catch (TimeoutException exception) {
            terminateTree(process, processTree);
            throw new CliProcessException(
                    CliProcessException.Reason.TIMEOUT,
                    "Locker CLI protocol request timed out",
                    exception
            );
        } catch (ExecutionException exception) {
            terminateTree(process, processTree);
            Throwable cause = exception.getCause();
            throw new CliProcessException(
                    CliProcessException.Reason.IO_FAILURE,
                    "Locker CLI protocol transport failed",
                    cause == null ? exception : cause
            );
        } finally {
            if (process.isAlive() || processTree.hasAliveDescendants()) {
                terminateTree(process, processTree);
            }
            processTree.stop();
            processTreeFuture.cancel(true);
            executor.shutdownNow();
        }
    }

    private static <T> T await(
            Future<T> future,
            long deadline,
            String operation
    ) throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = remainingNanos(deadline);
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            throw new TimeoutException(operation);
        }
    }

    private static long remainingNanos(long deadline) throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("Locker CLI protocol deadline elapsed");
        }
        return remaining;
    }

    private static Callable<Void> writeRequest(OutputStream stream, byte[] request) {
        return () -> {
            try (OutputStream output = stream) {
                output.write(request);
                output.flush();
            }
            return null;
        };
    }

    private static Callable<BoundedBytes> readBounded(InputStream stream, int limit) {
        return () -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream(
                    Math.min(limit, 8192)
            );
            boolean overflow = false;
            byte[] buffer = new byte[8192];
            try (InputStream input = stream) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    int remaining = limit - output.size();
                    if (remaining > 0) {
                        output.write(buffer, 0, Math.min(read, remaining));
                    }
                    if (read > remaining) {
                        overflow = true;
                    }
                }
            }
            return new BoundedBytes(output.toByteArray(), overflow);
        };
    }

    static void sanitizeEnvironment(Map<String, String> environment) {
        List<String> unsafeKeys = environment.keySet().stream()
                .filter(key -> !isSafeEnvironmentName(key))
                .collect(Collectors.toList());
        for (String key : unsafeKeys) {
            environment.remove(key);
        }
    }

    private static boolean isSafeEnvironmentName(String key) {
        String normalized = key.toUpperCase(Locale.ROOT);
        if (SAFE_ENVIRONMENT_NAMES.contains(normalized)
                || normalized.startsWith("LC_")) {
            return true;
        }
        return false;
    }

    private static boolean terminateTree(
            Process process,
            ProcessTreeTracker processTree
    ) {
        boolean interrupted = Thread.interrupted();
        ProcessHandle root = process.toHandle();

        processTree.capture();
        if (isEffectivelyAlive(root)) {
            root.destroy();
        }
        processTree.destroyKnown(false);
        interrupted |= waitForTreeExit(
                root,
                processTree,
                TERMINATION_GRACE,
                false
        );

        processTree.capture();
        if (isEffectivelyAlive(root)) {
            root.destroyForcibly();
        }
        processTree.destroyKnown(true);
        interrupted |= waitForTreeExit(
                root,
                processTree,
                FORCE_TERMINATION_GRACE,
                true
        );

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        processTree.capture();
        return !isEffectivelyAlive(root)
                && !processTree.hasAliveDescendants();
    }

    private static boolean waitForTreeExit(
            ProcessHandle root,
            ProcessTreeTracker processTree,
            Duration grace,
            boolean force
    ) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + grace.toNanos();
        while (isEffectivelyAlive(root)
                || processTree.hasAliveDescendants()) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            processTree.capture();
            if (force) {
                if (isEffectivelyAlive(root)) {
                    root.destroyForcibly();
                }
                processTree.destroyKnown(true);
            }
            try {
                Thread.sleep(PROCESS_TREE_POLL_MILLIS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        return interrupted;
    }

    static boolean isEffectivelyAlive(ProcessHandle handle) {
        if (!handle.isAlive()) {
            return false;
        }
        if (!System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("linux")) {
            return true;
        }
        try {
            String stat = Files.readString(
                    Path.of("/proc", Long.toString(handle.pid()), "stat"),
                    java.nio.charset.StandardCharsets.US_ASCII
            );
            int commandEnd = stat.lastIndexOf(')');
            if (commandEnd >= 0
                    && commandEnd + 2 < stat.length()
                    && stat.charAt(commandEnd + 1) == ' ') {
                char state = stat.charAt(commandEnd + 2);
                return state != 'Z' && state != 'X';
            }
        } catch (IOException | SecurityException ignored) {
            // Unknown process state remains alive so cleanup fails closed.
        }
        return handle.isAlive();
    }

    private static String requireBinaryPath(String binaryPath) {
        if (binaryPath == null || binaryPath.isBlank()) {
            throw new IllegalArgumentException("Locker CLI path must not be empty");
        }
        return binaryPath;
    }

    private static Path resolveExecutable(
            String executable
    ) throws CliProcessException {
        Path configured = Paths.get(executable)
                .toAbsolutePath()
                .normalize();
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    configured,
                    BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isRegularFile()
                    || attributes.isSymbolicLink()
                    || (!isWindows()
                    && !Files.isExecutable(configured))) {
                throw new IOException(
                        "Configured CLI is not an executable regular file"
                );
            }
            return configured;
        } catch (IOException exception) {
            throw new CliProcessException(
                    CliProcessException.Reason.START_FAILED,
                    "Configured Locker CLI executable is unavailable",
                    exception
            );
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
    }

    static final class Result {
        private final int exitCode;
        private final byte[] stdout;
        private final byte[] stderr;

        Result(int exitCode, byte[] stdout, byte[] stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout.clone();
            this.stderr = stderr.clone();
        }

        int getExitCode() {
            return exitCode;
        }

        byte[] getStdout() {
            return stdout.clone();
        }

        byte[] getStderr() {
            return stderr.clone();
        }

        void clear() {
            Arrays.fill(stdout, (byte) 0);
            Arrays.fill(stderr, (byte) 0);
        }
    }

    private static final class BoundedBytes {
        private final byte[] bytes;
        private final boolean overflow;

        private BoundedBytes(byte[] bytes, boolean overflow) {
            this.bytes = bytes;
            this.overflow = overflow;
        }
    }

    /**
     * Repeatedly records descendants while the root and known children are
     * alive. Java 11 has no portable process-group or Windows Job Object API,
     * so this preserves handles after re-parenting and lets cleanup verify
     * that every recorded process exited. A child that spawns and re-parents
     * entirely between samples remains outside the standard-library model.
     */
    private static final class ProcessTreeTracker implements Runnable {
        private final ProcessHandle root;
        private final Set<ProcessHandle> known =
                ConcurrentHashMap.newKeySet();
        private volatile boolean running = true;

        private ProcessTreeTracker(Process process) {
            this.root = process.toHandle();
            capture();
        }

        @Override
        public void run() {
            while (running
                    && (isEffectivelyAlive(root)
                    || hasAliveDescendants())) {
                capture();
                try {
                    Thread.sleep(PROCESS_TREE_POLL_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            capture();
        }

        private void capture() {
            List<ProcessHandle> parents = new ArrayList<>(known);
            parents.add(root);
            for (ProcessHandle parent : parents) {
                if (!isEffectivelyAlive(parent)) {
                    continue;
                }
                try (Stream<ProcessHandle> descendants =
                             parent.descendants()) {
                    descendants.forEach(known::add);
                } catch (SecurityException
                         | UnsupportedOperationException ignored) {
                    // The later root termination remains the portable fallback.
                }
            }
        }

        private boolean hasAliveDescendants() {
            capture();
            return known.stream().anyMatch(
                    CliProcessRunner::isEffectivelyAlive
            );
        }

        private void destroyKnown(boolean force) {
            capture();
            for (ProcessHandle descendant : known) {
                if (!isEffectivelyAlive(descendant)) {
                    continue;
                }
                if (force) {
                    descendant.destroyForcibly();
                } else {
                    descendant.destroy();
                }
            }
        }

        private void stop() {
            running = false;
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(
                    runnable,
                    "locker-cli-protocol-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        }
    }
}
