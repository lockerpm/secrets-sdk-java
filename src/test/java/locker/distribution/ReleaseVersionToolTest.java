package locker.distribution;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReleaseVersionToolTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    public void derivesDeterministicPatchVersions() {
        assertEquals(
                "1.0.0",
                ReleaseVersionTool.deriveVersion("1.0.0", 1, 1)
        );
        assertEquals(
                "1.0.7",
                ReleaseVersionTool.deriveVersion("1.0.0", 8, 1)
        );
    }

    @Test
    public void preparesFirstReleaseFromOneMerge() throws Exception {
        Repository repository = repository();
        String baseline = repository.head();
        repository.checkoutNew("feature");
        Path policy = repository.writePolicy(baseline);
        repository.write("feature.txt", "feature\n");
        repository.commit("feature");
        repository.checkout(repository.mainBranch);
        repository.merge("feature", "merge feature");
        String releaseCommit = repository.head();
        Path output = repository.root.resolve("release.env");

        ReleaseVersionTool.Release release =
                ReleaseVersionTool.prepare(
                        repository.root,
                        releaseCommit,
                        policy,
                        output
                );

        assertEquals("1.0.0", release.version);
        assertEquals("v1.0.0", release.tag);
        assertEquals(1, release.firstParentDistance);
        assertEquals(
                "LOCKER_SDK_VERSION=1.0.0\n"
                        + "LOCKER_RELEASE_TAG=v1.0.0\n"
                        + "SOURCE_DATE_EPOCH="
                        + release.sourceDateEpoch + "\n",
                Files.readString(output, StandardCharsets.US_ASCII)
        );
    }

    @Test
    public void rejectsDirectMainCommit() throws Exception {
        Repository repository = repository();
        String baseline = repository.head();
        Path policy = repository.writePolicy(baseline);
        repository.write("direct.txt", "direct\n");
        repository.commit("direct main update");

        assertThrows(
                java.io.IOException.class,
                () -> ReleaseVersionTool.prepare(
                        repository.root,
                        repository.head(),
                        policy,
                        repository.root.resolve("release.env")
                )
        );
    }

    @Test
    public void rejectsBaselineIntroducedThroughSecondParent()
            throws Exception {
        Repository repository = repository();
        repository.checkoutNew("baseline-side");
        repository.write("baseline-side.txt", "baseline\n");
        repository.commit("side-branch baseline");
        String sideBranchBaseline = repository.head();
        repository.checkout(repository.mainBranch);
        repository.merge("baseline-side", "merge side baseline");
        Path policy = repository.writePolicy(sideBranchBaseline);

        assertThrows(
                java.io.IOException.class,
                () -> ReleaseVersionTool.prepare(
                        repository.root,
                        repository.head(),
                        policy,
                        repository.root.resolve("release.env")
                )
        );
    }

    @Test
    public void requiresBaseTagBeforeSecondRelease()
            throws Exception {
        Repository repository = repository();
        String baseline = repository.head();
        String firstRelease = repository.mergeFeature(
                "feature-one",
                "one.txt"
        );
        Path policy = repository.writePolicy(baseline);
        String secondRelease = repository.mergeFeature(
                "feature-two",
                "two.txt"
        );

        assertThrows(
                java.io.IOException.class,
                () -> ReleaseVersionTool.prepare(
                        repository.root,
                        secondRelease,
                        policy,
                        repository.root.resolve("release.env")
                )
        );

        repository.git("tag", "v1.0.0", firstRelease);
        ReleaseVersionTool.Release release =
                ReleaseVersionTool.prepare(
                        repository.root,
                        secondRelease,
                        policy,
                        repository.root.resolve("release.env")
                );
        assertEquals("1.0.1", release.version);
    }

    @Test
    public void parsesOnlyCanonicalRemoteTagOutput()
            throws Exception {
        String tag = "v1.2.3";
        String direct = "1".repeat(40);
        String peeled = "2".repeat(40);

        assertNull(ReleaseVersionTool.parseRemoteTag("", tag));
        assertEquals(
                peeled,
                ReleaseVersionTool.parseRemoteTag(
                        direct + "\trefs/tags/" + tag + "\n"
                                + peeled + "\trefs/tags/" + tag
                                + "^{}\n",
                        tag
                )
        );
        assertThrows(
                java.io.IOException.class,
                () -> ReleaseVersionTool.parseRemoteTag(
                        direct + "\trefs/heads/main\n",
                        tag
                )
        );
    }

    @Test
    public void missingPredecessorTagFailsAfterBoundedWait() {
        int[] reads = {0};
        int[] sleeps = {0};

        assertThrows(
                java.io.IOException.class,
                () -> ReleaseVersionTool.awaitPredecessorTag(
                        "v1.0.0",
                        "1".repeat(40),
                        2,
                        milliseconds -> sleeps[0]++,
                        () -> {
                            reads[0]++;
                            return "";
                        }
                )
        );

        assertEquals(2, reads[0]);
        assertEquals(1, sleeps[0]);
    }

    @Test
    public void wrongPredecessorTagCommitFailsImmediately() {
        int[] sleeps = {0};
        String tag = "v1.0.0";

        assertThrows(
                java.io.IOException.class,
                () -> ReleaseVersionTool.awaitPredecessorTag(
                        tag,
                        "1".repeat(40),
                        3,
                        milliseconds -> sleeps[0]++,
                        () -> "2".repeat(40)
                                + "\trefs/tags/" + tag + "\n"
                )
        );

        assertEquals(0, sleeps[0]);
    }

    @Test
    public void repositoryPolicyBaselineExists() throws Exception {
        assumeGit();
        Path root = Path.of("").toAbsolutePath().normalize();
        String baseline =
                "67b9a8f02802d817ec5e13f9ddbfd62be25c570b";
        Process result = new ProcessBuilder(
                "git",
                "-C",
                root.toString(),
                "cat-file",
                "-e",
                baseline + "^{commit}"
        ).start();

        assertTrue(result.waitFor() == 0);
    }

    private Repository repository() throws Exception {
        assumeGit();
        Path root = temporaryDirectory.resolve(
                "repository-" + System.nanoTime()
        );
        Files.createDirectories(root);
        Repository repository = new Repository(root);
        repository.git("init", "--quiet");
        repository.git("config", "user.name", "Locker Test");
        repository.git(
                "config",
                "user.email",
                "test@locker.invalid"
        );
        repository.write(
                "pom.xml",
                "<project>"
                        + "<modelVersion>4.0.0</modelVersion>"
                        + "<groupId>io.locker</groupId>"
                        + "<artifactId>lockersm</artifactId>"
                        + "<version>${revision}</version>"
                        + "<properties><revision>1.0.0</revision>"
                        + "</properties></project>"
        );
        repository.commit("baseline");
        repository.mainBranch = repository.git(
                "branch",
                "--show-current"
        );
        return repository;
    }

    private static void assumeGit() {
        try {
            Process process = new ProcessBuilder(
                    "git",
                    "--version"
            ).start();
            Assumptions.assumeTrue(process.waitFor() == 0);
        } catch (Exception exception) {
            Assumptions.assumeTrue(false, "Git is unavailable");
        }
    }

    private static final class Repository {
        private final Path root;
        private String mainBranch;

        private Repository(Path root) {
            this.root = root;
        }

        private String git(String... arguments) throws Exception {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.add("-C");
            command.add(root.toString());
            command.addAll(Arrays.asList(arguments));
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            ).trim();
            if (process.waitFor() != 0) {
                throw new IllegalStateException(output);
            }
            return output;
        }

        private void write(String name, String value)
                throws Exception {
            Path path = root.resolve(name);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(
                    path,
                    value,
                    StandardCharsets.UTF_8
            );
        }

        private void commit(String message) throws Exception {
            git("add", ".");
            git("commit", "--quiet", "-m", message);
        }

        private String head() throws Exception {
            return git("rev-parse", "HEAD");
        }

        private void checkoutNew(String branch) throws Exception {
            git("checkout", "--quiet", "-b", branch);
        }

        private void checkout(String branch) throws Exception {
            git("checkout", "--quiet", branch);
        }

        private void merge(String branch, String message)
                throws Exception {
            git(
                    "merge",
                    "--quiet",
                    "--no-ff",
                    branch,
                    "-m",
                    message
            );
        }

        private String mergeFeature(
                String branch,
                String filename
        ) throws Exception {
            checkoutNew(branch);
            write(filename, branch + "\n");
            commit(branch);
            checkout(mainBranch);
            merge(branch, "merge " + branch);
            return head();
        }

        private Path writePolicy(String baseline) throws Exception {
            Path path = root.resolve("scripts/release-policy.json");
            write(
                    "scripts/release-policy.json",
                    "{"
                            + "\"schema_version\":1,"
                            + "\"baseline_commit\":\"" + baseline + "\","
                            + "\"first_release_distance\":1,"
                            + "\"mainline_mode\":\"merge_commit\""
                            + "}"
            );
            return path;
        }
    }
}
