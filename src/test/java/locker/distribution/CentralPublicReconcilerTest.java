package locker.distribution;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CentralPublicReconcilerTest {
    private static final String VERSION = "1.2.3";
    private static final Instant LOCAL_SIGNATURE_TIME =
            Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant PUBLIC_SIGNATURE_TIME =
            Instant.parse("2025-01-02T00:00:00Z");
    private static final Map<String, String> CHECKSUMS =
            Map.of(
                    ".md5", "MD5",
                    ".sha1", "SHA-1",
                    ".sha256", "SHA-256",
                    ".sha512", "SHA-512"
            );

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    @TempDir
    Path temporaryDirectory;

    @Test
    public void distinguishesAbsentPartialAndExactRelease()
            throws Exception {
        Fixture fixture = fixture(false);
        CentralPublicReconciler reconciler =
                new CentralPublicReconciler();
        CentralPublicReconciler.ExpectedRelease expected =
                expected(fixture);
        try {
            assertEquals(
                    CentralPublicReconciler.State.ABSENT,
                    reconciler.inspect(
                            expected,
                            new FakeTransport(Map.of())
                    )
            );
            Map<String, byte[]> partial = new LinkedHashMap<>();
            Map.Entry<String, byte[]> first =
                    fixture.publicBodies.entrySet().iterator().next();
            partial.put(first.getKey(), first.getValue());
            assertEquals(
                    CentralPublicReconciler.State.PARTIAL,
                    reconciler.inspect(
                            expected,
                            new FakeTransport(partial)
                    )
            );
            assertEquals(
                    CentralPublicReconciler.State.EXACT,
                    reconciler.inspect(
                            expected,
                            new FakeTransport(fixture.publicBodies)
                    )
            );
        } finally {
            expected.erase();
        }
    }

    @Test
    public void acceptsNondeterministicValidPublicSignatures()
            throws Exception {
        Fixture fixture = fixture(false);
        String firstSignature = fixture.publicBodies.keySet()
                .stream()
                .filter(name -> name.endsWith(".asc"))
                .findFirst()
                .orElseThrow();
        assertFalse(Arrays.equals(
                fixture.localBodies.get(
                        firstSignature.substring("/maven2/".length())
                ),
                fixture.publicBodies.get(firstSignature)
        ));

        CentralPublicReconciler.ExpectedRelease expected =
                expected(fixture);
        try {
            assertEquals(
                    CentralPublicReconciler.State.EXACT,
                    new CentralPublicReconciler().inspect(
                            expected,
                            new FakeTransport(fixture.publicBodies)
                    )
            );
        } finally {
            expected.erase();
        }
    }

    @Test
    public void acceptsDocumentedPublicChecksumSet()
            throws Exception {
        Fixture fixture = fixture(false);
        Map<String, byte[]> documented = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry
                : fixture.publicBodies.entrySet()) {
            String path = entry.getKey();
            if (!path.endsWith(".sha256")
                    && !path.endsWith(".sha512")
                    && !path.contains(".asc.")) {
                documented.put(path, entry.getValue());
            }
        }
        CentralPublicReconciler.ExpectedRelease expected =
                expected(fixture);
        try {
            assertEquals(
                    CentralPublicReconciler.State.EXACT,
                    new CentralPublicReconciler().inspect(
                            expected,
                            new FakeTransport(documented)
                    )
            );
        } finally {
            expected.erase();
        }
    }

    @Test
    public void rejectsAnInvalidExposedChecksum()
            throws Exception {
        Fixture fixture = fixture(false);
        Map<String, byte[]> substituted =
                new LinkedHashMap<>(fixture.publicBodies);
        String checksumPath = substituted.keySet().stream()
                .filter(path -> path.endsWith(".sha512"))
                .findFirst()
                .orElseThrow();
        byte[] invalid = Arrays.copyOf(
                substituted.get(checksumPath),
                substituted.get(checksumPath).length
        );
        invalid[0] = invalid[0] == '0' ? (byte) '1' : (byte) '0';
        substituted.put(checksumPath, invalid);
        CentralPublicReconciler.ExpectedRelease expected =
                expected(fixture);

        try {
            assertThrows(
                    IOException.class,
                    () -> new CentralPublicReconciler().inspect(
                            expected,
                            new FakeTransport(substituted)
                    )
            );
        } finally {
            expected.erase();
        }
    }

    @Test
    public void failsClosedWhenPublicPayloadBytesDiffer()
            throws Exception {
        Fixture fixture = fixture(false);
        CentralPublicReconciler.ExpectedRelease expected =
                expected(fixture);
        Map<String, byte[]> substituted =
                new LinkedHashMap<>(fixture.publicBodies);
        String firstPayload = substituted.keySet().stream()
                .filter(name -> !name.endsWith(".asc"))
                .findFirst()
                .orElseThrow();
        substituted.put(
                firstPayload,
                "substituted".getBytes(StandardCharsets.US_ASCII)
        );

        try {
            assertThrows(
                    IOException.class,
                    () -> new CentralPublicReconciler().inspect(
                            expected,
                            new FakeTransport(substituted)
                    )
            );
        } finally {
            expected.erase();
        }
    }

    @Test
    public void rejectsPublicSignatureFromAnotherKey()
            throws Exception {
        Fixture fixture = fixture(false);
        SigningMaterial other = SigningMaterial.create("other");
        Map<String, byte[]> substituted =
                new LinkedHashMap<>(fixture.publicBodies);
        String payloadPath = substituted.keySet().stream()
                .filter(name -> !name.endsWith(".asc"))
                .findFirst()
                .orElseThrow();
        substituted.put(
                payloadPath + ".asc",
                other.sign(
                        substituted.get(payloadPath),
                        PUBLIC_SIGNATURE_TIME
                )
        );
        CentralPublicReconciler.ExpectedRelease expected =
                expected(fixture);
        try {
            assertThrows(
                    IOException.class,
                    () -> new CentralPublicReconciler().inspect(
                            expected,
                            new FakeTransport(substituted)
                    )
            );
        } finally {
            expected.erase();
        }
    }

    @Test
    public void rejectsSignatureForDifferentPayload()
            throws Exception {
        Fixture fixture = fixture(false);
        Map<String, byte[]> substituted =
                new LinkedHashMap<>(fixture.publicBodies);
        String payloadPath = substituted.keySet().stream()
                .filter(name -> !name.endsWith(".asc"))
                .findFirst()
                .orElseThrow();
        substituted.put(
                payloadPath + ".asc",
                fixture.signing.sign(
                        "different-payload".getBytes(
                                StandardCharsets.US_ASCII
                        ),
                        PUBLIC_SIGNATURE_TIME
                )
        );
        CentralPublicReconciler.ExpectedRelease expected =
                expected(fixture);
        try {
            assertThrows(
                    IOException.class,
                    () -> new CentralPublicReconciler().inspect(
                            expected,
                            new FakeTransport(substituted)
                    )
            );
        } finally {
            expected.erase();
        }
    }

    @Test
    public void waitsForACompleteMatchingPublication()
            throws Exception {
        Fixture fixture = fixture(false);
        CentralPublicReconciler.ExpectedRelease expected =
                expected(fixture);
        FakeTransport transport = new FakeTransport(Map.of());
        int[] sleeps = {0};

        try {
            new CentralPublicReconciler().waitForExact(
                    expected,
                    transport,
                    milliseconds -> {
                        sleeps[0]++;
                        transport.replace(fixture.publicBodies);
                    },
                    2
            );
        } finally {
            expected.erase();
        }
        assertEquals(1, sleeps[0]);
    }

    @Test
    public void refusesAnIncompleteOrExtendedLocalBundle()
            throws Exception {
        Fixture extended = fixture(true);
        assertThrows(
                IOException.class,
                () -> CentralPublicReconciler.ExpectedRelease
                        .fromBundle(
                                extended.bundle,
                                VERSION,
                                extended.signingKey
                        )
        );

        Path incomplete = temporaryDirectory.resolve("incomplete.zip");
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(incomplete)
        )) {
            zip.putNextEntry(new ZipEntry("unexpected"));
            zip.write(1);
            zip.closeEntry();
        }
        assertThrows(
                IOException.class,
                () -> CentralPublicReconciler.ExpectedRelease
                        .fromBundle(
                                incomplete,
                                VERSION,
                                extended.signingKey
                        )
        );
    }

    private CentralPublicReconciler.ExpectedRelease expected(
            Fixture fixture
    ) throws Exception {
        return CentralPublicReconciler.ExpectedRelease.fromBundle(
                fixture.bundle,
                VERSION,
                fixture.signingKey
        );
    }

    private Fixture fixture(boolean addUnexpectedEntry)
            throws Exception {
        SigningMaterial signing = SigningMaterial.create("release");
        Path signingKey = temporaryDirectory.resolve(
                "release-" + System.nanoTime() + ".key"
        );
        Files.write(signingKey, signing.secretKeyRing);
        Path bundle = temporaryDirectory.resolve(
                "central-" + System.nanoTime() + ".zip"
        );
        String prefix = "io/locker/lockersm/" + VERSION
                + "/lockersm-" + VERSION;
        String[] suffixes = {
                ".pom",
                ".jar",
                "-sources.jar",
                "-javadoc.jar"
        };
        Map<String, byte[]> localBodies = new LinkedHashMap<>();
        Map<String, byte[]> publicBodies = new LinkedHashMap<>();
        for (String suffix : suffixes) {
            String name = prefix + suffix;
            byte[] payload = (
                    "artifact:" + suffix
            ).getBytes(StandardCharsets.US_ASCII);
            byte[] localSignature = signing.sign(
                    payload,
                    LOCAL_SIGNATURE_TIME
            );
            byte[] publicSignature = signing.sign(
                    payload,
                    PUBLIC_SIGNATURE_TIME
            );
            localBodies.put(name, payload);
            localBodies.put(name + ".asc", localSignature);
            putPublicArtifact(
                    publicBodies,
                    "/maven2/" + name,
                    payload
            );
            publicBodies.put(
                    "/maven2/" + name + ".asc",
                    publicSignature
            );
        }
        try (ZipOutputStream zip = new ZipOutputStream(
                Files.newOutputStream(bundle)
        )) {
            for (Map.Entry<String, byte[]> entry
                    : localBodies.entrySet()) {
                writeZipEntry(zip, entry.getKey(), entry.getValue());
                if (entry.getKey().endsWith(".asc")) {
                    continue;
                }
                for (Map.Entry<String, String> checksum
                        : CHECKSUMS.entrySet()) {
                    byte[] value = (
                            hexadecimal(MessageDigest.getInstance(
                                    checksum.getValue()
                            ).digest(entry.getValue())) + "\n"
                    ).getBytes(StandardCharsets.US_ASCII);
                    writeZipEntry(
                            zip,
                            entry.getKey() + checksum.getKey(),
                            value
                    );
                }
            }
            if (addUnexpectedEntry) {
                writeZipEntry(
                        zip,
                        "unexpected-payload",
                        new byte[]{1}
                );
            }
        }
        return new Fixture(
                bundle,
                signingKey,
                signing,
                localBodies,
                publicBodies
        );
    }

    private static void putPublicArtifact(
            Map<String, byte[]> bodies,
            String path,
            byte[] artifact
    ) throws Exception {
        bodies.put(path, artifact);
        for (Map.Entry<String, String> checksum
                : CHECKSUMS.entrySet()) {
            bodies.put(
                    path + checksum.getKey(),
                    (
                            hexadecimal(MessageDigest.getInstance(
                                    checksum.getValue()
                            ).digest(artifact)) + "\n"
                    ).getBytes(StandardCharsets.US_ASCII)
            );
        }
    }

    private static void writeZipEntry(
            ZipOutputStream zip,
            String name,
            byte[] bytes
    ) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
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

    @SuppressWarnings("deprecation")
    static final class SigningMaterial {
        private final PGPSecretKey signingKey;
        private final PGPPrivateKey privateKey;
        private final byte[] secretKeyRing;

        private SigningMaterial(
                PGPSecretKey signingKey,
                PGPPrivateKey privateKey,
                byte[] secretKeyRing
        ) {
            this.signingKey = signingKey;
            this.privateKey = privateKey;
            this.secretKeyRing = secretKeyRing;
        }

        static SigningMaterial create(String identity)
                throws Exception {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            KeyPairGenerator generator =
                    KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            PGPKeyPair keyPair = new JcaPGPKeyPair(
                    PublicKeyAlgorithmTags.RSA_SIGN,
                    generator.generateKeyPair(),
                    Date.from(Instant.parse(
                            "2024-01-01T00:00:00Z"
                    ))
            );
            PGPDigestCalculator checksum =
                    new JcaPGPDigestCalculatorProviderBuilder()
                            .setProvider("BC")
                            .build()
                            .get(HashAlgorithmTags.SHA1);
            PGPKeyRingGenerator keyRings =
                    new PGPKeyRingGenerator(
                            PGPSignature.POSITIVE_CERTIFICATION,
                            keyPair,
                            identity + "@locker.invalid",
                            checksum,
                            null,
                            null,
                            new JcaPGPContentSignerBuilder(
                                    keyPair.getPublicKey()
                                            .getAlgorithm(),
                                    HashAlgorithmTags.SHA512
                            ).setProvider("BC"),
                            null
                    );
            PGPSecretKeyRing ring =
                    keyRings.generateSecretKeyRing();
            ByteArrayOutputStream encoded =
                    new ByteArrayOutputStream();
            ring.encode(encoded);
            PGPSecretKey secret = ring.getSecretKey();
            PGPPrivateKey privateKey = secret.extractPrivateKey(
                    new JcePBESecretKeyDecryptorBuilder()
                            .setProvider("BC")
                            .build(new char[0])
            );
            return new SigningMaterial(
                    secret,
                    privateKey,
                    encoded.toByteArray()
            );
        }

        byte[] encodedSecretKey() {
            return Arrays.copyOf(
                    secretKeyRing,
                    secretKeyRing.length
            );
        }

        byte[] sign(
                byte[] payload,
                Instant timestamp
        ) throws Exception {
            PGPSignatureGenerator signature =
                    new PGPSignatureGenerator(
                            new JcaPGPContentSignerBuilder(
                                    signingKey.getPublicKey()
                                            .getAlgorithm(),
                                    HashAlgorithmTags.SHA512
                            ).setProvider("BC")
                    );
            signature.init(
                    PGPSignature.BINARY_DOCUMENT,
                    privateKey
            );
            PGPSignatureSubpacketGenerator subpackets =
                    new PGPSignatureSubpacketGenerator();
            subpackets.setIssuerFingerprint(
                    false,
                    signingKey.getPublicKey()
            );
            subpackets.setSignatureCreationTime(
                    false,
                    Date.from(timestamp)
            );
            signature.setHashedSubpackets(
                    subpackets.generate()
            );
            signature.update(payload);
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            try (ArmoredOutputStream armor =
                         new ArmoredOutputStream(output)) {
                signature.generate().encode(armor);
            }
            return output.toByteArray();
        }
    }

    private static final class FakeTransport
            implements CentralPublicReconciler.Transport {
        private Map<String, byte[]> bodies;

        private FakeTransport(Map<String, byte[]> bodies) {
            replace(bodies);
        }

        private void replace(Map<String, byte[]> replacement) {
            bodies = new LinkedHashMap<>(replacement);
        }

        @Override
        public CentralPublicReconciler.Response get(
                URI uri,
                long maximumSize
        ) {
            byte[] body = bodies.get(uri.getPath());
            if (body == null) {
                return new CentralPublicReconciler.Response(
                        404,
                        new byte[0]
                );
            }
            return new CentralPublicReconciler.Response(200, body);
        }
    }

    private static final class Fixture {
        private final Path bundle;
        private final Path signingKey;
        private final SigningMaterial signing;
        private final Map<String, byte[]> localBodies;
        private final Map<String, byte[]> publicBodies;

        private Fixture(
                Path bundle,
                Path signingKey,
                SigningMaterial signing,
                Map<String, byte[]> localBodies,
                Map<String, byte[]> publicBodies
        ) {
            this.bundle = bundle;
            this.signingKey = signingKey;
            this.signing = signing;
            this.localBodies = localBodies;
            this.publicBodies = publicBodies;
        }
    }
}
