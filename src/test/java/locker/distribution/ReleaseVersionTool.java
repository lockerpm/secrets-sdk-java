package locker.distribution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Test-scope deterministic release-version preflight utility.
 *
 * <p>CI invokes this class from a full-history checkout of the exact commit a
 * protected {@code vX.Y.Z} tag points to. The tag alone defines the release
 * version; this tool only has to prove the tagged commit is really part of
 * {@code main}'s history before any release artifact is built.
 */
public final class ReleaseVersionTool {
    private static final Pattern COMMIT = Pattern.compile(
            "^(?:[0-9a-f]{40}|[0-9a-f]{64})$"
    );
    private static final Pattern TAG = Pattern.compile(
            "^v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"
    );
    private static final int MAX_GIT_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);

    private ReleaseVersionTool() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments == null
                || arguments.length != 5
                || !"prepare".equals(arguments[0])) {
            throw new IllegalArgumentException(
                    "Expected prepare <repository> <tag> <commit> "
                            + "<dotenv-output>"
            );
        }
        Release release = prepare(
                Path.of(arguments[1]),
                arguments[2],
                arguments[3],
                Path.of(arguments[4])
        );
        System.out.println(
                "Prepared Locker Java SDK " + release.version
                        + " (" + release.tag + ")"
        );
    }

    static Release prepare(
            Path repository,
            String tag,
            String commit,
            Path output
    ) throws Exception {
        Path root = repository.toAbsolutePath().normalize();
        if (!TAG.matcher(tag).matches()) {
            throw new IllegalArgumentException(
                    "Release tag must match vMAJOR.MINOR.PATCH"
            );
        }
        requireCommit(commit, "release commit");

        if (!commit.equals(git(root, "rev-parse", "--verify", "HEAD"))) {
            throw new IOException(
                    "Release commit must exactly match checked-out HEAD"
            );
        }
        if (!git(
                root,
                "status",
                "--porcelain=v1",
                "--untracked-files=no"
        ).isBlank()) {
            throw new IOException(
                    "Release checkout contains tracked changes"
            );
        }
        String version = tag.substring(1);

        git(root, "cat-file", "-e", "refs/remotes/origin/main^{commit}");
        GitResult ancestor = gitResult(
                root,
                "merge-base",
                "--is-ancestor",
                commit,
                "refs/remotes/origin/main"
        );
        if (ancestor.exitCode != 0) {
            throw new IOException(
                    "Release commit is not part of the main history"
            );
        }

        String epochText = git(
                root,
                "show",
                "-s",
                "--format=%ct",
                commit
        );
        long sourceDateEpoch;
        try {
            sourceDateEpoch = Long.parseLong(epochText);
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Git returned an invalid source timestamp",
                    exception
            );
        }
        if (sourceDateEpoch < 1) {
            throw new IOException(
                    "Git returned an invalid source timestamp"
            );
        }

        Release release = new Release(version, tag, sourceDateEpoch);
        writeAtomically(
                output,
                (
                        "LOCKER_SDK_VERSION=" + release.version + "\n"
                                + "LOCKER_RELEASE_TAG=" + release.tag + "\n"
                                + "SOURCE_DATE_EPOCH="
                                + release.sourceDateEpoch + "\n"
                ).getBytes(StandardCharsets.US_ASCII)
        );
        return release;
    }

    private static void requireCommit(
            String value,
            String label
    ) {
        if (value == null || !COMMIT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    label + " must be a full hexadecimal object ID"
            );
        }
    }

    private static String git(
            Path repository,
            String... arguments
    ) throws Exception {
        GitResult result = gitResult(repository, arguments);
        if (result.exitCode != 0) {
            throw new IOException(
                    result.output.isBlank()
                            ? "Git command failed"
                            : result.output.trim()
            );
        }
        return result.output.trim();
    }

    private static GitResult gitResult(
            Path repository,
            String... arguments
    ) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(repository.toString());
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        ExecutorService readerExecutor =
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "locker-java-release-git-output"
                    );
                    thread.setDaemon(true);
                    return thread;
                });
        Future<byte[]> output = readerExecutor.submit(
                () -> readBounded(
                        process.getInputStream(),
                        MAX_GIT_OUTPUT_BYTES
                )
        );
        try {
            if (!process.waitFor(
                    GIT_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
            )) {
                process.destroyForcibly();
                throw new IOException("Git command timed out");
            }
            byte[] bytes;
            try {
                bytes = output.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException exception) {
                throw new IOException(
                        "Cannot read Git command output",
                        exception.getCause()
                );
            } catch (TimeoutException exception) {
                throw new IOException(
                        "Git output reader did not terminate",
                        exception
                );
            }
            try {
                return new GitResult(
                        process.exitValue(),
                        new String(bytes, StandardCharsets.UTF_8)
                );
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } finally {
            process.destroyForcibly();
            output.cancel(true);
            readerExecutor.shutdownNow();
        }
    }

    private static byte[] readBounded(
            InputStream input,
            int maximum
    ) throws IOException {
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total = Math.addExact(total, read);
                if (total > maximum) {
                    throw new IOException(
                            "Git output exceeds its size limit"
                    );
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void writeAtomically(
            Path output,
            byte[] bytes
    ) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException(
                    "Release environment output parent is unavailable"
            );
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(
                parent,
                ".release-env-",
                ".tmp"
        );
        boolean published = false;
        try {
            Files.write(
                    temporary,
                    bytes,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "Atomic release environment publication "
                                + "is unavailable",
                        exception
                );
            }
            published = true;
        } finally {
            Arrays.fill(bytes, (byte) 0);
            if (!published) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    static final class Release {
        final String version;
        final String tag;
        final long sourceDateEpoch;

        Release(
                String version,
                String tag,
                long sourceDateEpoch
        ) {
            this.version = version;
            this.tag = tag;
            this.sourceDateEpoch = sourceDateEpoch;
        }
    }

    private static final class GitResult {
        private final int exitCode;
        private final String output;

        private GitResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
