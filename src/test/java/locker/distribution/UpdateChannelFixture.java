package locker.distribution;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class UpdateChannelFixture {
    private static final byte[] SEED = new byte[]{
            0, 1, 2, 3, 4, 5, 6, 7,
            8, 9, 10, 11, 12, 13, 14, 15,
            16, 17, 18, 19, 20, 21, 22, 23,
            24, 25, 26, 27, 28, 29, 30, 31
    };
    private static final String SOURCE_COMMIT = "a".repeat(40);
    private static final List<Target> TARGETS = List.of(
            new Target("linux", "amd64", "locker-linux-amd64"),
            new Target("linux", "arm64", "locker-linux-arm64"),
            new Target("darwin", "amd64", "locker-darwin-amd64"),
            new Target("darwin", "arm64", "locker-darwin-arm64"),
            new Target(
                    "windows",
                    "amd64",
                    "locker-windows-amd64.exe"
            )
    );

    final byte[] publicKey;
    final byte[] binary;
    final byte[] binarySignature;
    final byte[] manifestEnvelope;
    final byte[] latestEnvelope;
    final String version;
    final Map<URI, byte[]> objects;

    private UpdateChannelFixture(
            byte[] publicKey,
            byte[] binary,
            byte[] binarySignature,
            byte[] manifestEnvelope,
            byte[] latestEnvelope,
            String version,
            Map<URI, byte[]> objects
    ) {
        this.publicKey = publicKey;
        this.binary = binary;
        this.binarySignature = binarySignature;
        this.manifestEnvelope = manifestEnvelope;
        this.latestEnvelope = latestEnvelope;
        this.version = version;
        this.objects = Collections.unmodifiableMap(objects);
    }

    static UpdateChannelFixture create(String version)
            throws Exception {
        return create(
                version,
                executable("linux", "amd64")
        );
    }

    static byte[] executable(String os, String arch) {
        byte[] value = new byte[256];
        if ("linux".equals(os)) {
            value[0] = 0x7f;
            value[1] = 'E';
            value[2] = 'L';
            value[3] = 'F';
            value[4] = 2;
            value[5] = 1;
            int machine = "amd64".equals(arch)
                    ? 0x3e
                    : 0xb7;
            value[18] = (byte) machine;
            value[19] = (byte) (machine >>> 8);
            return value;
        }
        if ("darwin".equals(os)) {
            value[0] = (byte) 0xcf;
            value[1] = (byte) 0xfa;
            value[2] = (byte) 0xed;
            value[3] = (byte) 0xfe;
            int cpu = "amd64".equals(arch)
                    ? 0x01000007
                    : 0x0100000c;
            putLittleEndian32(value, 4, cpu);
            return value;
        }
        if ("windows".equals(os) && "amd64".equals(arch)) {
            value[0] = 'M';
            value[1] = 'Z';
            putLittleEndian32(value, 60, 128);
            value[128] = 'P';
            value[129] = 'E';
            value[132] = 0x64;
            value[133] = (byte) 0x86;
            return value;
        }
        throw new IllegalArgumentException(
                "Unsupported executable fixture target"
        );
    }

    static UpdateChannelFixture create(
            String version,
            byte[] binary
    ) throws Exception {
        byte[] selectedBinary = binary.clone();
        Ed25519PrivateKeyParameters privateKey =
                new Ed25519PrivateKeyParameters(SEED, 0);
        byte[] publicKey = privateKey.generatePublicKey().getEncoded();
        byte[] binarySignature = sign(privateKey, selectedBinary);

        JsonObject manifestPayload = manifestPayload(
                version,
                selectedBinary
        );
        byte[] manifest = signedEnvelope(
                privateKey,
                manifestPayload
        );
        JsonObject latestPayload = latestPayload(version, manifest);
        byte[] latest = signedEnvelope(privateKey, latestPayload);

        Map<URI, byte[]> objects = new HashMap<>();
        objects.put(
                uri("latest.json"),
                latest.clone()
        );
        objects.put(
                uri(version + "/manifest.json"),
                manifest.clone()
        );
        objects.put(
                uri(version + "/locker-linux-amd64"),
                binary.clone()
        );
        objects.put(
                uri(version + "/locker-linux-amd64.sig"),
                binarySignature.clone()
        );
        return new UpdateChannelFixture(
                publicKey,
                selectedBinary,
                binarySignature,
                manifest,
                latest,
                version,
                objects
        );
    }

    static byte[] signedEnvelope(
            Ed25519PrivateKeyParameters privateKey,
            JsonObject payload
    ) throws Exception {
        byte[] payloadBytes = SignedUpdateContract.canonicalize(payload);
        byte[] signature = sign(privateKey, payloadBytes);
        JsonObject envelope = new JsonObject();
        envelope.addProperty("algorithm", "Ed25519");
        envelope.addProperty("key_id", "locker-cli-release-v1");
        envelope.addProperty(
                "payload",
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(payloadBytes)
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
        Arrays.fill(payloadBytes, (byte) 0);
        Arrays.fill(signature, (byte) 0);
        Arrays.fill(canonical, (byte) 0);
        return file;
    }

    static Ed25519PrivateKeyParameters privateKey() {
        return new Ed25519PrivateKeyParameters(SEED, 0);
    }

    static byte[] sign(
            Ed25519PrivateKeyParameters privateKey,
            byte[] bytes
    ) {
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(bytes, 0, bytes.length);
        return signer.generateSignature();
    }

    private static JsonObject manifestPayload(
            String version,
            byte[] selectedBinary
    ) throws Exception {
        JsonArray artifacts = new JsonArray();
        for (int index = 0; index < TARGETS.size(); index++) {
            Target target = TARGETS.get(index);
            byte[] bytes = index == 0
                    ? selectedBinary
                    : ("fixture-" + target.filename).getBytes(
                    StandardCharsets.US_ASCII
            );
            JsonObject artifact = new JsonObject();
            artifact.addProperty("arch", target.arch);
            artifact.addProperty("filename", target.filename);
            artifact.addProperty("os", target.os);
            artifact.addProperty(
                    "path",
                    version + "/" + target.filename
            );
            artifact.addProperty(
                    "sha256",
                    SignedUpdateContract.sha256(bytes)
            );
            artifact.addProperty(
                    "signature_path",
                    version + "/" + target.filename + ".sig"
            );
            artifact.addProperty("size", bytes.length);
            artifacts.add(artifact);
        }

        JsonObject protocol = new JsonObject();
        protocol.addProperty("max_version", 1);
        protocol.addProperty("min_version", 1);
        protocol.addProperty("name", "locker.sdk");
        protocol.addProperty("transport", "json-rpc-2.0-stdio");

        JsonObject payload = new JsonObject();
        payload.add("artifacts", artifacts);
        payload.addProperty("product", "locker-cli");
        payload.add("protocol", protocol);
        payload.addProperty(
                "schema",
                "io.locker.cli.update-manifest"
        );
        payload.addProperty("schema_version", 2);
        payload.addProperty("source_commit", SOURCE_COMMIT);
        payload.addProperty("version", version);
        return payload;
    }

    private static JsonObject latestPayload(
            String version,
            byte[] manifestEnvelope
    ) throws Exception {
        JsonObject pointer = new JsonObject();
        pointer.addProperty("path", version + "/manifest.json");
        pointer.addProperty(
                "sha256",
                SignedUpdateContract.sha256(manifestEnvelope)
        );
        pointer.addProperty("size", manifestEnvelope.length);

        JsonObject payload = new JsonObject();
        payload.add("manifest", pointer);
        payload.addProperty("product", "locker-cli");
        payload.addProperty(
                "schema",
                "io.locker.cli.update-latest"
        );
        payload.addProperty("schema_version", 2);
        payload.addProperty("source_commit", SOURCE_COMMIT);
        payload.addProperty("version", version);
        return payload;
    }

    private static URI uri(String relative) {
        return URI.create(SignedUpdateContract.BASE_URL + relative);
    }

    private static void putLittleEndian32(
            byte[] bytes,
            int offset,
            int value
    ) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    static final class FakeTransport
            implements LockerCliInstaller.DownloadTransport {
        private final Map<URI, ResponseSpec> responses =
                Collections.synchronizedMap(new HashMap<>());
        private final List<URI> requests =
                Collections.synchronizedList(new ArrayList<>());
        private volatile boolean unavailable;

        FakeTransport(UpdateChannelFixture fixture) {
            fixture.objects.forEach(
                    (uri, bytes) -> responses.put(
                            uri,
                            new ResponseSpec(200, bytes)
                    )
            );
        }

        void replace(UpdateChannelFixture fixture) {
            responses.clear();
            fixture.objects.forEach(
                    (uri, bytes) -> responses.put(
                            uri,
                            new ResponseSpec(200, bytes)
                    )
            );
        }

        void put(URI uri, int status, byte[] bytes) {
            responses.put(uri, new ResponseSpec(status, bytes));
        }

        void setUnavailable(boolean value) {
            unavailable = value;
        }

        int requestCount() {
            return requests.size();
        }

        List<URI> requests() {
            synchronized (requests) {
                return List.copyOf(requests);
            }
        }

        @Override
        public LockerCliInstaller.DownloadedResponse open(
                URI uri,
                long maximumBytes
        ) throws IOException {
            requests.add(uri);
            if (unavailable) {
                throw new IOException("fixture network unavailable");
            }
            ResponseSpec response = responses.get(uri);
            if (response == null) {
                return new LockerCliInstaller.DownloadedResponse(
                        404,
                        Optional.of(0L),
                        new ByteArrayInputStream(new byte[0])
                );
            }
            return new LockerCliInstaller.DownloadedResponse(
                    response.status,
                    Optional.of((long) response.bytes.length),
                    new ByteArrayInputStream(response.bytes.clone())
            );
        }
    }

    private static final class ResponseSpec {
        private final int status;
        private final byte[] bytes;

        private ResponseSpec(int status, byte[] bytes) {
            this.status = status;
            this.bytes = bytes.clone();
        }
    }

    private static final class Target {
        private final String os;
        private final String arch;
        private final String filename;

        private Target(String os, String arch, String filename) {
            this.os = os;
            this.arch = arch;
            this.filename = filename;
        }
    }
}
