package locker.distribution;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.sig.IssuerFingerprint;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;

/**
 * Reconciles a locally verified Central bundle with public Maven artifacts.
 *
 * <p>Release payloads must match byte-for-byte. OpenPGP armor is intentionally
 * not compared byte-for-byte because detached signatures contain a
 * nondeterministic creation timestamp. Instead, every local and public
 * signature must contain exactly one binary-document signature, use SHA-512,
 * identify the exact signing fingerprint from the protected CI key, and
 * cryptographically verify the exact payload bytes. The authenticated
 * deployment endpoint must expose all four submitted checksum sidecars for
 * every payload, byte-for-byte in bundle format. Central documents that
 * detached signatures do not need checksum sidecars. The public repository
 * contract requires the MD5 and SHA-1 payload sidecars; SHA-256 and SHA-512
 * are verified whenever the repository exposes them.
 */
public final class CentralPublicReconciler {
    private static final URI PUBLIC_BASE =
            URI.create("https://repo1.maven.org/maven2/");
    private static final Pattern VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "\\.(0|[1-9][0-9]*)$"
    );
    private static final long MAX_ARTIFACT_BYTES =
            128L * 1024L * 1024L;
    private static final long MAX_SIGNING_KEY_BYTES =
            1024L * 1024L;
    private static final long MAX_SIGNATURE_BYTES = 64L * 1024L;
    private static final int ERROR_BODY_LIMIT = 64 * 1024;
    private static final Duration CONNECT_TIMEOUT =
            Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(60);
    private static final long POLL_MILLISECONDS = 5_000;
    private static final Map<String, String> CHECKSUMS;

    static {
        Map<String, String> checksums = new LinkedHashMap<>();
        checksums.put(".md5", "MD5");
        checksums.put(".sha1", "SHA-1");
        checksums.put(".sha256", "SHA-256");
        checksums.put(".sha512", "SHA-512");
        CHECKSUMS = Collections.unmodifiableMap(checksums);
    }

    CentralPublicReconciler() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments == null
                || arguments.length != 5
                || (!"preflight".equals(arguments[0])
                && !"wait".equals(arguments[0]))) {
            throw new IllegalArgumentException(
                    "Expected preflight|wait <central-bundle> "
                            + "<version> <signing-key> <state-output>"
            );
        }
        ExpectedRelease expected = ExpectedRelease.fromBundle(
                Path.of(arguments[1]),
                arguments[2],
                Path.of(arguments[3])
        );
        Path output = Path.of(arguments[4]);
        try (HttpTransport transport = new HttpTransport()) {
            CentralPublicReconciler reconciler =
                    new CentralPublicReconciler();
            if ("preflight".equals(arguments[0])) {
                State state = reconciler.preflight(
                        expected,
                        transport,
                        Thread::sleep
                );
                writeState(output, state);
                System.out.println(
                        "Maven Central public preflight: "
                                + state.name().toLowerCase(Locale.ROOT)
                );
            } else {
                reconciler.waitForExact(
                        expected,
                        transport,
                        Thread::sleep,
                        180
                );
                writeState(output, State.EXACT);
                System.out.println(
                        "Maven Central public artifacts and signatures "
                                + "match " + arguments[2]
                );
            }
        } finally {
            expected.erase();
        }
    }

    State preflight(
            ExpectedRelease expected,
            Transport transport,
            Sleeper sleeper
    ) throws Exception {
        IOException lastTransient = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                State state = inspect(expected, transport);
                if (state == State.PARTIAL) {
                    waitForExact(
                            expected,
                            transport,
                            sleeper,
                            120
                    );
                    return State.EXACT;
                }
                return state;
            } catch (TransientReconciliationException exception) {
                lastTransient = exception;
                if (attempt + 1 < 5) {
                    sleeper.sleep(POLL_MILLISECONDS);
                }
            }
        }
        throw lastTransient == null
                ? new IOException(
                "Maven Central public preflight failed"
        )
                : lastTransient;
    }

    void waitForExact(
            ExpectedRelease expected,
            Transport transport,
            Sleeper sleeper,
            int attempts
    ) throws Exception {
        if (attempts < 1 || attempts > 360) {
            throw new IllegalArgumentException(
                    "Publication attempts are outside the safe bound"
            );
        }
        IOException lastTransient = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                if (inspect(expected, transport) == State.EXACT) {
                    return;
                }
                lastTransient = null;
            } catch (TransientReconciliationException exception) {
                lastTransient = exception;
            }
            if (attempt + 1 < attempts) {
                sleeper.sleep(POLL_MILLISECONDS);
            }
        }
        if (lastTransient != null) {
            throw lastTransient;
        }
        throw new IOException(
                "Maven Central did not expose the exact release "
                        + "within the bounded wait"
        );
    }

    State inspect(
            ExpectedRelease expected,
            Transport transport
    ) throws Exception {
        return inspectAtInternal(
                expected,
                transport,
                CentralPublicReconciler::publicUri,
                false
        );
    }

    State inspectAt(
            ExpectedRelease expected,
            Transport transport,
            ArtifactUriResolver uriResolver
    ) throws Exception {
        return inspectAtInternal(
                expected,
                transport,
                uriResolver,
                true
        );
    }

    private State inspectAtInternal(
            ExpectedRelease expected,
            Transport transport,
            ArtifactUriResolver uriResolver,
            boolean requireAllBundleChecksums
    ) throws Exception {
        boolean anyPresent = false;
        boolean complete = true;
        for (ExpectedArtifact artifact : expected.artifacts) {
            Response payload = get(
                    transport,
                    uriResolver,
                    artifact.relativePath,
                    artifact.size
            );
            try {
                boolean payloadPresent = false;
                if (payload.statusCode == 200) {
                    requireExactPayload(payload.body, artifact);
                    payloadPresent = true;
                    anyPresent = true;
                } else if (payload.statusCode != 404) {
                    requireSuccessfulOrMissing(payload.statusCode);
                } else {
                    complete = false;
                }
                Inspection payloadChecksums = inspectChecksums(
                        transport,
                        uriResolver,
                        artifact.relativePath,
                        payload.body,
                        payloadPresent,
                        requireAllBundleChecksums,
                        true
                );
                anyPresent |= payloadChecksums.anyPresent;
                complete &= payloadChecksums.complete;

                Response signature = get(
                        transport,
                        uriResolver,
                        artifact.signaturePath,
                        MAX_SIGNATURE_BYTES
                );
                try {
                    boolean signaturePresent = false;
                    if (signature.statusCode == 200) {
                        signaturePresent = true;
                        anyPresent = true;
                        if (payloadPresent) {
                            expected.signingIdentity.verify(
                                    payload.body,
                                    signature.body,
                                    artifact.signaturePath
                            );
                        }
                    } else if (signature.statusCode != 404) {
                        requireSuccessfulOrMissing(
                                signature.statusCode
                        );
                    } else {
                        complete = false;
                    }
                } finally {
                    signature.erase();
                }
            } finally {
                payload.erase();
            }
        }
        if (!anyPresent) {
            return State.ABSENT;
        }
        if (complete) {
            return State.EXACT;
        }
        return State.PARTIAL;
    }

    private static Inspection inspectChecksums(
            Transport transport,
            ArtifactUriResolver uriResolver,
            String artifactPath,
            byte[] artifactBytes,
            boolean artifactPresent,
            boolean requireAllBundleChecksums,
            boolean requirePublicPayloadChecksums
    ) throws Exception {
        boolean anyPresent = false;
        boolean complete = true;
        for (Map.Entry<String, String> checksum
                : CHECKSUMS.entrySet()) {
            String checksumPath = artifactPath + checksum.getKey();
            Response response = get(
                    transport,
                    uriResolver,
                    checksumPath,
                    1024
            );
            try {
                if (response.statusCode == 200) {
                    anyPresent = true;
                    if (artifactPresent) {
                        requireExactChecksum(
                                response.body,
                                artifactBytes,
                                checksum.getValue(),
                                checksumPath,
                                requireAllBundleChecksums
                        );
                    }
                } else if (response.statusCode != 404) {
                    requireSuccessfulOrMissing(response.statusCode);
                } else if (requireAllBundleChecksums
                        || (requirePublicPayloadChecksums
                        && (".md5".equals(checksum.getKey())
                        || ".sha1".equals(checksum.getKey())))) {
                    complete = false;
                }
            } finally {
                response.erase();
            }
        }
        return new Inspection(anyPresent, complete);
    }

    private static void requireExactChecksum(
            byte[] sidecar,
            byte[] artifact,
            String algorithm,
            String path,
            boolean trailingLineFeed
    ) throws IOException {
        byte[] expected = (
                hexadecimal(digest(algorithm, artifact))
                        + (trailingLineFeed ? "\n" : "")
        ).getBytes(StandardCharsets.US_ASCII);
        try {
            if (!MessageDigest.isEqual(expected, sidecar)) {
                throw new IOException(
                        "Maven Central checksum differs from its "
                                + "artifact: " + path
                );
            }
        } finally {
            Arrays.fill(expected, (byte) 0);
        }
    }

    private static Response get(
            Transport transport,
            ArtifactUriResolver uriResolver,
            String relativePath,
            long maximumSize
    ) throws InterruptedException, TransientReconciliationException {
        try {
            return transport.get(
                    uriResolver.resolve(relativePath),
                    maximumSize
            );
        } catch (IOException exception) {
            throw new TransientReconciliationException(
                    "Maven Central public request failed",
                    exception
            );
        }
    }

    private static void requireExactPayload(
            byte[] body,
            ExpectedArtifact artifact
    ) throws IOException {
        byte[] actual = digest("SHA-256", body);
        try {
            if (body.length != artifact.size
                    || !MessageDigest.isEqual(
                    artifact.sha256,
                    actual
            )) {
                throw new IOException(
                        "Public Maven payload differs from the "
                                + "verified release: "
                                + artifact.relativePath
                );
            }
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static void requireSuccessfulOrMissing(int statusCode)
            throws IOException {
        if (statusCode == 429 || statusCode >= 500) {
            throw new TransientReconciliationException(
                    "Maven Central public request returned HTTP "
                            + statusCode
            );
        }
        throw new IOException(
                "Maven Central public request returned HTTP "
                        + statusCode
        );
    }

    private static URI publicUri(String relativePath) {
        URI uri = PUBLIC_BASE.resolve(relativePath);
        if (!"https".equals(uri.getScheme())
                || !"repo1.maven.org".equals(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || !uri.getPath().startsWith("/maven2/")) {
            throw new IllegalArgumentException(
                    "Public Maven artifact URI is invalid"
            );
        }
        return uri;
    }

    private static void writeState(
            Path output,
            State state
    ) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException(
                    "Maven Central state output parent is unavailable"
            );
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(
                parent,
                ".central-public-state-",
                ".tmp"
        );
        boolean published = false;
        try {
            Files.writeString(
                    temporary,
                    state.name().toLowerCase(Locale.ROOT) + "\n",
                    StandardCharsets.US_ASCII,
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
                        "Atomic Maven Central state publication "
                                + "is unavailable",
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

    enum State {
        ABSENT,
        PARTIAL,
        EXACT
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    interface Transport {
        Response get(URI uri, long maximumSize)
                throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface ArtifactUriResolver {
        URI resolve(String relativePath);
    }

    private static final class Inspection {
        private final boolean anyPresent;
        private final boolean complete;

        private Inspection(
                boolean anyPresent,
                boolean complete
        ) {
            this.anyPresent = anyPresent;
            this.complete = complete;
        }
    }

    static final class Response {
        private final int statusCode;
        private final byte[] body;

        Response(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = Arrays.copyOf(body, body.length);
        }

        void erase() {
            Arrays.fill(body, (byte) 0);
        }
    }

    static final class ExpectedRelease {
        private final List<ExpectedArtifact> artifacts;
        private final SigningIdentity signingIdentity;

        private ExpectedRelease(
                List<ExpectedArtifact> artifacts,
                SigningIdentity signingIdentity
        ) {
            this.artifacts = Collections.unmodifiableList(
                    new ArrayList<>(artifacts)
            );
            this.signingIdentity = signingIdentity;
        }

        static ExpectedRelease fromBundle(
                Path bundle,
                String version,
                Path signingKey
        ) throws Exception {
            if (!VERSION.matcher(version).matches()) {
                throw new IllegalArgumentException(
                        "Maven release version must be stable SemVer"
                );
            }
            SigningIdentity identity = SigningIdentity.fromKeyFile(
                    signingKey
            );
            String prefix = "io/locker/lockersm/" + version
                    + "/lockersm-" + version;
            List<String> payloadNames = List.of(
                    prefix + ".pom",
                    prefix + ".jar",
                    prefix + "-sources.jar",
                    prefix + "-javadoc.jar"
            );
            Set<String> signedNames = new LinkedHashSet<>();
            Set<String> checksumNames = new LinkedHashSet<>(
                    payloadNames
            );
            Set<String> expectedNames = new LinkedHashSet<>();
            for (String payload : payloadNames) {
                signedNames.add(payload);
                signedNames.add(payload + ".asc");
            }
            expectedNames.addAll(signedNames);
            for (String name : checksumNames) {
                for (String suffix : CHECKSUMS.keySet()) {
                    expectedNames.add(name + suffix);
                }
            }

            Map<String, byte[]> bytes = new LinkedHashMap<>();
            try (ZipFile zip = new ZipFile(bundle.toFile())) {
                Map<String, Integer> counts = entryCounts(zip);
                if (!counts.keySet().equals(expectedNames)
                        || counts.values().stream().anyMatch(
                        count -> count != 1
                )) {
                    throw new IOException(
                            "Central bundle entries are not the exact "
                                    + "release set"
                    );
                }
                for (String name : signedNames) {
                    long maximum = name.endsWith(".asc")
                            ? MAX_SIGNATURE_BYTES
                            : MAX_ARTIFACT_BYTES;
                    bytes.put(
                            name,
                            readEntry(zip, name, maximum)
                    );
                }
                verifyChecksums(zip, checksumNames, bytes);
            } catch (Exception exception) {
                CentralPublicReconciler.erase(bytes.values());
                identity.erase();
                throw exception;
            }

            List<ExpectedArtifact> result = new ArrayList<>();
            try {
                for (String payloadName : payloadNames) {
                    byte[] payload = bytes.get(payloadName);
                    String signatureName = payloadName + ".asc";
                    identity.verify(
                            payload,
                            bytes.get(signatureName),
                            signatureName
                    );
                    result.add(new ExpectedArtifact(
                            payloadName,
                            signatureName,
                            payload.length,
                            digest("SHA-256", payload)
                    ));
                }
                return new ExpectedRelease(result, identity);
            } catch (Exception exception) {
                identity.erase();
                for (ExpectedArtifact artifact : result) {
                    artifact.erase();
                }
                throw exception;
            } finally {
                CentralPublicReconciler.erase(bytes.values());
            }
        }

        void erase() {
            for (ExpectedArtifact artifact : artifacts) {
                artifact.erase();
            }
            signingIdentity.erase();
        }

        private static Map<String, Integer> entryCounts(ZipFile zip) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                counts.merge(entry.getName(), 1, Integer::sum);
            }
            return counts;
        }

        private static byte[] readEntry(
                ZipFile zip,
                String name,
                long maximum
        ) throws IOException {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null
                    || entry.isDirectory()
                    || entry.getSize() < 1
                    || entry.getSize() > maximum) {
                throw new IOException(
                        "Central bundle contains an invalid entry: "
                                + name
                );
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return readExact(input, entry.getSize(), maximum);
            }
        }

        private static void verifyChecksums(
                ZipFile zip,
                Set<String> names,
                Map<String, byte[]> bytes
        ) throws IOException {
            for (String name : names) {
                for (Map.Entry<String, String> checksum
                        : CHECKSUMS.entrySet()) {
                    byte[] expected = (
                            hexadecimal(digest(
                                    checksum.getValue(),
                                    bytes.get(name)
                            )) + "\n"
                    ).getBytes(StandardCharsets.US_ASCII);
                    byte[] actual = readEntry(
                            zip,
                            name + checksum.getKey(),
                            1024
                    );
                    try {
                        if (!MessageDigest.isEqual(
                                expected,
                                actual
                        )) {
                            throw new IOException(
                                    "Central bundle checksum differs "
                                            + "from its payload"
                            );
                        }
                    } finally {
                        Arrays.fill(expected, (byte) 0);
                        Arrays.fill(actual, (byte) 0);
                    }
                }
            }
        }
    }

    private static final class ExpectedArtifact {
        private final String relativePath;
        private final String signaturePath;
        private final long size;
        private final byte[] sha256;

        private ExpectedArtifact(
                String relativePath,
                String signaturePath,
                long size,
                byte[] sha256
        ) {
            this.relativePath = relativePath;
            this.signaturePath = signaturePath;
            this.size = size;
            this.sha256 = Arrays.copyOf(sha256, sha256.length);
        }

        private void erase() {
            Arrays.fill(sha256, (byte) 0);
        }
    }

    private static final class SigningIdentity {
        private static final String ARMOR_BEGIN =
                "-----BEGIN PGP SIGNATURE-----\n";
        private static final String ARMOR_END =
                "-----END PGP SIGNATURE-----";

        private final PGPPublicKey publicKey;
        private final byte[] fingerprint;

        private SigningIdentity(PGPPublicKey publicKey) {
            this.publicKey = publicKey;
            this.fingerprint = publicKey.getFingerprint();
        }

        private static SigningIdentity fromKeyFile(Path path)
                throws Exception {
            byte[] keyBytes = readRegularFile(
                    path,
                    MAX_SIGNING_KEY_BYTES,
                    "Maven release signing key"
            );
            try (InputStream decoded = PGPUtil.getDecoderStream(
                    new ByteArrayInputStream(keyBytes)
            )) {
                PGPSecretKeyRingCollection rings =
                        new PGPSecretKeyRingCollection(
                                decoded,
                                new BcKeyFingerprintCalculator()
                        );
                for (PGPSecretKeyRing ring : rings) {
                    for (PGPSecretKey key : ring) {
                        if (!key.isPrivateKeyEmpty()) {
                            return new SigningIdentity(
                                    key.getPublicKey()
                            );
                        }
                    }
                }
                throw new IOException(
                        "Maven release signing key has no private key"
                );
            } catch (PGPException exception) {
                throw new IOException(
                        "Maven release signing key is invalid",
                        exception
                );
            } finally {
                Arrays.fill(keyBytes, (byte) 0);
            }
        }

        private void verify(
                byte[] payload,
                byte[] armoredSignature,
                String label
        ) throws IOException {
            validateArmor(armoredSignature, label);
            try (InputStream decoded = PGPUtil.getDecoderStream(
                    new ByteArrayInputStream(armoredSignature)
            )) {
                PGPObjectFactory objects = new PGPObjectFactory(
                        decoded,
                        new BcKeyFingerprintCalculator()
                );
                Object first = objects.nextObject();
                Object extra = objects.nextObject();
                if (!(first instanceof PGPSignatureList)
                        || ((PGPSignatureList) first).size() != 1
                        || extra != null) {
                    throw new IOException(
                            label + " must contain exactly one "
                                    + "OpenPGP signature packet"
                    );
                }
                PGPSignature signature =
                        ((PGPSignatureList) first).get(0);
                IssuerFingerprint issuer = signature
                        .getHashedSubPackets()
                        .getIssuerFingerprint();
                if (signature.getSignatureType()
                        != PGPSignature.BINARY_DOCUMENT
                        || signature.getHashAlgorithm()
                        != HashAlgorithmTags.SHA512
                        || signature.getKeyID()
                        != publicKey.getKeyID()
                        || issuer == null
                        || !MessageDigest.isEqual(
                        fingerprint,
                        issuer.getFingerprint()
                )) {
                    throw new IOException(
                            label + " does not identify the protected "
                                    + "release signing key"
                    );
                }
                signature.init(
                        new BcPGPContentVerifierBuilderProvider(),
                        publicKey
                );
                signature.update(payload);
                if (!signature.verify()) {
                    throw new IOException(
                            label + " is not valid for its exact payload"
                    );
                }
            } catch (PGPException exception) {
                throw new IOException(
                        label + " is not a valid OpenPGP signature",
                        exception
                );
            }
        }

        private static void validateArmor(
                byte[] value,
                String label
        ) throws IOException {
            if (value == null
                    || value.length < 1
                    || value.length > MAX_SIGNATURE_BYTES) {
                throw new IOException(
                        label + " exceeds its signature bound"
                );
            }
            for (byte character : value) {
                if ((character & 0xff) > 0x7f
                        || (character < 0x20
                        && character != '\r'
                        && character != '\n'
                        && character != '\t')) {
                    throw new IOException(
                            label + " is not strict ASCII armor"
                    );
                }
            }
            String text = new String(
                    value,
                    StandardCharsets.US_ASCII
            ).replace("\r\n", "\n");
            if (text.indexOf('\r') >= 0
                    || !text.startsWith(ARMOR_BEGIN)
                    || count(text, "-----BEGIN PGP SIGNATURE-----") != 1
                    || count(text, ARMOR_END) != 1) {
                throw new IOException(
                        label + " has an invalid armor envelope"
                );
            }
            int end = text.indexOf(ARMOR_END) + ARMOR_END.length();
            if (end != text.length()
                    && !(end + 1 == text.length()
                    && text.charAt(end) == '\n')) {
                throw new IOException(
                        label + " contains data after its signature"
                );
            }
        }

        private static int count(String value, String needle) {
            int count = 0;
            int offset = 0;
            while ((offset = value.indexOf(needle, offset)) >= 0) {
                count++;
                offset += needle.length();
            }
            return count;
        }

        private void erase() {
            Arrays.fill(fingerprint, (byte) 0);
        }
    }

    private static byte[] readRegularFile(
            Path path,
            long maximum,
            String label
    ) throws IOException {
        BasicFileAttributes before = Files.readAttributes(
                path,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!before.isRegularFile()
                || before.size() < 1
                || before.size() > maximum) {
            throw new IOException(
                    label + " must be a bounded regular non-symlink file"
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
                    label + " changed while it was read"
            );
        }
        return bytes;
    }

    private static byte[] readExact(
            InputStream stream,
            long expectedSize,
            long maximum
    ) throws IOException {
        if (expectedSize < 1
                || expectedSize > maximum
                || expectedSize > Integer.MAX_VALUE) {
            throw new IOException(
                    "Maven artifact size is outside its bound"
            );
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(expectedSize, 8192)
        );
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total = Math.addExact(total, read);
            if (total > expectedSize) {
                throw new IOException(
                        "Maven artifact exceeds its expected size"
                );
            }
            output.write(buffer, 0, read);
        }
        if (total != expectedSize) {
            throw new IOException("Maven artifact is truncated");
        }
        return output.toByteArray();
    }

    private static byte[] digest(
            String algorithm,
            byte[] value
    ) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    algorithm + " is unavailable",
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

    private static final class TransientReconciliationException
            extends IOException {
        private static final long serialVersionUID = 1L;

        private TransientReconciliationException(String message) {
            super(message);
        }

        private TransientReconciliationException(
                String message,
                Throwable cause
        ) {
            super(message, cause);
        }
    }

    private static final class HttpTransport
            implements Transport, AutoCloseable {
        private final ExecutorService executor;
        private final HttpClient client;

        private HttpTransport() {
            executor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "locker-central-public-http"
                );
                thread.setDaemon(true);
                return thread;
            });
            client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .executor(executor)
                    .build();
        }

        @Override
        public Response get(URI uri, long maximumSize)
                throws IOException, InterruptedException {
            publicUri(
                    uri.getRawPath().substring("/maven2/".length())
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header(
                            "User-Agent",
                            "LockerSM-Java-Release/1"
                    )
                    .GET()
                    .build();
            HttpResponse<InputStream> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            long maximum = response.statusCode() == 200
                    ? maximumSize
                    : ERROR_BODY_LIMIT;
            byte[] body;
            try (InputStream stream = response.body()) {
                body = readAtMost(stream, maximum);
            }
            return new Response(response.statusCode(), body);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static byte[] readAtMost(
            InputStream stream,
            long maximum
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(
                (int) Math.min(maximum, 8192)
        );
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total = Math.addExact(total, read);
            if (total > maximum) {
                throw new IOException(
                        "Maven Central response exceeds its size limit"
                );
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
