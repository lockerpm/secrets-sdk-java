package locker.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CentralBundleBuilderTest {
    private static final String VERSION = "1.2.3";
    private static final String PUBLIC_KEY =
            "G2lcXttVEXeXdaCNb0mBMyXE6Llgw1vu9SDjFmk8d2s";
    private static final String OTHER_PUBLIC_KEY =
            Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            "x".repeat(32).getBytes(
                                    StandardCharsets.US_ASCII
                            )
                    );
    private static final byte[] INSTALLER_CLASS_BYTES =
            "verified compiled installer class".getBytes(
                    StandardCharsets.US_ASCII
            );

    @TempDir
    Path temporaryDirectory;

    @Test
    public void buildsExactSignedChecksummedCentralBundle()
            throws Exception {
        Path protectedKey = prepareInputs(true, PUBLIC_KEY);
        Path output = temporaryDirectory.resolve(
                "release-dist/central-bundle.zip"
        );

        String digest = CentralBundleBuilder.build(
                temporaryDirectory,
                VERSION,
                output,
                Instant.parse("2026-07-26T00:00:00Z"),
                protectedKey
        );

        assertTrue(Files.isRegularFile(output));
        assertEquals(64, digest.length());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            Set<String> names = new HashSet<>();
            zip.stream().forEach(entry -> names.add(entry.getName()));
            assertEquals(24, names.size());

            String prefix = "io/locker/lockersm/" + VERSION
                    + "/lockersm-" + VERSION;
            assertTrue(names.contains(prefix + ".pom"));
            assertTrue(names.contains(prefix + ".pom.asc"));
            assertTrue(names.contains(prefix + ".jar"));
            assertTrue(names.contains(prefix + ".jar.asc"));
            assertTrue(names.contains(prefix + "-sources.jar"));
            assertTrue(names.contains(prefix + "-javadoc.jar"));
            assertTrue(names.contains(prefix + ".jar.sha256"));
            assertFalse(names.contains(prefix + ".jar.asc.sha256"));

            byte[] jarBytes = zip.getInputStream(
                    zip.getEntry(prefix + ".jar")
            ).readAllBytes();
            String expectedChecksum = hexadecimal(
                    MessageDigest.getInstance("SHA-256")
                            .digest(jarBytes)
            ) + "\n";
            String actualChecksum = new String(
                    zip.getInputStream(
                            zip.getEntry(prefix + ".jar.sha256")
                    ).readAllBytes(),
                    StandardCharsets.US_ASCII
            );
            assertEquals(expectedChecksum, actualChecksum);
        }
    }

    @Test
    public void mainAcceptsGitLabCommitTimestampOffsetFormat()
            throws Exception {
        Path protectedKey = prepareInputs(true, PUBLIC_KEY);
        Path output = temporaryDirectory.resolve(
                "release-dist/central-bundle-cli.zip"
        );

        CentralBundleBuilder.main(new String[]{
                temporaryDirectory.toString(),
                VERSION,
                output.toString(),
                "2026-08-01T05:43:53+00:00",
                protectedKey.toString(),
        });

        assertTrue(Files.isRegularFile(output));
    }

    @Test
    public void builtBundleIsAcceptedByPublicReconciler()
            throws Exception {
        Path protectedKey = prepareInputs(true, PUBLIC_KEY);
        CentralPublicReconcilerTest.SigningMaterial signing =
                CentralPublicReconcilerTest.SigningMaterial.create(
                        "central-bundle-integration"
                );
        signReleaseInputs(signing);
        Path signingKey = temporaryDirectory.resolve(
                "maven-signing-key.pgp"
        );
        Files.write(signingKey, signing.encodedSecretKey());
        Path output = temporaryDirectory.resolve(
                "release-dist/central-bundle.zip"
        );

        CentralBundleBuilder.build(
                temporaryDirectory,
                VERSION,
                output,
                Instant.parse("2026-07-26T00:00:00Z"),
                protectedKey
        );
        CentralPublicReconciler.ExpectedRelease expected =
                CentralPublicReconciler.ExpectedRelease.fromBundle(
                        output,
                        VERSION,
                        signingKey
                );
        expected.erase();
    }

    @Test
    public void stageVerifiedRecoveryChecksDeploymentUrisBundleAndKey()
            throws Exception {
        Path protectedKey = prepareInputs(true, PUBLIC_KEY);
        CentralPublicReconcilerTest.SigningMaterial signing =
                CentralPublicReconcilerTest.SigningMaterial.create(
                        "central-recovery-integration"
                );
        signReleaseInputs(signing);
        Path signingKey = temporaryDirectory.resolve(
                "maven-recovery-signing-key.pgp"
        );
        Files.write(signingKey, signing.encodedSecretKey());
        Path bundle = temporaryDirectory.resolve(
                "release-dist/central-recovery-bundle.zip"
        );
        CentralBundleBuilder.build(
                temporaryDirectory,
                VERSION,
                bundle,
                Instant.parse("2026-07-26T00:00:00Z"),
                protectedKey
        );
        RecoveredDeploymentTransport transport =
                new RecoveredDeploymentTransport(bundle);
        Path deploymentId = temporaryDirectory.resolve(
                "central-deployment-id"
        );

        new CentralPublisher(
                transport,
                milliseconds -> {
                },
                "token-user",
                "token-password"
        ).stageVerified(
                bundle,
                deploymentId,
                "v" + VERSION,
                signingKey
        );

        assertEquals(
                RecoveredDeploymentTransport.DEPLOYMENT_ID + "\n",
                Files.readString(
                        deploymentId,
                        StandardCharsets.US_ASCII
                )
        );
        assertFalse(transport.uploadAttempted);
        assertEquals(24, transport.artifacts.size());
        assertEquals(transport.artifacts.size(), transport.downloads);
        assertEquals(26, transport.requests.size());
        for (String artifact : transport.artifacts.keySet()) {
            assertTrue(transport.requests.contains(
                    RecoveredDeploymentTransport.DOWNLOAD_PREFIX
                            + artifact
            ));
        }
    }

    @Test
    public void refusesIncompleteSignatureSet() throws Exception {
        Path protectedKey = prepareInputs(false, PUBLIC_KEY);
        Path output = temporaryDirectory.resolve(
                "release-dist/central-bundle.zip"
        );

        assertThrows(
                java.io.IOException.class,
                () -> CentralBundleBuilder.build(
                        temporaryDirectory,
                        VERSION,
                        output,
                        Instant.parse("2026-07-26T00:00:00Z"),
                        protectedKey
                )
        );
    }

    @Test
    public void refusesJarWithMismatchedTrustRoot() throws Exception {
        Path protectedKey = prepareInputs(true, OTHER_PUBLIC_KEY);
        Path output = temporaryDirectory.resolve(
                "release-dist/central-bundle.zip"
        );

        assertThrows(
                CliDistributionException.class,
                () -> CentralBundleBuilder.build(
                        temporaryDirectory,
                        VERSION,
                        output,
                        Instant.parse("2026-07-26T00:00:00Z"),
                        protectedKey
                )
        );
    }

    @Test
    public void refusesJarWithSubstitutedInstallerClass()
            throws Exception {
        Path protectedKey = prepareInputs(true, PUBLIC_KEY);
        writeSdkJar(
                temporaryDirectory.resolve("target")
                        .resolve("lockersm-" + VERSION + ".jar"),
                PUBLIC_KEY,
                "substituted installer class".getBytes(
                        StandardCharsets.US_ASCII
                )
        );

        assertThrows(
                CliDistributionException.class,
                () -> CentralBundleBuilder.build(
                        temporaryDirectory,
                        VERSION,
                        temporaryDirectory.resolve(
                                "release-dist/central-bundle.zip"
                        ),
                        Instant.parse("2026-07-26T00:00:00Z"),
                        protectedKey
                )
        );
    }

    @Test
    public void refusesJarWithMultiReleaseInstallerOverride()
            throws Exception {
        Path protectedKey = prepareInputs(true, PUBLIC_KEY);
        writeSdkJar(
                temporaryDirectory.resolve("target")
                        .resolve("lockersm-" + VERSION + ".jar"),
                PUBLIC_KEY,
                INSTALLER_CLASS_BYTES,
                "versioned substituted installer class".getBytes(
                        StandardCharsets.US_ASCII
                )
        );

        assertThrows(
                CliDistributionException.class,
                () -> CentralBundleBuilder.build(
                        temporaryDirectory,
                        VERSION,
                        temporaryDirectory.resolve(
                                "release-dist/central-bundle.zip"
                        ),
                        Instant.parse("2026-07-26T00:00:00Z"),
                        protectedKey
                )
        );
    }

    private Path prepareInputs(
            boolean includeJavadocSignature,
            String packagedKey
    )
            throws Exception {
        String artifactBase = "lockersm-" + VERSION;
        Path target = temporaryDirectory.resolve("target");
        Files.createDirectories(target);
        Path protectedKey = temporaryDirectory.resolve(
                "protected-public-key.txt"
        );
        Files.writeString(
                temporaryDirectory.resolve(".flattened-pom.xml"),
                "<project>"
                        + "<groupId>io.locker</groupId>"
                        + "<artifactId>lockersm</artifactId>"
                        + "<version>" + VERSION + "</version>"
                        + "</project>",
                StandardCharsets.UTF_8
        );
        writeCanonicalKey(protectedKey, PUBLIC_KEY);
        writeCanonicalKey(
                temporaryDirectory.resolve("src/main/resources")
                        .resolve("locker-cli-ed25519-public-key.txt"),
                PUBLIC_KEY
        );
        writeCanonicalKey(
                target.resolve("classes")
                        .resolve("locker-cli-ed25519-public-key.txt"),
                PUBLIC_KEY
        );
        Path compiledInstaller = target.resolve("classes")
                .resolve("locker")
                .resolve("distribution")
                .resolve("LockerCliInstaller.class");
        Files.createDirectories(compiledInstaller.getParent());
        Files.write(compiledInstaller, INSTALLER_CLASS_BYTES);
        for (String filename : new String[]{
                artifactBase + ".jar",
                artifactBase + "-sources.jar",
                artifactBase + "-javadoc.jar"
        }) {
            Path artifact = target.resolve(filename);
            if (filename.equals(artifactBase + ".jar")) {
                writeSdkJar(
                        artifact,
                        packagedKey,
                        INSTALLER_CLASS_BYTES
                );
            } else {
                Files.writeString(
                        artifact,
                        "bytes:" + filename,
                        StandardCharsets.UTF_8
                );
            }
            if (includeJavadocSignature
                    || !filename.endsWith("-javadoc.jar")) {
                Files.writeString(
                        target.resolve(filename + ".asc"),
                        "signature:" + filename,
                        StandardCharsets.US_ASCII
                );
            }
        }
        Files.writeString(
                target.resolve(artifactBase + ".pom.asc"),
                "signature:pom",
                StandardCharsets.US_ASCII
        );
        return protectedKey;
    }

    private void signReleaseInputs(
            CentralPublicReconcilerTest.SigningMaterial signing
    ) throws Exception {
        String artifactBase = "lockersm-" + VERSION;
        Path target = temporaryDirectory.resolve("target");
        Instant timestamp = Instant.parse("2026-07-26T00:00:00Z");
        Files.write(
                target.resolve(artifactBase + ".pom.asc"),
                signing.sign(
                        Files.readAllBytes(
                                temporaryDirectory.resolve(
                                        ".flattened-pom.xml"
                                )
                        ),
                        timestamp
                )
        );
        for (String filename : new String[]{
                artifactBase + ".jar",
                artifactBase + "-sources.jar",
                artifactBase + "-javadoc.jar"
        }) {
            Files.write(
                    target.resolve(filename + ".asc"),
                    signing.sign(
                            Files.readAllBytes(target.resolve(filename)),
                            timestamp
                    )
            );
        }
    }

    private static void writeSdkJar(
            Path path,
            String publicKey,
            byte[] installerClass
    ) throws Exception {
        writeSdkJar(path, publicKey, installerClass, null);
    }

    private static void writeSdkJar(
            Path path,
            String publicKey,
            byte[] installerClass,
            byte[] versionedInstallerClass
    ) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(path)
        )) {
            zip.putNextEntry(new ZipEntry(
                    "META-INF/MANIFEST.MF"
            ));
            zip.write(
                    (
                            "Manifest-Version: 1.0\n"
                                    + "Implementation-Version: "
                                    + VERSION + "\n"
                                    + (versionedInstallerClass == null
                                    ? ""
                                    : "Multi-Release: true\n")
                                    + "\n"
                    ).getBytes(StandardCharsets.US_ASCII)
            );
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(
                    "locker-cli-ed25519-public-key.txt"
            ));
            zip.write(
                    (publicKey + "\n").getBytes(
                            StandardCharsets.US_ASCII
                    )
            );
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(
                    "locker/distribution/LockerCliInstaller.class"
            ));
            zip.write(installerClass);
            zip.closeEntry();
            if (versionedInstallerClass != null) {
                zip.putNextEntry(new ZipEntry(
                        "META-INF/versions/11/"
                                + "locker/distribution/"
                                + "LockerCliInstaller.class"
                ));
                zip.write(versionedInstallerClass);
                zip.closeEntry();
            }
        }
    }

    private static void writeCanonicalKey(
            Path path,
            String publicKey
    ) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                publicKey + "\n",
                StandardCharsets.US_ASCII
        );
    }

    private static String hexadecimal(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(
                    Character.forDigit((value >>> 4) & 0x0f, 16)
            );
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }

    private static final class RecoveredDeploymentTransport
            implements CentralPublisher.Transport {
        private static final String DEPLOYMENT_ID =
                "28570f16-da32-4c14-bd2e-c1acc0782365";
        private static final String DOWNLOAD_PREFIX =
                "/api/v1/publisher/deployment/"
                        + DEPLOYMENT_ID
                        + "/download/";

        private final Map<String, byte[]> artifacts =
                new LinkedHashMap<>();
        private final List<String> requests = new ArrayList<>();
        private boolean uploadAttempted;
        private int downloads;

        private RecoveredDeploymentTransport(Path bundle)
                throws Exception {
            try (ZipFile zip = new ZipFile(bundle.toFile())) {
                for (ZipEntry entry : java.util.Collections.list(
                        zip.entries()
                )) {
                    if (!entry.isDirectory()) {
                        try (InputStream input =
                                     zip.getInputStream(entry)) {
                            artifacts.put(
                                    entry.getName(),
                                    input.readAllBytes()
                            );
                        }
                    }
                }
            }
        }

        @Override
        public CentralPublisher.Response send(
                CentralPublisher.Request request,
                String authorization
        ) throws java.io.IOException {
            String path = request.uri.getPath();
            requests.add(path);
            if ("/api/v1/publisher/deployments".equals(path)) {
                return response(
                        200,
                        "{"
                                + "\"deployments\":[{"
                                + "\"deploymentId\":\""
                                + DEPLOYMENT_ID + "\","
                                + "\"deploymentName\":\""
                                + "lockersm-java-v" + VERSION + "\","
                                + "\"namespace\":\"io.locker\","
                                + "\"deploymentState\":\"VALIDATED\""
                                + "}],"
                                + "\"page\":0,"
                                + "\"pageSize\":20,"
                                + "\"pageCount\":1,"
                                + "\"totalResultCount\":1"
                                + "}"
                );
            }
            if ("/api/v1/publisher/status".equals(path)) {
                return response(
                        200,
                        "{"
                                + "\"deploymentId\":\""
                                + DEPLOYMENT_ID + "\","
                                + "\"deploymentState\":\"VALIDATED\""
                                + "}"
                );
            }
            if (path.startsWith(DOWNLOAD_PREFIX)) {
                downloads++;
                byte[] body = artifacts.get(
                        path.substring(DOWNLOAD_PREFIX.length())
                );
                return new CentralPublisher.Response(
                        body == null ? 404 : 200,
                        body == null ? new byte[0] : body
                );
            }
            if ("/api/v1/publisher/upload".equals(path)) {
                uploadAttempted = true;
            }
            throw new java.io.IOException(
                    "Unexpected Maven Central request: " + path
            );
        }

        private static CentralPublisher.Response response(
                int status,
                String body
        ) {
            return new CentralPublisher.Response(
                    status,
                    body.getBytes(StandardCharsets.UTF_8)
            );
        }
    }
}
