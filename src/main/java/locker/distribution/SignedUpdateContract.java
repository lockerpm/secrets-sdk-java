package locker.distribution;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strict verifier for the Locker CLI signed update-channel v2 contract.
 */
final class SignedUpdateContract {
    static final String BASE_URL = "https://files.locker.io/cli/releases/";
    static final String KEY_ID = "locker-cli-release-v1";
    static final int MAX_LATEST_BYTES = 64 * 1024;
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    static final long MAX_BINARY_BYTES = 256L * 1024L * 1024L;
    static final int MAX_JSON_DEPTH = 64;
    static final int PUBLIC_KEY_BYTES = 32;
    static final int SIGNATURE_BYTES = 64;

    private static final String ENVELOPE_SCHEMA =
            "io.locker.cli.signed-envelope";
    private static final String LATEST_SCHEMA =
            "io.locker.cli.update-latest";
    private static final String MANIFEST_SCHEMA =
            "io.locker.cli.update-manifest";
    private static final String PRODUCT = "locker-cli";
    private static final String ALGORITHM = "Ed25519";
    private static final Pattern VERSION_PATTERN =
            Pattern.compile(
                    "^2\\.(0|[1-9][0-9]*)"
                            + "\\.(0|[1-9][0-9]*)$"
            );
    private static final Pattern COMMIT_PATTERN =
            Pattern.compile("^(?:[0-9a-f]{40}|[0-9a-f]{64})$");
    private static final Pattern SHA256_PATTERN =
            Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern BASE64URL_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final BigInteger MIN_INT64 =
            BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger MAX_INT64 =
            BigInteger.valueOf(Long.MAX_VALUE);
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "algorithm",
            "key_id",
            "payload",
            "schema",
            "schema_version",
            "signature"
    );
    private static final Set<String> LATEST_FIELDS = Set.of(
            "manifest",
            "product",
            "schema",
            "schema_version",
            "source_commit",
            "version"
    );
    private static final Set<String> MANIFEST_POINTER_FIELDS = Set.of(
            "path",
            "sha256",
            "size"
    );
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "artifacts",
            "product",
            "protocol",
            "schema",
            "schema_version",
            "source_commit",
            "version"
    );
    private static final Set<String> PROTOCOL_FIELDS = Set.of(
            "max_version",
            "min_version",
            "name",
            "transport"
    );
    private static final Set<String> ARTIFACT_FIELDS = Set.of(
            "arch",
            "filename",
            "os",
            "path",
            "sha256",
            "signature_path",
            "size"
    );
    private static final Map<String, Target> TARGETS;

    static {
        Map<String, Target> targets = new HashMap<>();
        addTarget(targets, "linux", "amd64", "locker-linux-amd64");
        addTarget(targets, "linux", "arm64", "locker-linux-arm64");
        addTarget(targets, "darwin", "amd64", "locker-darwin-amd64");
        addTarget(targets, "darwin", "arm64", "locker-darwin-arm64");
        addTarget(
                targets,
                "windows",
                "amd64",
                "locker-windows-amd64.exe"
        );
        TARGETS = Collections.unmodifiableMap(targets);
    }

    private SignedUpdateContract() {
    }

    static Latest verifyLatest(
            byte[] envelopeBytes,
            byte[] publicKey
    ) throws CliDistributionException {
        JsonObject payload = verifyEnvelope(
                envelopeBytes,
                publicKey,
                LATEST_SCHEMA,
                MAX_LATEST_BYTES
        );
        requireExactFields(payload, LATEST_FIELDS, "latest payload");
        requireIdentity(payload, LATEST_SCHEMA, "latest payload");

        String version = requireVersion(payload, "version");
        String sourceCommit = requireCommit(
                payload,
                "source_commit"
        );
        JsonObject pointer = requireObject(
                payload,
                "manifest",
                "latest manifest pointer"
        );
        requireExactFields(
                pointer,
                MANIFEST_POINTER_FIELDS,
                "latest manifest pointer"
        );
        String manifestPath = requireString(pointer, "path");
        if (!(version + "/manifest.json").equals(manifestPath)) {
            throw invalid(
                    "Latest manifest path does not match its version"
            );
        }
        String manifestSha256 = requireSha256(
                pointer,
                "sha256"
        );
        long manifestSize = requireInteger(pointer, "size");
        if (manifestSize < 1
                || manifestSize > MAX_MANIFEST_BYTES) {
            throw invalid(
                    "Latest manifest size is outside v2 bounds"
            );
        }
        return new Latest(
                version,
                sourceCommit,
                manifestPath,
                manifestSha256,
                manifestSize
        );
    }

    static Manifest verifyManifest(
            byte[] envelopeBytes,
            byte[] publicKey
    ) throws CliDistributionException {
        JsonObject payload = verifyEnvelope(
                envelopeBytes,
                publicKey,
                MANIFEST_SCHEMA,
                MAX_MANIFEST_BYTES
        );
        requireExactFields(
                payload,
                MANIFEST_FIELDS,
                "manifest payload"
        );
        requireIdentity(payload, MANIFEST_SCHEMA, "manifest payload");
        String version = requireVersion(payload, "version");
        String sourceCommit = requireCommit(
                payload,
                "source_commit"
        );

        JsonObject protocol = requireObject(
                payload,
                "protocol",
                "manifest protocol"
        );
        requireExactFields(
                protocol,
                PROTOCOL_FIELDS,
                "manifest protocol"
        );
        if (requireInteger(protocol, "min_version") != 1
                || requireInteger(protocol, "max_version") != 1
                || !"locker.sdk".equals(
                requireString(protocol, "name")
        )
                || !"json-rpc-2.0-stdio".equals(
                requireString(protocol, "transport")
        )) {
            throw invalid(
                    "Manifest protocol does not match Locker SDK v1"
            );
        }

        JsonElement artifactsElement = payload.get("artifacts");
        if (artifactsElement == null
                || !artifactsElement.isJsonArray()) {
            throw invalid("Manifest artifacts must be an array");
        }
        JsonArray artifactArray = artifactsElement.getAsJsonArray();
        if (artifactArray.size() != TARGETS.size()) {
            throw invalid(
                    "Manifest must contain exactly five artifacts"
            );
        }

        Map<String, Artifact> artifacts = new HashMap<>();
        for (JsonElement element : artifactArray) {
            if (!element.isJsonObject()) {
                throw invalid(
                        "Manifest artifact must be an object"
                );
            }
            JsonObject object = element.getAsJsonObject();
            requireExactFields(
                    object,
                    ARTIFACT_FIELDS,
                    "manifest artifact"
            );
            String filename = requireString(object, "filename");
            Target target = TARGETS.get(filename);
            if (target == null
                    || !target.os.equals(
                    requireString(object, "os")
            )
                    || !target.arch.equals(
                    requireString(object, "arch")
            )) {
                throw invalid(
                        "Manifest artifact target does not match "
                                + "its filename"
                );
            }
            String path = requireString(object, "path");
            String signaturePath = requireString(
                    object,
                    "signature_path"
            );
            if (!(version + "/" + filename).equals(path)
                    || !(path + ".sig").equals(signaturePath)
                    || path.length() > 320
                    || signaturePath.length() > 324) {
                throw invalid(
                        "Manifest artifact path is not canonical"
                );
            }
            String sha256 = requireSha256(object, "sha256");
            long size = requireInteger(object, "size");
            if (size < 1 || size > MAX_BINARY_BYTES) {
                throw invalid(
                        "Manifest artifact size is outside v2 bounds"
                );
            }
            Artifact artifact = new Artifact(
                    target.os,
                    target.arch,
                    filename,
                    path,
                    signaturePath,
                    sha256,
                    size
            );
            if (artifacts.put(filename, artifact) != null) {
                throw invalid(
                        "Manifest contains a duplicate platform artifact"
                );
            }
        }
        if (!artifacts.keySet().equals(TARGETS.keySet())) {
            throw invalid("Manifest platform set is incomplete");
        }
        return new Manifest(
                version,
                sourceCommit,
                artifacts
        );
    }

    static void bind(
            Latest latest,
            Manifest manifest,
            byte[] manifestEnvelope
    ) throws CliDistributionException {
        if (!latest.version.equals(manifest.version)
                || !latest.sourceCommit.equals(
                manifest.sourceCommit
        )
                || latest.manifestSize != manifestEnvelope.length
                || !constantTimeEquals(
                latest.manifestSha256,
                sha256(manifestEnvelope)
        )) {
            throw invalid(
                    "Latest pointer does not bind the signed manifest"
            );
        }
    }

    static void requireNotOlder(
            String candidate,
            String accepted
    ) throws CliDistributionException {
        if (accepted == null) {
            return;
        }
        if (compareVersions(candidate, accepted) < 0) {
            throw invalid(
                    "Locker CLI update channel attempted a downgrade"
            );
        }
    }

    static void verifySignature(
            byte[] publicKey,
            byte[] signature,
            byte[] bytes
    ) throws CliDistributionException {
        requirePublicKey(publicKey);
        if (signature == null
                || signature.length != SIGNATURE_BYTES
                || bytes == null) {
            throw invalid("Locker CLI Ed25519 signature is invalid");
        }
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(
                false,
                new Ed25519PublicKeyParameters(publicKey, 0)
        );
        verifier.update(bytes, 0, bytes.length);
        if (!verifier.verifySignature(signature)) {
            throw invalid("Locker CLI Ed25519 signature is invalid");
        }
    }

    static Ed25519Signer newStreamingVerifier(byte[] publicKey)
            throws CliDistributionException {
        requirePublicKey(publicKey);
        Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(
                false,
                new Ed25519PublicKeyParameters(publicKey, 0)
        );
        return verifier;
    }

    static byte[] decodePublicKey(String encoded)
            throws CliDistributionException {
        if (encoded == null
                || encoded.isBlank()
                || !encoded.equals(encoded.trim())) {
            throw invalid(
                    "The embedded Locker CLI release public key is blank"
            );
        }
        return decodeBase64Url(
                encoded,
                PUBLIC_KEY_BYTES,
                PUBLIC_KEY_BYTES,
                "release public key"
        );
    }

    static String sha256(byte[] bytes)
            throws CliDistributionException {
        try {
            byte[] digest = java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes);
            try {
                return hexadecimal(digest);
            } finally {
                Arrays.fill(digest, (byte) 0);
            }
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new CliDistributionException(
                    "SHA-256 is unavailable",
                    exception
            );
        }
    }

    static byte[] canonicalize(JsonElement value)
            throws CliDistributionException {
        StringBuilder output = new StringBuilder();
        appendCanonical(value, output, 0);
        return output.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static JsonObject verifyEnvelope(
            byte[] envelopeBytes,
            byte[] publicKey,
            String expectedPayloadSchema,
            int maximumBytes
    ) throws CliDistributionException {
        requirePublicKey(publicKey);
        if (envelopeBytes == null
                || envelopeBytes.length < 2
                || envelopeBytes.length > maximumBytes) {
            throw invalid(
                    "Signed Locker CLI update envelope is outside "
                            + "its size bound"
            );
        }
        validateRawNesting(envelopeBytes);
        JsonElement parsed = StrictJson.parse(
                envelopeBytes,
                MAX_JSON_DEPTH
        );
        if (!parsed.isJsonObject()) {
            throw invalid(
                    "Signed Locker CLI update envelope must be an object"
            );
        }
        JsonObject envelope = parsed.getAsJsonObject();
        requireExactFields(
                envelope,
                ENVELOPE_FIELDS,
                "signed envelope"
        );
        requireAsciiTree(envelope, 0);
        if (!ALGORITHM.equals(
                requireString(envelope, "algorithm")
        )
                || !KEY_ID.equals(
                requireString(envelope, "key_id")
        )
                || !ENVELOPE_SCHEMA.equals(
                requireString(envelope, "schema")
        )
                || requireInteger(
                envelope,
                "schema_version"
        ) != 2) {
            throw invalid("Signed update envelope identity is invalid");
        }

        byte[] canonicalEnvelope = canonicalize(envelope);
        byte[] canonicalFile = Arrays.copyOf(
                canonicalEnvelope,
                canonicalEnvelope.length + 1
        );
        canonicalFile[canonicalFile.length - 1] = '\n';
        try {
            if (!MessageDigest.isEqual(
                    envelopeBytes,
                    canonicalFile
            )) {
                throw invalid(
                        "Signed update envelope is not canonical JSON "
                                + "plus one LF"
                );
            }
        } finally {
            Arrays.fill(canonicalEnvelope, (byte) 0);
            Arrays.fill(canonicalFile, (byte) 0);
        }

        byte[] payloadBytes = decodeBase64Url(
                requireString(envelope, "payload"),
                1,
                maximumBytes,
                "signed payload"
        );
        byte[] signature = decodeBase64Url(
                requireString(envelope, "signature"),
                SIGNATURE_BYTES,
                SIGNATURE_BYTES,
                "envelope signature"
        );
        try {
            validateRawNesting(payloadBytes);
            JsonElement payloadElement = StrictJson.parse(
                    payloadBytes,
                    MAX_JSON_DEPTH
            );
            if (!payloadElement.isJsonObject()) {
                throw invalid(
                        "Signed update payload must be an object"
                );
            }
            JsonObject payload = payloadElement.getAsJsonObject();
            requireAsciiTree(payload, 0);
            byte[] canonicalPayload = canonicalize(payload);
            try {
                if (!MessageDigest.isEqual(
                        payloadBytes,
                        canonicalPayload
                )) {
                    throw invalid(
                            "Signed update payload is not canonical JSON"
                    );
                }
            } finally {
                Arrays.fill(canonicalPayload, (byte) 0);
            }
            verifySignature(publicKey, signature, payloadBytes);
            if (!expectedPayloadSchema.equals(
                    requireString(payload, "schema")
            )
                    || requireInteger(
                    payload,
                    "schema_version"
            ) != 2) {
                throw invalid("Signed update payload schema is invalid");
            }
            return payload;
        } finally {
            Arrays.fill(payloadBytes, (byte) 0);
            Arrays.fill(signature, (byte) 0);
        }
    }

    private static void appendCanonical(
            JsonElement value,
            StringBuilder output,
            int depth
    ) throws CliDistributionException {
        if (depth > MAX_JSON_DEPTH) {
            throw invalid("Canonical JSON exceeds the depth limit");
        }
        if (value == null || value.isJsonNull()) {
            output.append("null");
            return;
        }
        if (value.isJsonObject()) {
            output.append('{');
            List<Map.Entry<String, JsonElement>> fields =
                    new ArrayList<>(
                            value.getAsJsonObject().entrySet()
                    );
            fields.sort(Comparator.comparing(Map.Entry::getKey));
            boolean first = true;
            for (Map.Entry<String, JsonElement> field : fields) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                appendString(field.getKey(), output);
                output.append(':');
                appendCanonical(
                        field.getValue(),
                        output,
                        depth + 1
                );
            }
            output.append('}');
            return;
        }
        if (value.isJsonArray()) {
            output.append('[');
            boolean first = true;
            for (JsonElement element : value.getAsJsonArray()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                appendCanonical(element, output, depth + 1);
            }
            output.append(']');
            return;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) {
            appendString(primitive.getAsString(), output);
        } else if (primitive.isBoolean()) {
            output.append(primitive.getAsBoolean()
                    ? "true"
                    : "false");
        } else if (primitive.isNumber()) {
            BigInteger integer;
            try {
                integer = primitive.getAsBigDecimal()
                        .toBigIntegerExact();
            } catch (ArithmeticException exception) {
                throw new CliDistributionException(
                        "Canonical JSON floats are forbidden",
                        exception
                );
            }
            if (integer.compareTo(MIN_INT64) < 0
                    || integer.compareTo(MAX_INT64) > 0) {
                throw invalid(
                        "Canonical JSON integer is outside int64"
                );
            }
            output.append(integer);
        } else {
            throw invalid("Canonical JSON contains an invalid value");
        }
    }

    private static void appendString(
            String value,
            StringBuilder output
    ) throws CliDistributionException {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character > 0x7f) {
                throw invalid(
                        "Canonical JSON contains a non-ASCII string"
                );
            }
            switch (character) {
                case '"':
                    output.append("\\\"");
                    break;
                case '\\':
                    output.append("\\\\");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                default:
                    if (character < 0x20 || character == 0x7f) {
                        output.append("\\u00");
                        output.append(
                                Character.forDigit(
                                        (character >>> 4) & 0x0f,
                                        16
                                )
                        );
                        output.append(
                                Character.forDigit(
                                        character & 0x0f,
                                        16
                                )
                        );
                    } else {
                        output.append(character);
                    }
                    break;
            }
        }
        output.append('"');
    }

    private static void requireAsciiTree(
            JsonElement value,
            int depth
    ) throws CliDistributionException {
        if (depth > MAX_JSON_DEPTH) {
            throw invalid("Signed update JSON exceeds the depth limit");
        }
        if (value == null || value.isJsonNull()) {
            return;
        }
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry
                    : value.getAsJsonObject().entrySet()) {
                requireAscii(entry.getKey());
                requireAsciiTree(entry.getValue(), depth + 1);
            }
        } else if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                requireAsciiTree(element, depth + 1);
            }
        } else {
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isString()) {
                requireAscii(primitive.getAsString());
            } else if (primitive.isNumber()) {
                try {
                    BigInteger integer = primitive.getAsBigDecimal()
                            .toBigIntegerExact();
                    if (integer.compareTo(MIN_INT64) < 0
                            || integer.compareTo(MAX_INT64) > 0) {
                        throw invalid(
                                "Signed update JSON integer is "
                                        + "outside int64"
                        );
                    }
                } catch (ArithmeticException exception) {
                    throw new CliDistributionException(
                            "Signed update JSON floats are forbidden",
                            exception
                    );
                }
            } else if (!primitive.isBoolean()) {
                throw invalid(
                        "Signed update JSON contains an invalid primitive"
                );
            }
        }
    }

    private static void requireAscii(String value)
            throws CliDistributionException {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f) {
                throw invalid(
                        "Signed update JSON strings must be ASCII"
                );
            }
        }
    }

    private static void validateRawNesting(byte[] bytes)
            throws CliDistributionException {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (byte raw : bytes) {
            int value = raw & 0xff;
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '{' || value == '[') {
                depth++;
                if (depth > MAX_JSON_DEPTH) {
                    throw invalid(
                            "Signed update JSON exceeds the depth limit"
                    );
                }
            } else if (value == '}' || value == ']') {
                depth--;
                if (depth < 0) {
                    throw invalid(
                            "Signed update JSON nesting is unbalanced"
                    );
                }
            }
        }
        if (depth != 0 || inString || escaped) {
            throw invalid(
                    "Signed update JSON nesting is unbalanced"
            );
        }
    }

    private static byte[] decodeBase64Url(
            String value,
            int minimumBytes,
            int maximumBytes,
            String label
    ) throws CliDistributionException {
        if (value == null
                || !BASE64URL_PATTERN.matcher(value).matches()) {
            throw invalid(label + " is not unpadded base64url");
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            throw new CliDistributionException(
                    label + " is invalid base64url",
                    exception
            );
        }
        if (decoded.length < minimumBytes
                || decoded.length > maximumBytes
                || !Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(decoded)
                .equals(value)) {
            Arrays.fill(decoded, (byte) 0);
            throw invalid(label + " is not canonical base64url");
        }
        return decoded;
    }

    private static void requirePublicKey(byte[] publicKey)
            throws CliDistributionException {
        if (publicKey == null
                || publicKey.length != PUBLIC_KEY_BYTES) {
            throw invalid(
                    "The Locker CLI release public key is unavailable"
            );
        }
    }

    private static void requireIdentity(
            JsonObject object,
            String schema,
            String label
    ) throws CliDistributionException {
        if (!PRODUCT.equals(requireString(object, "product"))
                || !schema.equals(requireString(object, "schema"))
                || requireInteger(object, "schema_version") != 2) {
            throw invalid(label + " identity is invalid");
        }
    }

    private static String requireVersion(
            JsonObject object,
            String name
    ) throws CliDistributionException {
        String version = requireString(object, name);
        if (!VERSION_PATTERN.matcher(version).matches()) {
            throw invalid("Locker CLI version is not stable v2");
        }
        return version;
    }

    static int compareVersions(String left, String right)
            throws CliDistributionException {
        Matcher leftMatch = left == null
                ? null
                : VERSION_PATTERN.matcher(left);
        Matcher rightMatch = right == null
                ? null
                : VERSION_PATTERN.matcher(right);
        if (leftMatch == null
                || rightMatch == null
                || !leftMatch.matches()
                || !rightMatch.matches()) {
            throw invalid("Locker CLI version is not stable v2");
        }

        int minor = new BigInteger(leftMatch.group(1)).compareTo(
                new BigInteger(rightMatch.group(1))
        );
        return minor != 0
                ? minor
                : new BigInteger(leftMatch.group(2)).compareTo(
                        new BigInteger(rightMatch.group(2))
                );
    }

    private static String requireCommit(
            JsonObject object,
            String name
    ) throws CliDistributionException {
        String commit = requireString(object, name);
        if (!COMMIT_PATTERN.matcher(commit).matches()) {
            throw invalid("Locker CLI source commit is invalid");
        }
        return commit;
    }

    private static String requireSha256(
            JsonObject object,
            String name
    ) throws CliDistributionException {
        String value = requireString(object, name);
        if (!SHA256_PATTERN.matcher(value).matches()) {
            throw invalid("Locker CLI SHA-256 is invalid");
        }
        return value;
    }

    private static JsonObject requireObject(
            JsonObject parent,
            String name,
            String label
    ) throws CliDistributionException {
        JsonElement value = parent.get(name);
        if (value == null || !value.isJsonObject()) {
            throw invalid(label + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static String requireString(
            JsonObject object,
            String name
    ) throws CliDistributionException {
        JsonElement value = object.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            throw invalid(
                    "Signed update field " + name + " must be a string"
            );
        }
        return value.getAsString();
    }

    private static long requireInteger(
            JsonObject object,
            String name
    ) throws CliDistributionException {
        JsonElement value = object.get(name);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid(
                    "Signed update field " + name
                            + " must be an integer"
            );
        }
        try {
            BigDecimal number = value.getAsBigDecimal();
            return number.longValueExact();
        } catch (ArithmeticException exception) {
            throw new CliDistributionException(
                    "Signed update field " + name
                            + " is outside int64",
                    exception
            );
        }
    }

    private static void requireExactFields(
            JsonObject object,
            Set<String> expected,
            String label
    ) throws CliDistributionException {
        Set<String> actual = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry
                : object.entrySet()) {
            actual.add(entry.getKey());
        }
        if (!actual.equals(expected)) {
            throw invalid(label + " field set is invalid");
        }
    }

    private static boolean constantTimeEquals(
            String left,
            String right
    ) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
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

    private static void addTarget(
            Map<String, Target> targets,
            String os,
            String arch,
            String filename
    ) {
        targets.put(filename, new Target(os, arch, filename));
    }

    private static CliDistributionException invalid(String message) {
        return new CliDistributionException(message);
    }

    static final class Latest {
        private final String version;
        private final String sourceCommit;
        private final String manifestPath;
        private final String manifestSha256;
        private final long manifestSize;

        private Latest(
                String version,
                String sourceCommit,
                String manifestPath,
                String manifestSha256,
                long manifestSize
        ) {
            this.version = version;
            this.sourceCommit = sourceCommit;
            this.manifestPath = manifestPath;
            this.manifestSha256 = manifestSha256;
            this.manifestSize = manifestSize;
        }

        String getVersion() {
            return version;
        }

        String getSourceCommit() {
            return sourceCommit;
        }

        String getManifestPath() {
            return manifestPath;
        }

        String getManifestSha256() {
            return manifestSha256;
        }

        long getManifestSize() {
            return manifestSize;
        }
    }

    static final class Manifest {
        private final String version;
        private final String sourceCommit;
        private final Map<String, Artifact> artifacts;

        private Manifest(
                String version,
                String sourceCommit,
                Map<String, Artifact> artifacts
        ) {
            this.version = version;
            this.sourceCommit = sourceCommit;
            this.artifacts = Collections.unmodifiableMap(
                    new HashMap<>(artifacts)
            );
        }

        String getVersion() {
            return version;
        }

        String getSourceCommit() {
            return sourceCommit;
        }

        Artifact artifactFor(PlatformIdentity platform)
                throws CliDistributionException {
            for (Artifact artifact : artifacts.values()) {
                if (artifact.os.equals(platform.getOs())
                        && artifact.arch.equals(
                        platform.getArch()
                )) {
                    return artifact;
                }
            }
            throw invalid(
                    "Signed manifest does not contain this Java platform"
            );
        }
    }

    static final class Artifact {
        private final String os;
        private final String arch;
        private final String filename;
        private final String path;
        private final String signaturePath;
        private final String sha256;
        private final long size;

        private Artifact(
                String os,
                String arch,
                String filename,
                String path,
                String signaturePath,
                String sha256,
                long size
        ) {
            this.os = os;
            this.arch = arch;
            this.filename = filename;
            this.path = path;
            this.signaturePath = signaturePath;
            this.sha256 = sha256;
            this.size = size;
        }

        String getFilename() {
            return filename;
        }

        String getPath() {
            return path;
        }

        String getSignaturePath() {
            return signaturePath;
        }

        String getSha256() {
            return sha256;
        }

        long getSize() {
            return size;
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
