package locker.distribution;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SignedUpdateContractTest {
    private static final String FIXTURE_SHA256 =
            "116ffe19be6d2f45555ec0f1e1a662ea"
                    + "860d08a48520d4bfb59c8c11cc2f0b47";
    private static final String LATEST_CANONICAL_SHA256 =
            "dd32ad36e2ac2fac72220ad8ad8b72da"
                    + "3200799d7d33e7c93f08aa5221b2b22c";
    private static final String MANIFEST_CANONICAL_SHA256 =
            "dbac1da6c487aac212fb9cf18cc54798"
                    + "3749d226f4958de849d00d15116e6212";

    @Test
    public void copiedSharedFixtureAndCanonicalHashesAreExact()
            throws Exception {
        byte[] fixtureBytes = resourceBytes(
                "/update-channel-v2.json"
        );
        assertEquals(FIXTURE_SHA256, sha256(fixtureBytes));

        JsonObject fixture = JsonParser.parseString(
                new String(fixtureBytes, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        byte[] latest = SignedUpdateContract.canonicalize(
                fixture.get("latest_payload")
        );
        byte[] manifest = SignedUpdateContract.canonicalize(
                fixture.get("manifest_payload")
        );
        try {
            assertEquals(
                    LATEST_CANONICAL_SHA256,
                    sha256(latest)
            );
            assertEquals(
                    MANIFEST_CANONICAL_SHA256,
                    sha256(manifest)
            );
        } finally {
            Arrays.fill(fixtureBytes, (byte) 0);
            Arrays.fill(latest, (byte) 0);
            Arrays.fill(manifest, (byte) 0);
        }
    }

    @Test
    public void comparesEveryStableMajorTwoReleaseWithoutOverflow()
            throws Exception {
        assertEquals(
                1,
                SignedUpdateContract.compareVersions(
                        "2.1.0",
                        "2.0.999"
                )
        );
        assertEquals(
                -1,
                SignedUpdateContract.compareVersions(
                        "2.0.1000",
                        "2.1.0"
                )
        );
        assertEquals(
                1,
                SignedUpdateContract.compareVersions(
                        "2.12345678901234567890.0",
                        "2.9999999999999999999."
                                + "99999999999999999999"
                )
        );
        assertEquals(
                0,
                SignedUpdateContract.compareVersions(
                        "2.1.7",
                        "2.1.7"
                )
        );
    }

    @Test
    public void rejectsVersionsOutsideStableMajorTwo() {
        for (String invalid : new String[]{
                "2.01.0",
                "2.0.01",
                "2.0",
                "2.0.0-rc.1",
                "3.0.0"
        }) {
            assertThrows(
                    CliDistributionException.class,
                    () -> SignedUpdateContract.compareVersions(
                            invalid,
                            "2.0.0"
                    )
            );
        }
    }

    @Test
    public void verifiesGeneratedEnvelopeChainAndBinarySignature()
            throws Exception {
        UpdateChannelFixture fixture =
                UpdateChannelFixture.create("2.0.7");

        SignedUpdateContract.Latest latest =
                SignedUpdateContract.verifyLatest(
                        fixture.latestEnvelope,
                        fixture.publicKey
                );
        SignedUpdateContract.Manifest manifest =
                SignedUpdateContract.verifyManifest(
                        fixture.manifestEnvelope,
                        fixture.publicKey
                );
        SignedUpdateContract.bind(
                latest,
                manifest,
                fixture.manifestEnvelope
        );
        SignedUpdateContract.Artifact artifact = manifest.artifactFor(
                PlatformIdentity.from("linux", "amd64")
        );
        SignedUpdateContract.verifySignature(
                fixture.publicKey,
                fixture.binarySignature,
                fixture.binary
        );

        assertEquals("2.0.7", latest.getVersion());
        assertEquals(
                "locker-linux-amd64",
                artifact.getFilename()
        );
    }

    @Test
    public void rejectsNonCanonicalDuplicateFloatAndNonAsciiPayloads()
            throws Exception {
        UpdateChannelFixture fixture =
                UpdateChannelFixture.create("2.0.7");
        byte[] outerWhitespace = Arrays.copyOf(
                fixture.latestEnvelope,
                fixture.latestEnvelope.length + 1
        );
        outerWhitespace[outerWhitespace.length - 2] = ' ';
        outerWhitespace[outerWhitespace.length - 1] = '\n';
        assertThrows(
                CliDistributionException.class,
                () -> SignedUpdateContract.verifyLatest(
                        outerWhitespace,
                        fixture.publicKey
                )
        );

        for (String invalidPayload : new String[]{
                "{\"manifest\":{},\"product\":\"locker-cli\","
                        + "\"schema\":\"io.locker.cli.update-latest\","
                        + "\"schema_version\":2,\"schema_version\":2,"
                        + "\"source_commit\":\"" + "a".repeat(40) + "\","
                        + "\"version\":\"2.0.7\"}",
                "{\"manifest\":{},\"product\":\"locker-cli\","
                        + "\"schema\":\"io.locker.cli.update-latest\","
                        + "\"schema_version\":2.0,"
                        + "\"source_commit\":\"" + "a".repeat(40) + "\","
                        + "\"version\":\"2.0.7\"}",
                "{\"manifest\":{},\"product\":\"locker-clí\","
                        + "\"schema\":\"io.locker.cli.update-latest\","
                        + "\"schema_version\":2,"
                        + "\"source_commit\":\"" + "a".repeat(40) + "\","
                        + "\"version\":\"2.0.7\"}"
        }) {
            byte[] envelope = rawSignedEnvelope(
                    invalidPayload.getBytes(StandardCharsets.UTF_8)
            );
            assertThrows(
                    CliDistributionException.class,
                    () -> SignedUpdateContract.verifyLatest(
                            envelope,
                            fixture.publicKey
                    )
            );
        }
    }

    @Test
    public void rejectsDepthAbove64AndIntegersOutsideInt64()
            throws Exception {
        UpdateChannelFixture fixture =
                UpdateChannelFixture.create("2.0.7");
        String nested = "null";
        for (int depth = 0; depth < 65; depth++) {
            nested = "{\"a\":" + nested + "}";
        }
        byte[] deepEnvelope = rawSignedEnvelope(
                nested.getBytes(StandardCharsets.US_ASCII)
        );
        assertThrows(
                CliDistributionException.class,
                () -> SignedUpdateContract.verifyLatest(
                        deepEnvelope,
                        fixture.publicKey
                )
        );

        String oversized = "{\"manifest\":{},"
                + "\"product\":\"locker-cli\","
                + "\"schema\":\"io.locker.cli.update-latest\","
                + "\"schema_version\":9223372036854775808,"
                + "\"source_commit\":\"" + "a".repeat(40) + "\","
                + "\"version\":\"2.0.7\"}";
        byte[] oversizedEnvelope = rawSignedEnvelope(
                oversized.getBytes(StandardCharsets.US_ASCII)
        );
        assertThrows(
                CliDistributionException.class,
                () -> SignedUpdateContract.verifyLatest(
                        oversizedEnvelope,
                        fixture.publicKey
                )
        );
    }

    @Test
    public void canonicalWriterMatchesPythonEscapesAndOrdering()
            throws Exception {
        JsonObject object = new JsonObject();
        object.addProperty("z", "\0\b\u000b\f\u001f\u007f");
        object.addProperty("a", 2);

        assertArrayEquals(
                (
                        "{\"a\":2,\"z\":\""
                                + "\\u0000\\b\\u000b\\f"
                                + "\\u001f\\u007f\"}"
                ).getBytes(StandardCharsets.US_ASCII),
                SignedUpdateContract.canonicalize(object)
        );
    }

    private static byte[] rawSignedEnvelope(byte[] payload)
            throws Exception {
        Ed25519PrivateKeyParameters privateKey =
                UpdateChannelFixture.privateKey();
        byte[] signature = UpdateChannelFixture.sign(
                privateKey,
                payload
        );
        JsonObject envelope = new JsonObject();
        envelope.addProperty("algorithm", "Ed25519");
        envelope.addProperty("key_id", "locker-cli-release-v1");
        envelope.addProperty(
                "payload",
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payload)
        );
        envelope.addProperty(
                "schema",
                "io.locker.cli.signed-envelope"
        );
        envelope.addProperty("schema_version", 2);
        envelope.addProperty(
                "signature",
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(signature)
        );
        byte[] canonical = SignedUpdateContract.canonicalize(envelope);
        byte[] file = Arrays.copyOf(canonical, canonical.length + 1);
        file[file.length - 1] = '\n';
        return file;
    }

    private static byte[] resourceBytes(String name) throws Exception {
        try (InputStream input = SignedUpdateContractTest.class
                .getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing test resource " + name
                );
            }
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest
                .getInstance("SHA-256")
                .digest(bytes);
        StringBuilder result = new StringBuilder();
        for (byte value : digest) {
            result.append(
                    Character.forDigit((value >>> 4) & 0x0f, 16)
            );
            result.append(Character.forDigit(value & 0x0f, 16));
        }
        return result.toString();
    }
}
