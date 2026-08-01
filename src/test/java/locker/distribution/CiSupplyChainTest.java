package locker.distribution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class CiSupplyChainTest {
    private static final String IMAGE =
            "maven:3.9.16-eclipse-temurin-11@sha256:"
                    + "1fee93ca227db7e8b8c7c72752ada0f03da6ebab40addd6fe48ac6293424186c";
    private static final Pattern MUTABLE_BOOTSTRAP =
            Pattern.compile("\\b(?:apt-get|apk\\s+add|curl|wget)\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void pipelinePinsBuildImageAndStrictChecksums() throws IOException {
        Path root = Path.of("").toAbsolutePath().normalize();
        String pipeline = readBounded(root.resolve(".gitlab-ci.yml"));
        String pom = readBounded(root.resolve("pom.xml"));

        assertTrue(pipeline.contains(IMAGE), "CI image must be immutable");
        assertTrue(
                pipeline.contains("--strict-checksums"),
                "Maven must fail when repository checksums are absent or invalid");
        assertFalse(
                MUTABLE_BOOTSTRAP.matcher(pipeline).find(),
                "CI must not bootstrap tools through mutable OS downloads");
        assertTrue(
                pipeline.contains("LOCKER_CLI_RELEASE_PUBLIC_KEY"),
                "release CI must require an independent CLI trust root");
        assertTrue(
                pipeline.contains("workflow:"),
                "release CI must define pipeline-level trigger rules");
        assertTrue(
                pipeline.contains("- if: '$CI_COMMIT_BRANCH'")
                        && pipeline.contains(
                        "CI_COMMIT_TAG =~ /^v(0|[1-9][0-9]*)"
                )
                        && pipeline.contains("- when: never"),
                "CI must validate every branch push and release only from a"
                        + " protected vX.Y.Z tag");
        assertFalse(
                pipeline.contains("CI_OPEN_MERGE_REQUESTS"),
                "open-merge-request scoped rules are not reviewed");
        assertTrue(
                pipeline.contains("CI_COMMIT_REF_PROTECTED"),
                "the release job must require a protected tag");
        assertTrue(
                pipeline.contains(".m2/release-${CI_JOB_ID}"),
                "release jobs must isolate their local Maven repositories");
        assertTrue(
                pipeline.contains("- cs_newgen_docker"),
                "all jobs must use the approved Docker runner");
        assertTrue(
                pipeline.contains("retry:\n    max: 2"),
                "release recovery must use only a bounded retry");
        assertTrue(
                pipeline.contains("- runner_system_failure")
                        && pipeline.contains("- script_failure")
                        && pipeline.contains(
                        "- stuck_or_timeout_failure"
                ),
                "release retry must cover ambiguous runner and script loss");
        assertTrue(
                pipeline.contains(
                        "resource_group: lockersm-maven-central"
                ),
                "release jobs must serialize through the Central resource group");
        assertFalse(
                pipeline.contains("(?:"),
                "GitLab rules use RE2 and must not contain PCRE non-capturing groups");
        assertTrue(
                pom.contains("<requireReleaseDeps>"),
                "Maven Enforcer must reject snapshot dependencies");
        assertTrue(
                pom.contains(
                        "<artifactId>flatten-maven-plugin</artifactId>"
                ),
                "published POMs must resolve CI-friendly version fields");
        assertTrue(
                pom.contains("<version>${revision}</version>"),
                "the Maven project must use one CI-friendly revision");
        assertFalse(pom.contains("-SNAPSHOT"), "the reviewed build graph must not contain snapshots");
    }

    private static String readBounded(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) {
            throw new IOException(path + " must be a regular non-link file");
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes.length > 2 * 1024 * 1024) {
            throw new IOException(path + " is empty or exceeds its input bound");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
