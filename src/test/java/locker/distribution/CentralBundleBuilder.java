package locker.distribution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Test-scope release utility that creates one deterministic Central bundle.
 *
 * <p>It is intentionally kept out of the published SDK. CI invokes the
 * compiled class after Maven's release profile has produced and signed all
 * release artifacts.
 */
public final class CentralBundleBuilder {
    private static final long MAX_INPUT_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
    private static final Map<String, String> CHECKSUMS;

    static {
        Map<String, String> checksums = new LinkedHashMap<>();
        checksums.put(".md5", "MD5");
        checksums.put(".sha1", "SHA-1");
        checksums.put(".sha256", "SHA-256");
        checksums.put(".sha512", "SHA-512");
        CHECKSUMS = Collections.unmodifiableMap(checksums);
    }

    private CentralBundleBuilder() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 5) {
            throw new IllegalArgumentException(
                    "Expected: <project-root> <version> <output-zip> "
                            + "<commit-timestamp> "
                            + "<protected-public-key-file>"
            );
        }
        Path projectRoot = Path.of(arguments[0]);
        String version = arguments[1];
        Path output = Path.of(arguments[2]);
        Instant timestamp = Instant.parse(arguments[3]);
        Path expectedPublicKeyPath = Path.of(arguments[4]);
        String sha256 = build(
                projectRoot,
                version,
                output,
                timestamp,
                expectedPublicKeyPath
        );
        System.out.println(
                "Verified Maven Central bundle SHA-256: " + sha256
        );
    }

    static String build(
            Path projectRoot,
            String version,
            Path output,
            Instant timestamp,
            Path expectedPublicKeyPath
    ) throws Exception {
        if (projectRoot == null
                || output == null
                || timestamp == null
                || expectedPublicKeyPath == null
                || !ReleaseReadinessVerifier.isSemver(version)) {
            throw new IllegalArgumentException(
                    "Central bundle inputs are invalid"
            );
        }

        Path root = projectRoot.toAbsolutePath().normalize();
        Path target = root.resolve("target");
        String artifactBase = "lockersm-" + version;
        String centralPath = "io/locker/lockersm/" + version + "/";
        String mainJarEntry = centralPath + artifactBase + ".jar";

        List<ArtifactInput> inputs = List.of(
                new ArtifactInput(
                        root.resolve("pom.xml"),
                        centralPath + artifactBase + ".pom"
                ),
                new ArtifactInput(
                        target.resolve(artifactBase + ".jar"),
                        mainJarEntry
                ),
                new ArtifactInput(
                        target.resolve(artifactBase + "-sources.jar"),
                        centralPath + artifactBase + "-sources.jar"
                ),
                new ArtifactInput(
                        target.resolve(artifactBase + "-javadoc.jar"),
                        centralPath + artifactBase + "-javadoc.jar"
                ),
                new ArtifactInput(
                        target.resolve("gpg").resolve("pom.xml.asc"),
                        centralPath + artifactBase + ".pom.asc"
                ),
                new ArtifactInput(
                        target.resolve(artifactBase + ".jar.asc"),
                        centralPath + artifactBase + ".jar.asc"
                ),
                new ArtifactInput(
                        target.resolve(
                                artifactBase + "-sources.jar.asc"
                        ),
                        centralPath + artifactBase + "-sources.jar.asc"
                ),
                new ArtifactInput(
                        target.resolve(
                                artifactBase + "-javadoc.jar.asc"
                        ),
                        centralPath + artifactBase + "-javadoc.jar.asc"
                )
        );

        Map<String, byte[]> entries = new HashMap<>();
        long total = 0;
        for (ArtifactInput input : inputs) {
            byte[] bytes = readRegularFile(input.path);
            total += bytes.length;
            if (total > MAX_TOTAL_BYTES) {
                erase(entries.values());
                Arrays.fill(bytes, (byte) 0);
                throw new IOException(
                        "Central bundle inputs exceed the total size limit"
                );
            }
            if (entries.put(input.entryName, bytes) != null) {
                erase(entries.values());
                throw new IOException(
                        "Central bundle entry names are not unique"
                );
            }
        }

        try {
            ReleaseReadinessVerifier.verifyPackagedArtifact(
                    root,
                    expectedPublicKeyPath,
                    entries.get(mainJarEntry)
            );
            Map<String, byte[]> generated = new HashMap<>(entries);
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                for (Map.Entry<String, String> checksum
                        : CHECKSUMS.entrySet()) {
                    String checksumName =
                            entry.getKey() + checksum.getKey();
                    byte[] checksumBytes = (
                            hexadecimal(
                                    digest(
                                            checksum.getValue(),
                                            entry.getValue()
                                    )
                            )
                                    + "\n"
                    ).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    if (generated.put(
                            checksumName,
                            checksumBytes
                    ) != null) {
                        erase(generated.values());
                        throw new IOException(
                                "Central checksum entry names are not unique"
                        );
                    }
                }
            }
            entries = generated;

            writeAtomically(output, entries, timestamp);
            verify(output, entries);
            byte[] outputBytes = readRegularFile(
                    output.toAbsolutePath().normalize()
            );
            try {
                return hexadecimal(digest("SHA-256", outputBytes));
            } finally {
                Arrays.fill(outputBytes, (byte) 0);
            }
        } finally {
            erase(entries.values());
        }
    }

    private static byte[] readRegularFile(Path path) throws IOException {
        BasicFileAttributes before = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!before.isRegularFile()
                || before.size() < 1
                || before.size() > MAX_INPUT_BYTES) {
            throw new IOException(
                    "Central bundle input must be a bounded regular file"
            );
        }
        byte[] bytes = Files.readAllBytes(path);
        BasicFileAttributes after = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!after.isRegularFile()
                || bytes.length != before.size()
                || after.size() != before.size()
                || !before.lastModifiedTime().equals(
                after.lastModifiedTime()
        )) {
            Arrays.fill(bytes, (byte) 0);
            throw new IOException(
                    "Central bundle input changed while it was read"
            );
        }
        return bytes;
    }

    private static void writeAtomically(
            Path output,
            Map<String, byte[]> entries,
            Instant timestamp
    ) throws IOException {
        Path absoluteOutput = output.toAbsolutePath().normalize();
        Path parent = absoluteOutput.getParent();
        if (parent == null) {
            throw new IOException("Central bundle output parent is missing");
        }
        Files.createDirectories(parent);
        if (Files.exists(
                absoluteOutput,
                LinkOption.NOFOLLOW_LINKS
        )) {
            throw new IOException(
                    "Central bundle output already exists"
            );
        }
        Path temporary = Files.createTempFile(
                parent,
                ".central-bundle-",
                ".tmp"
        );
        boolean published = false;
        try {
            List<String> names = new ArrayList<>(entries.keySet());
            Collections.sort(names);
            try (ZipOutputStream zip = new ZipOutputStream(
                    Files.newOutputStream(temporary)
            )) {
                for (String name : names) {
                    ZipEntry zipEntry = new ZipEntry(name);
                    zipEntry.setTime(timestamp.toEpochMilli());
                    zip.putNextEntry(zipEntry);
                    zip.write(entries.get(name));
                    zip.closeEntry();
                }
            }
            try {
                Files.move(
                        temporary,
                        absoluteOutput,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "Atomic Central bundle publication is unavailable",
                        exception
                );
            }
            published = true;
        } finally {
            if (!published) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void verify(
            Path output,
            Map<String, byte[]> expected
    ) throws IOException {
        Set<String> expectedNames = expected.keySet();
        Map<String, Integer> counts = new HashMap<>();
        try (ZipFile zip = new ZipFile(output.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                counts.merge(entry.getName(), 1, Integer::sum);
                byte[] expectedBytes = expected.get(entry.getName());
                if (expectedBytes == null || entry.isDirectory()) {
                    throw new IOException(
                            "Central bundle contains an unexpected entry"
                    );
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    if (!Arrays.equals(
                            expectedBytes,
                            readBounded(
                                    stream,
                                    expectedBytes.length
                            )
                    )) {
                        throw new IOException(
                                "Central bundle entry bytes do not match"
                        );
                    }
                }
            }
        }
        if (!counts.keySet().equals(expectedNames)
                || counts.values().stream().anyMatch(
                count -> count != 1
        )) {
            throw new IOException(
                    "Central bundle entries do not match the release set"
            );
        }
    }

    private static byte[] readBounded(
            InputStream stream,
            int expectedLength
    ) throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream(expectedLength);
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > expectedLength) {
                throw new IOException(
                        "Central bundle entry exceeds its expected size"
                );
            }
            output.write(buffer, 0, read);
        }
        if (total != expectedLength) {
            throw new IOException(
                    "Central bundle entry is truncated"
            );
        }
        return output.toByteArray();
    }

    private static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Required checksum algorithm is unavailable",
                    exception
            );
        }
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

    private static void erase(Iterable<byte[]> values) {
        for (byte[] value : values) {
            Arrays.fill(value, (byte) 0);
        }
    }

    private static final class ArtifactInput {
        private final Path path;
        private final String entryName;

        private ArtifactInput(Path path, String entryName) {
            this.path = path;
            this.entryName = entryName;
        }
    }
}
