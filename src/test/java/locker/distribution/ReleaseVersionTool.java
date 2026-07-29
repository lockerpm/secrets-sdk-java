package locker.distribution;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;

/**
 * Test-scope deterministic release-version and remote-tag preflight utility.
 *
 * <p>CI invokes this class from a full-history protected-main checkout before
 * it builds or uploads release artifacts.
 */
public final class ReleaseVersionTool {
    private static final Pattern COMMIT = Pattern.compile(
            "^(?:[0-9a-f]{40}|[0-9a-f]{64})$"
    );
    private static final Pattern VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "\\.(0|[1-9][0-9]*)$"
    );
    private static final Pattern REMOTE = Pattern.compile(
            "^[A-Za-z0-9._-]+$"
    );
    private static final Pattern INTEGER = Pattern.compile(
            "^(?:0|[1-9][0-9]*)$"
    );
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_GIT_OUTPUT_BYTES = 8 * 1024 * 1024;
    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final int PREDECESSOR_ATTEMPTS = 720;
    private static final long PREDECESSOR_POLL_MILLISECONDS = 5_000;
    private static final Duration PREDECESSOR_WAIT =
            Duration.ofHours(1);

    private ReleaseVersionTool() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments == null || arguments.length < 1) {
            throw new IllegalArgumentException(
                    "Expected prepare, verify-remote-tag, "
                            + "or wait-predecessor-tag"
            );
        }
        switch (arguments[0]) {
            case "prepare":
                if (arguments.length != 5) {
                    throw new IllegalArgumentException(
                            "Expected prepare <repository> <commit> "
                                    + "<policy> <dotenv-output>"
                    );
                }
                Release release = prepare(
                        Path.of(arguments[1]),
                        arguments[2],
                        Path.of(arguments[3]),
                        Path.of(arguments[4])
                );
                System.out.println(
                        "Prepared Locker Java SDK " + release.version
                                + " (" + release.tag
                                + ", first-parent distance "
                                + release.firstParentDistance + ")"
                );
                return;
            case "verify-remote-tag":
                if (arguments.length != 5) {
                    throw new IllegalArgumentException(
                            "Expected verify-remote-tag <repository> "
                                    + "<remote> <tag> <commit>"
                    );
                }
                verifyRemoteTag(
                        Path.of(arguments[1]),
                        arguments[2],
                        arguments[3],
                        arguments[4]
                );
                System.out.println(
                        "Remote release-tag preflight passed for "
                                + arguments[3]
                );
                return;
            case "wait-predecessor-tag":
                if (arguments.length != 5) {
                    throw new IllegalArgumentException(
                            "Expected wait-predecessor-tag <repository> "
                                    + "<remote> <commit> <policy>"
                    );
                }
                waitForPredecessorTag(
                        Path.of(arguments[1]),
                        arguments[2],
                        arguments[3],
                        Path.of(arguments[4]),
                        PREDECESSOR_ATTEMPTS,
                        Thread::sleep
                );
                System.out.println(
                        "Predecessor release tag is ready for "
                                + arguments[3]
                );
                return;
            default:
                throw new IllegalArgumentException(
                        "Unknown release command: " + arguments[0]
                );
        }
    }

    static Release prepare(
            Path repository,
            String commit,
            Path policyPath,
            Path output
    ) throws Exception {
        Path root = repository.toAbsolutePath().normalize();
        requireCommit(commit, "release commit");
        Policy policy = readPolicy(policyPath);
        String baseVersion = readBaseVersion(root.resolve("pom.xml"));

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
        List<String> history = releaseHistory(
                root,
                commit,
                policy
        );

        String version = deriveVersion(
                baseVersion,
                history.size(),
                policy.firstReleaseDistance
        );
        validateReleaseLineBase(
                root,
                baseVersion,
                history,
                policy.firstReleaseDistance
        );
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

        Release release = new Release(
                version,
                "v" + version,
                history.size(),
                sourceDateEpoch
        );
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

    static String deriveVersion(
            String baseVersion,
            int distance,
            int firstReleaseDistance
    ) {
        Matcher match = VERSION.matcher(baseVersion);
        if (!match.matches()
                || firstReleaseDistance != 1
                || distance < firstReleaseDistance) {
            throw new IllegalArgumentException(
                    "Release version inputs are invalid"
            );
        }
        int major = Integer.parseInt(match.group(1));
        int minor = Integer.parseInt(match.group(2));
        int patch = Math.addExact(
                Integer.parseInt(match.group(3)),
                distance - firstReleaseDistance
        );
        return major + "." + minor + "." + patch;
    }

    static void verifyRemoteTag(
            Path repository,
            String remote,
            String tag,
            String commit
    ) throws Exception {
        requireCommit(commit, "release commit");
        if (!REMOTE.matcher(remote).matches()
                || !tag.equals("v" + requireVersion(
                tag.startsWith("v") ? tag.substring(1) : ""
        ))) {
            throw new IllegalArgumentException(
                    "Remote tag preflight inputs are invalid"
            );
        }
        String output = queryRemoteTag(
                repository.toAbsolutePath().normalize(),
                remote,
                tag
        );
        String existing = parseRemoteTag(output, tag);
        if (existing != null && !existing.equals(commit)) {
            throw new IOException(
                    "Remote release tag already points to another commit"
            );
        }
    }

    static void waitForPredecessorTag(
            Path repository,
            String remote,
            String commit,
            Path policyPath,
            int attempts,
            Sleeper sleeper
    ) throws Exception {
        Path root = repository.toAbsolutePath().normalize();
        requireCommit(commit, "release commit");
        if (!REMOTE.matcher(remote).matches()) {
            throw new IllegalArgumentException(
                    "Remote name is invalid"
            );
        }
        Policy policy = readPolicy(policyPath);
        String baseVersion = readBaseVersion(root.resolve("pom.xml"));
        List<String> history = releaseHistory(
                root,
                commit,
                policy
        );
        if (history.size() == policy.firstReleaseDistance) {
            return;
        }
        String predecessorVersion = deriveVersion(
                baseVersion,
                history.size() - 1,
                policy.firstReleaseDistance
        );
        String predecessorTag = "v" + predecessorVersion;
        String[] predecessorFields = history
                .get(history.size() - 2)
                .split("\\s+");
        String predecessorCommit = predecessorFields[0];
        requireCommit(
                predecessorCommit,
                "predecessor release commit"
        );
        awaitPredecessorTag(
                predecessorTag,
                predecessorCommit,
                attempts,
                sleeper,
                () -> queryRemoteTag(
                        root,
                        remote,
                        predecessorTag
                )
        );
    }

    static void awaitPredecessorTag(
            String tag,
            String expectedCommit,
            int attempts,
            Sleeper sleeper,
            RemoteTagReader reader
    ) throws Exception {
        if (!tag.equals("v" + requireVersion(
                tag.startsWith("v") ? tag.substring(1) : ""
        ))
                || attempts < 1
                || attempts > PREDECESSOR_ATTEMPTS
                || sleeper == null
                || reader == null) {
            throw new IllegalArgumentException(
                    "Predecessor tag wait inputs are invalid"
            );
        }
        requireCommit(
                expectedCommit,
                "predecessor release commit"
        );
        long deadline = System.nanoTime()
                + PREDECESSOR_WAIT.toNanos();
        Exception lastTransportFailure = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            String output;
            try {
                output = reader.read();
                lastTransportFailure = null;
            } catch (InterruptedException exception) {
                throw exception;
            } catch (Exception exception) {
                lastTransportFailure = exception;
                output = null;
            }
            if (output != null) {
                String actual = parseRemoteTag(output, tag);
                if (actual != null) {
                    if (!expectedCommit.equals(actual)) {
                        throw new IOException(
                                "Predecessor release tag points to "
                                        + "another commit"
                        );
                    }
                    return;
                }
            }
            if (attempt + 1 < attempts) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                sleeper.sleep(Math.min(
                        PREDECESSOR_POLL_MILLISECONDS,
                        TimeUnit.NANOSECONDS.toMillis(remaining)
                ));
            }
        }
        if (lastTransportFailure != null) {
            throw new IOException(
                    "Cannot query the predecessor release tag "
                            + "within the bounded wait",
                    lastTransportFailure
            );
        }
        throw new IOException(
                "Predecessor release tag is not visible within "
                        + "the bounded wait"
        );
    }

    static String parseRemoteTag(String output, String tag)
            throws IOException {
        Map<String, String> values = new HashMap<>();
        Set<String> expected = Set.of(
                "refs/tags/" + tag,
                "refs/tags/" + tag + "^{}"
        );
        for (String line : nonemptyLines(output)) {
            String[] fields = line.split("\\s+");
            if (fields.length != 2
                    || !COMMIT.matcher(fields[0]).matches()
                    || !expected.contains(fields[1])
                    || values.put(fields[1], fields[0]) != null) {
                throw new IOException(
                        "Git returned invalid remote tag data"
                );
            }
        }
        if (values.isEmpty()) {
            return null;
        }
        String direct = values.get("refs/tags/" + tag);
        if (direct == null) {
            throw new IOException(
                    "Git returned incomplete remote tag data"
            );
        }
        return values.getOrDefault(
                "refs/tags/" + tag + "^{}",
                direct
        );
    }

    private static String queryRemoteTag(
            Path repository,
            String remote,
            String tag
    ) throws Exception {
        return git(
                repository,
                "ls-remote",
                "--tags",
                remote,
                "refs/tags/" + tag,
                "refs/tags/" + tag + "^{}"
        );
    }

    private static List<String> releaseHistory(
            Path repository,
            String commit,
            Policy policy
    ) throws Exception {
        git(
                repository,
                "cat-file",
                "-e",
                policy.baselineCommit + "^{commit}"
        );
        git(repository, "cat-file", "-e", commit + "^{commit}");
        GitResult ancestor = gitResult(
                repository,
                "merge-base",
                "--is-ancestor",
                policy.baselineCommit,
                commit
        );
        if (ancestor.exitCode != 0) {
            throw new IOException(
                    "Release commit is not descended from the policy baseline"
            );
        }
        String historyText = git(
                repository,
                "rev-list",
                "--first-parent",
                "--reverse",
                "--parents",
                policy.baselineCommit + ".." + commit
        );
        List<String> history = nonemptyLines(historyText);
        if (history.size() < policy.firstReleaseDistance) {
            throw new IOException(
                    "Release commit predates the first releasable merge"
            );
        }
        for (int index = 0; index < history.size(); index++) {
            String[] commitAndParents = history.get(index).split("\\s+");
            if (commitAndParents.length != 3) {
                throw new IOException(
                        "Every release-line commit must be a two-parent "
                                + "merge commit; direct, fast-forward, "
                                + "squash, and rebase updates are forbidden"
                );
            }
            if (index == 0
                    && !policy.baselineCommit.equals(
                    commitAndParents[1]
            )) {
                throw new IOException(
                        "The policy baseline must be the first "
                                + "release merge's immediate first parent"
                );
            }
        }
        return history;
    }

    private static Policy readPolicy(Path path) throws Exception {
        byte[] bytes = readRegularFile(
                path,
                MAX_FILE_BYTES,
                "Release policy"
        );
        try {
            JsonElement parsed = StrictJson.parse(bytes, 8);
            if (!parsed.isJsonObject()) {
                throw new IOException(
                        "Release policy must be a JSON object"
                );
            }
            JsonObject object = parsed.getAsJsonObject();
            if (!object.keySet().equals(Set.of(
                    "schema_version",
                    "baseline_commit",
                    "first_release_distance",
                    "mainline_mode"
            ))) {
                throw new IOException(
                        "Release policy fields are invalid"
                );
            }
            int schema = requireInteger(object, "schema_version");
            int firstDistance = requireInteger(
                    object,
                    "first_release_distance"
            );
            String baseline = requireString(
                    object,
                    "baseline_commit"
            );
            String mode = requireString(object, "mainline_mode");
            if (schema != 1
                    || firstDistance != 1
                    || !"merge_commit".equals(mode)
                    || !COMMIT.matcher(baseline).matches()) {
                throw new IOException(
                        "Release policy values are invalid"
                );
            }
            return new Policy(baseline, firstDistance);
        } catch (CliDistributionException exception) {
            throw new IOException(
                    "Release policy is invalid",
                    exception
            );
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String readBaseVersion(Path pomPath)
            throws Exception {
        byte[] bytes = readRegularFile(
                pomPath,
                MAX_FILE_BYTES,
                "Maven POM"
        );
        DocumentBuilderFactory factory =
                DocumentBuilderFactory.newInstance();
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl",
                true
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-general-entities",
                false
        );
        factory.setFeature(
                "http://xml.org/sax/features/external-parameter-entities",
                false
        );
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try {
            Document pom = factory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(bytes)
            );
            javax.xml.xpath.XPath xpath =
                    XPathFactory.newInstance().newXPath();
            String project = "/*[local-name()='project']";
            String projectVersion = (String) xpath.evaluate(
                    project + "/*[local-name()='version']/text()",
                    pom,
                    XPathConstants.STRING
            );
            String revision = (String) xpath.evaluate(
                    project + "/*[local-name()='properties']"
                            + "/*[local-name()='revision']/text()",
                    pom,
                    XPathConstants.STRING
            );
            if (!"${revision}".equals(projectVersion.trim())) {
                throw new IOException(
                        "Maven project version must use ${revision}"
                );
            }
            return requireVersion(revision.trim());
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void validateReleaseLineBase(
            Path repository,
            String baseVersion,
            List<String> history,
            int firstReleaseDistance
    ) throws Exception {
        String tag = "v" + baseVersion;
        GitResult result = gitResult(
                repository,
                "rev-parse",
                "--verify",
                "--quiet",
                "refs/tags/" + tag + "^{commit}"
        );
        String taggedCommit = null;
        if (result.exitCode == 0) {
            taggedCommit = result.output.trim();
            requireCommit(taggedCommit, "base release tag");
        } else if (result.exitCode != 1) {
            throw new IOException(
                    "Cannot inspect the base release tag"
            );
        }
        String firstReleaseCommit = history.get(0)
                .split("\\s+")[0];
        if (taggedCommit == null
                && history.size() != firstReleaseDistance) {
            throw new IOException(
                    "Base tag " + tag + " is missing; the policy "
                            + "baseline must be the first release merge's "
                            + "immediate parent"
            );
        }
        if (taggedCommit != null
                && !taggedCommit.equals(firstReleaseCommit)) {
            throw new IOException(
                    "Base tag " + tag
                            + " does not point to the first release merge"
            );
        }
    }

    private static int requireInteger(
            JsonObject object,
            String name
    ) throws IOException {
        JsonElement element = object.get(name);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException(
                    "Release policy " + name + " must be an integer"
            );
        }
        try {
            String value = element.getAsString();
            if (!INTEGER.matcher(value).matches()) {
                throw new NumberFormatException(
                        "not a canonical nonnegative integer"
                );
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Release policy " + name + " must be an integer",
                    exception
            );
        }
    }

    private static String requireString(
            JsonObject object,
            String name
    ) throws IOException {
        JsonElement element = object.get(name);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IOException(
                    "Release policy " + name + " must be a string"
            );
        }
        String value = element.getAsString();
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IOException(
                    "Release policy " + name + " is invalid"
            );
        }
        return value;
    }

    private static String requireVersion(String value) {
        if (!VERSION.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Release base version must be stable SemVer"
            );
        }
        return value;
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

    private static byte[] readRegularFile(
            Path path,
            int maximum,
            String label
    ) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile()
                || attributes.size() < 1
                || attributes.size() > maximum) {
            throw new IOException(
                    label + " must be a bounded regular non-symlink file"
            );
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length != attributes.size()) {
            Arrays.fill(bytes, (byte) 0);
            throw new IOException(label + " changed while being read");
        }
        return bytes;
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

    private static List<String> nonemptyLines(String value) {
        List<String> result = new ArrayList<>();
        for (String line : value.replace("\r\n", "\n").split("\n")) {
            if (!line.isBlank()) {
                result.add(line.trim());
            }
        }
        return result;
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

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    @FunctionalInterface
    interface RemoteTagReader {
        String read() throws Exception;
    }

    static final class Release {
        final String version;
        final String tag;
        final int firstParentDistance;
        final long sourceDateEpoch;

        Release(
                String version,
                String tag,
                int firstParentDistance,
                long sourceDateEpoch
        ) {
            this.version = version;
            this.tag = tag;
            this.firstParentDistance = firstParentDistance;
            this.sourceDateEpoch = sourceDateEpoch;
        }
    }

    private static final class Policy {
        private final String baselineCommit;
        private final int firstReleaseDistance;

        private Policy(
                String baselineCommit,
                int firstReleaseDistance
        ) {
            this.baselineCommit = baselineCommit;
            this.firstReleaseDistance = firstReleaseDistance;
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
