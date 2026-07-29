package locker.distribution;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ReleaseReadinessVerifier {
    private static final int MAX_LICENSE_BYTES = 64 * 1024;
    private static final int MAX_KEY_BYTES = 128;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_INSTALLER_CLASS_BYTES = 4 * 1024 * 1024;
    private static final int MAX_JAR_ENTRIES = 10_000;
    private static final long MAX_JAR_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_JAR_UNCOMPRESSED_BYTES =
            256L * 1024L * 1024L;
    private static final String PUBLIC_KEY_RESOURCE =
            "locker-cli-ed25519-public-key.txt";
    private static final String INSTALLER_CLASS =
            "locker/distribution/LockerCliInstaller.class";
    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "\\.(0|[1-9][0-9]*)"
                    + "(?:-(?:0|[1-9][0-9]*"
                    + "|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
                    + "(?:\\.(?:0|[1-9][0-9]*"
                    + "|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?$"
    );

    private ReleaseReadinessVerifier() {
    }

    static void verify(
            Path projectRoot,
            String tag,
            Path expectedPublicKeyPath,
            String sdkVersion
    ) throws CliDistributionException {
        if (projectRoot == null
                || expectedPublicKeyPath == null) {
            throw failure("Release validation inputs are incomplete");
        }
        String checkedVersion = requireTrimmed(
                sdkVersion,
                "The SDK version is missing"
        );
        if (!isSemver(checkedVersion)
                || !("v" + checkedVersion).equals(tag)) {
            throw failure(
                    "The protected release tag does not match "
                            + "the SDK version"
            );
        }
        verifyLicense(projectRoot.resolve("LICENSE"));
        verifyPublicKeyCopies(
                projectRoot,
                expectedPublicKeyPath,
                null
        );
    }

    static void verifyPackagedArtifact(
            Path projectRoot,
            Path expectedPublicKeyPath,
            byte[] packagedJar,
            String expectedVersion
    ) throws CliDistributionException {
        if (projectRoot == null
                || expectedPublicKeyPath == null
                || packagedJar == null
                || packagedJar.length < 1
                || packagedJar.length > MAX_JAR_BYTES
                || !isSemver(expectedVersion)) {
            throw failure("Packaged release validation inputs are invalid");
        }
        PackagedRuntime packaged = extractPackagedRuntime(
                packagedJar,
                expectedVersion
        );
        byte[] compiledInstaller = null;
        try {
            verifyPublicKeyCopies(
                    projectRoot,
                    expectedPublicKeyPath,
                    packaged.publicKey
            );
            compiledInstaller = readRegularFile(
                    projectRoot.resolve("target")
                            .resolve("classes")
                            .resolve(INSTALLER_CLASS),
                    MAX_INSTALLER_CLASS_BYTES,
                    "Compiled Locker CLI installer class"
            );
            if (!MessageDigest.isEqual(
                    compiledInstaller,
                    packaged.installerClass
            )) {
                throw failure(
                        "The packaged Locker CLI installer class does not "
                                + "match the verified compiled class"
                );
            }
        } finally {
            erase(compiledInstaller);
            packaged.erase();
        }
    }

    private static void verifyPublicKeyCopies(
            Path projectRoot,
            Path expectedPublicKeyPath,
            byte[] packagedKeyBytes
    ) throws CliDistributionException {
        byte[] expectedBytes = null;
        byte[] sourceBytes = null;
        byte[] compiledBytes = null;
        byte[] runtimeBytes = null;
        byte[] expectedKey = null;
        byte[] sourceKey = null;
        byte[] compiledKey = null;
        byte[] runtimeKey = null;
        byte[] packagedKey = null;
        try {
            expectedBytes = readRegularFile(
                    expectedPublicKeyPath,
                    MAX_KEY_BYTES,
                    "Locker CLI release public key"
            );
            sourceBytes = readRegularFile(
                    projectRoot.resolve("src")
                            .resolve("main")
                            .resolve("resources")
                            .resolve(PUBLIC_KEY_RESOURCE),
                    MAX_KEY_BYTES,
                    "Source Locker CLI public key"
            );
            compiledBytes = readRegularFile(
                    projectRoot.resolve("target")
                            .resolve("classes")
                            .resolve(PUBLIC_KEY_RESOURCE),
                    MAX_KEY_BYTES,
                    "Compiled Locker CLI public key"
            );
            runtimeBytes = (
                    LockerCliInstaller.compiledReleasePublicKey() + "\n"
            ).getBytes(StandardCharsets.US_ASCII);

            expectedKey = decodeKey(
                    expectedBytes,
                    "Protected Locker CLI release public key"
            );
            sourceKey = decodeKey(
                    sourceBytes,
                    "Source Locker CLI public key"
            );
            compiledKey = decodeKey(
                    compiledBytes,
                    "Compiled Locker CLI public key"
            );
            runtimeKey = decodeKey(
                    runtimeBytes,
                    "Runtime-compiled Locker CLI public key"
            );
            requireMatchingKey(
                    expectedBytes,
                    expectedKey,
                    sourceBytes,
                    sourceKey,
                    "The source Locker CLI public key does not "
                            + "match the protected release key"
            );
            requireMatchingKey(
                    expectedBytes,
                    expectedKey,
                    compiledBytes,
                    compiledKey,
                    "The compiled Locker CLI public key does not "
                            + "match the protected release key"
            );
            requireMatchingKey(
                    expectedBytes,
                    expectedKey,
                    runtimeBytes,
                    runtimeKey,
                    "The runtime-compiled Locker CLI public key does not "
                            + "match the protected release key"
            );

            if (packagedKeyBytes != null) {
                packagedKey = decodeKey(
                        packagedKeyBytes,
                        "Packaged Locker CLI public key"
                );
                requireMatchingKey(
                        expectedBytes,
                        expectedKey,
                        packagedKeyBytes,
                        packagedKey,
                        "The packaged Locker CLI public key does not "
                                + "match the protected release key"
                );
            }
        } finally {
            erase(expectedBytes);
            erase(sourceBytes);
            erase(compiledBytes);
            erase(runtimeBytes);
            erase(expectedKey);
            erase(sourceKey);
            erase(compiledKey);
            erase(runtimeKey);
            erase(packagedKey);
        }
    }

    private static PackagedRuntime extractPackagedRuntime(
            byte[] packagedJar,
            String expectedVersion
    )
            throws CliDistributionException {
        byte[] buffer = new byte[8192];
        byte[] publicKey = null;
        byte[] installerClass = null;
        byte[] manifestBytes = null;
        int entryCount = 0;
        long totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(
                new ByteArrayInputStream(packagedJar)
        )) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_JAR_ENTRIES) {
                    throw failure(
                            "Packaged SDK JAR contains too many entries"
                    );
                }
                boolean isPublicKey = PUBLIC_KEY_RESOURCE.equals(
                        entry.getName()
                );
                boolean isManifest = "META-INF/MANIFEST.MF".equals(
                        entry.getName()
                );
                boolean isInstaller = INSTALLER_CLASS.equals(
                        entry.getName()
                );
                boolean isVersionedInstaller =
                        entry.getName().startsWith(
                                "META-INF/versions/"
                        )
                                && entry.getName().endsWith(
                                "/" + INSTALLER_CLASS
                        );
                if (isVersionedInstaller) {
                    throw failure(
                            "Packaged SDK JAR must not override the "
                                    + "Locker CLI installer class"
                    );
                }
                if (isPublicKey
                        && (publicKey != null || entry.isDirectory())) {
                    throw failure(
                            "Packaged SDK JAR must contain exactly one "
                                    + "Locker CLI public key"
                    );
                }
                if (isInstaller
                        && (installerClass != null
                        || entry.isDirectory())) {
                    throw failure(
                            "Packaged SDK JAR must contain exactly one "
                                    + "Locker CLI installer class"
                    );
                }
                if (isManifest
                        && (manifestBytes != null
                        || entry.isDirectory())) {
                    throw failure(
                            "Packaged SDK JAR must contain exactly one "
                                    + "manifest"
                    );
                }

                int captureLimit = isPublicKey
                        ? MAX_KEY_BYTES
                        : isManifest
                        ? MAX_MANIFEST_BYTES
                        : isInstaller
                        ? MAX_INSTALLER_CLASS_BYTES
                        : 0;
                ByteArrayOutputStream captured = captureLimit > 0
                        ? new ByteArrayOutputStream(
                        Math.min(captureLimit, 8192)
                )
                        : null;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    totalBytes += read;
                    if (totalBytes > MAX_JAR_UNCOMPRESSED_BYTES) {
                        throw failure(
                                "Packaged SDK JAR exceeds its "
                                        + "uncompressed size limit"
                        );
                    }
                    if (captured != null) {
                        if (captured.size() + read > captureLimit) {
                            throw failure(
                                    "Packaged Locker CLI runtime entry "
                                            + "exceeds its size limit"
                            );
                        }
                        captured.write(buffer, 0, read);
                    }
                }
                if (captured != null) {
                    if (isPublicKey) {
                        publicKey = captured.toByteArray();
                    } else if (isManifest) {
                        manifestBytes = captured.toByteArray();
                    } else {
                        installerClass = captured.toByteArray();
                    }
                }
                zip.closeEntry();
            }
        } catch (CliDistributionException exception) {
            erase(publicKey);
            erase(installerClass);
            erase(manifestBytes);
            throw exception;
        } catch (IOException exception) {
            erase(publicKey);
            erase(installerClass);
            erase(manifestBytes);
            throw new CliDistributionException(
                    "Packaged SDK JAR is unreadable",
                    exception
            );
        }
        if (publicKey == null) {
            erase(installerClass);
            erase(manifestBytes);
            throw failure(
                    "Packaged SDK JAR is missing the Locker CLI public key"
            );
        }
        if (installerClass == null || installerClass.length == 0) {
            erase(publicKey);
            erase(installerClass);
            erase(manifestBytes);
            throw failure(
                    "Packaged SDK JAR is missing the Locker CLI "
                            + "installer class"
            );
        }
        if (manifestBytes == null) {
            erase(publicKey);
            erase(installerClass);
            throw failure(
                    "Packaged SDK JAR is missing its manifest"
            );
        }
        try {
            verifyManifestVersion(manifestBytes, expectedVersion);
        } catch (CliDistributionException exception) {
            erase(publicKey);
            erase(installerClass);
            throw exception;
        } finally {
            erase(manifestBytes);
        }
        return new PackagedRuntime(publicKey, installerClass);
    }

    private static void verifyManifestVersion(
            byte[] manifestBytes,
            String expectedVersion
    ) throws CliDistributionException {
        try {
            Manifest manifest = new Manifest(
                    new ByteArrayInputStream(manifestBytes)
            );
            String packagedVersion = manifest
                    .getMainAttributes()
                    .getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            if (!expectedVersion.equals(packagedVersion)) {
                throw failure(
                        "Packaged SDK JAR version does not match "
                                + "the release version"
                );
            }
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "Packaged SDK JAR manifest is invalid",
                    exception
            );
        }
    }

    private static byte[] decodeKey(
            byte[] bytes,
            String label
    ) throws CliDistributionException {
        return SignedUpdateContract.decodePublicKey(
                keyText(bytes, label)
        );
    }

    private static void requireMatchingKey(
            byte[] expectedBytes,
            byte[] expectedKey,
            byte[] candidateBytes,
            byte[] candidateKey,
            String message
    ) throws CliDistributionException {
        if (!MessageDigest.isEqual(expectedBytes, candidateBytes)
                || !MessageDigest.isEqual(expectedKey, candidateKey)) {
            throw failure(message);
        }
    }

    static boolean isSemver(String value) {
        return value != null && SEMVER.matcher(value).matches();
    }

    private static void verifyLicense(Path path)
            throws CliDistributionException {
        byte[] bytes = readRegularFile(
                path,
                MAX_LICENSE_BYTES,
                "LICENSE"
        );
        try {
            String text = decodeUtf8(bytes, "LICENSE");
            if (text.isBlank()
                    || !text.contains("Apache License")
                    || !text.contains("Version 2.0")) {
                throw failure(
                        "LICENSE is empty or inconsistent "
                                + "with package metadata"
                );
            }
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static String keyText(
            byte[] bytes,
            String label
    ) throws CliDistributionException {
        String text = decodeUtf8(bytes, label);
        if (!text.endsWith("\n")
                || text.endsWith("\n\n")
                || text.indexOf('\r') >= 0) {
            throw failure(
                    label + " must contain one key and one final LF"
            );
        }
        return requireTrimmed(
                text.substring(0, text.length() - 1),
                label + " must not be blank"
        );
    }

    private static String decodeUtf8(
            byte[] bytes,
            String label
    ) throws CliDistributionException {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new CliDistributionException(
                    label + " must be valid UTF-8",
                    exception
            );
        }
    }

    private static byte[] readRegularFile(
            Path path,
            int maximum,
            String label
    ) throws CliDistributionException {
        try {
            BasicFileAttributes before = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (!before.isRegularFile()
                    || before.size() < 1
                    || before.size() > maximum) {
                throw failure(
                        label + " must be a bounded regular "
                                + "non-symlink file"
                );
            }
            byte[] bytes;
            try (InputStream input = Files.newInputStream(
                    path,
                    StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                ByteArrayOutputStream output =
                        new ByteArrayOutputStream((int) before.size());
                byte[] buffer = new byte[Math.min(maximum + 1, 8192)];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maximum) {
                        throw failure(
                                label + " exceeds its size limit"
                        );
                    }
                    output.write(buffer, 0, read);
                }
                bytes = output.toByteArray();
            }
            BasicFileAttributes after = Files.readAttributes(
                    path,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (!after.isRegularFile()
                    || bytes.length != before.size()
                    || bytes.length != after.size()
                    || !before.lastModifiedTime().equals(
                    after.lastModifiedTime()
            )
                    || !Objects.equals(
                    before.fileKey(),
                    after.fileKey()
            )) {
                Arrays.fill(bytes, (byte) 0);
                throw failure(label + " changed while it was read");
            }
            return bytes;
        } catch (IOException exception) {
            throw new CliDistributionException(
                    label + " is unavailable",
                    exception
            );
        }
    }

    private static String requireTrimmed(
            String value,
            String message
    ) throws CliDistributionException {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())) {
            throw failure(message);
        }
        return value;
    }

    private static CliDistributionException failure(String message) {
        return new CliDistributionException(message);
    }

    private static final class PackagedRuntime {
        private final byte[] publicKey;
        private final byte[] installerClass;

        private PackagedRuntime(
                byte[] publicKey,
                byte[] installerClass
        ) {
            this.publicKey = publicKey;
            this.installerClass = installerClass;
        }

        private void erase() {
            ReleaseReadinessVerifier.erase(publicKey);
            ReleaseReadinessVerifier.erase(installerClass);
        }
    }

    private static void erase(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }
}
