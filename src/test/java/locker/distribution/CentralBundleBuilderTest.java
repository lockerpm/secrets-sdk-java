package locker.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertEquals(40, names.size());

            String prefix = "io/locker/lockersm/" + VERSION
                    + "/lockersm-" + VERSION;
            assertTrue(names.contains(prefix + ".pom"));
            assertTrue(names.contains(prefix + ".pom.asc"));
            assertTrue(names.contains(prefix + ".jar"));
            assertTrue(names.contains(prefix + ".jar.asc"));
            assertTrue(names.contains(prefix + "-sources.jar"));
            assertTrue(names.contains(prefix + "-javadoc.jar"));
            assertTrue(names.contains(prefix + ".jar.sha256"));

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
        Files.createDirectories(target.resolve("gpg"));
        Path protectedKey = temporaryDirectory.resolve(
                "protected-public-key.txt"
        );
        Files.writeString(
                temporaryDirectory.resolve("pom.xml"),
                "<project/>",
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
                target.resolve("gpg/pom.xml.asc"),
                "signature:pom",
                StandardCharsets.US_ASCII
        );
        return protectedKey;
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
            if (versionedInstallerClass != null) {
                zip.putNextEntry(new ZipEntry(
                        "META-INF/MANIFEST.MF"
                ));
                zip.write(
                        (
                                "Manifest-Version: 1.0\n"
                                        + "Multi-Release: true\n\n"
                        ).getBytes(StandardCharsets.US_ASCII)
                );
                zip.closeEntry();
            }
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
}
