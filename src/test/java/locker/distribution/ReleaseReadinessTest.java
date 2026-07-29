package locker.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ReleaseReadinessTest {
    private static final String PUBLIC_KEY =
            "G2lcXttVEXeXdaCNb0mBMyXE6Llgw1vu9SDjFmk8d2s";
    private static final String OTHER_PUBLIC_KEY =
            java.util.Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            "x".repeat(32).getBytes(
                                    StandardCharsets.US_ASCII
                            )
                    );

    @TempDir
    Path temporaryDirectory;

    @Test
    public void acceptsExactReleaseMetadata() throws Exception {
        Path root = validProject();
        Path key = writeKey(PUBLIC_KEY);

        assertDoesNotThrow(() -> ReleaseReadinessVerifier.verify(
                root,
                "v1.0.0",
                key,
                "1.0.0"
        ));
    }

    @Test
    public void rejectsBlankOrMismatchedMainResource()
            throws Exception {
        Path root = validProject();
        Path key = writeKey(PUBLIC_KEY);
        Path sourceKey = sourceKey(root);
        Path compiledKey = compiledKey(root);

        Files.write(sourceKey, new byte[0]);
        assertThrows(
                CliDistributionException.class,
                () -> ReleaseReadinessVerifier.verify(
                        root,
                        "v1.0.0",
                        key,
                        "1.0.0"
                )
        );

        writeCanonicalKey(sourceKey, PUBLIC_KEY);
        writeCanonicalKey(compiledKey, OTHER_PUBLIC_KEY);
        assertThrows(
                CliDistributionException.class,
                () -> ReleaseReadinessVerifier.verify(
                        root,
                        "v1.0.0",
                        key,
                        "1.0.0"
                )
        );
    }

    @Test
    public void testClasspathCannotShadowCompiledTrustRoot()
            throws Exception {
        Path root = validProject();
        writeCanonicalKey(compiledKey(root), OTHER_PUBLIC_KEY);
        writeCanonicalKey(
                root.resolve("target")
                        .resolve("test-classes")
                        .resolve("locker-cli-ed25519-public-key.txt"),
                PUBLIC_KEY
        );

        assertThrows(
                CliDistributionException.class,
                () -> ReleaseReadinessVerifier.verify(
                        root,
                        "v1.0.0",
                        writeKey(PUBLIC_KEY),
                        "1.0.0"
                )
        );
    }

    @Test
    public void rejectsTagThatDoesNotMatchSdkVersion()
            throws Exception {
        assertThrows(
                CliDistributionException.class,
                () -> ReleaseReadinessVerifier.verify(
                        validProject(),
                        "v1.0.1",
                        writeKey(PUBLIC_KEY),
                        "1.0.0"
                )
        );
    }

    @Test
    public void rejectsEmptyLicense() throws Exception {
        Path root = temporaryDirectory.resolve("empty-license");
        Files.createDirectories(root);
        Files.write(root.resolve("LICENSE"), new byte[0]);

        assertThrows(
                CliDistributionException.class,
                () -> ReleaseReadinessVerifier.verify(
                        root,
                        "v1.0.0",
                        writeKey(PUBLIC_KEY),
                        "1.0.0"
                )
        );
    }

    @Test
    @EnabledIfSystemProperty(
            named = "locker.release.validation",
            matches = "true"
    )
    public void validatesProtectedCiReleaseInputs() throws Exception {
        String expectedKeyPath = requireEnvironment(
                "LOCKER_CLI_PUBLIC_KEY_FILE"
        );
        String tag = requireEnvironment("LOCKER_RELEASE_TAG");
        String sdkVersion = System.getProperty("locker.sdk.version");

        ReleaseReadinessVerifier.verify(
                Path.of("").toAbsolutePath(),
                tag,
                Path.of(expectedKeyPath),
                sdkVersion
        );
    }

    private Path validProject() throws Exception {
        Path root = temporaryDirectory.resolve(
                "project-" + System.nanoTime()
        );
        Files.createDirectories(root);
        Files.writeString(
                root.resolve("LICENSE"),
                "Apache License\nVersion 2.0\n",
                StandardCharsets.UTF_8
        );
        writeCanonicalKey(sourceKey(root), PUBLIC_KEY);
        writeCanonicalKey(compiledKey(root), PUBLIC_KEY);
        return root;
    }

    private Path writeKey(String key) throws Exception {
        Path path = temporaryDirectory.resolve(
                "public-key-" + System.nanoTime() + ".txt"
        );
        Files.writeString(
                path,
                key + "\n",
                StandardCharsets.US_ASCII
        );
        return path;
    }

    private static Path sourceKey(Path root) {
        return root.resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("locker-cli-ed25519-public-key.txt");
    }

    private static Path compiledKey(Path root) {
        return root.resolve("target")
                .resolve("classes")
                .resolve("locker-cli-ed25519-public-key.txt");
    }

    private static void writeCanonicalKey(
            Path path,
            String key
    ) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                key + "\n",
                StandardCharsets.US_ASCII
        );
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " is required for release validation"
            );
        }
        return value;
    }
}
