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
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ReleaseVersionToolTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    public void preparesFromTagIndependentOfPomAndRequiresMainAncestry()
            throws Exception {
        Repository repository = repository();
        String main = repository.head();
        Path output = repository.root.resolve("release.env");

        ReleaseVersionTool.Release release =
                ReleaseVersionTool.prepare(
                        repository.root,
                        "v9.9.9",
                        main,
                        output
                );

        assertEquals("9.9.9", release.version);
        assertEquals("v9.9.9", release.tag);
        assertEquals(
                "LOCKER_SDK_VERSION=9.9.9\n"
                        + "LOCKER_RELEASE_TAG=v9.9.9\n"
                        + "SOURCE_DATE_EPOCH="
                        + release.sourceDateEpoch + "\n",
                Files.readString(output, StandardCharsets.US_ASCII)
        );

        repository.checkoutNew("feature");
        repository.write("feature.txt", "feature\n");
        repository.commit("feature");
        String offMain = repository.head();

        assertThrows(
                java.io.IOException.class,
                () -> ReleaseVersionTool.prepare(
                        repository.root,
                        "v9.9.9",
                        offMain,
                        repository.root.resolve("other.env")
                )
        );
    }

    @Test
    public void rejectsMalformedTag() throws Exception {
        Repository repository = repository();
        String main = repository.head();

        assertThrows(
                IllegalArgumentException.class,
                () -> ReleaseVersionTool.prepare(
                        repository.root,
                        "9.9.9",
                        main,
                        repository.root.resolve("release.env")
                )
        );
    }

    private Repository repository() throws Exception {
        assumeGit();
        Path root = temporaryDirectory.resolve(
                "repository-" + System.nanoTime()
        );
        Path remoteRoot = temporaryDirectory.resolve(
                "remote-" + System.nanoTime()
        );
        Files.createDirectories(root);
        Repository repository = new Repository(root);
        repository.git("init", "--quiet", "-b", "main");
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
        String remote = remoteRoot.resolve("origin.git").toString();
        repository.git("init", "--quiet", "--bare", remote);
        repository.git("remote", "add", "origin", remote);
        repository.git("push", "--quiet", "origin", "main");
        repository.git("fetch", "--quiet", "origin");
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
    }
}
