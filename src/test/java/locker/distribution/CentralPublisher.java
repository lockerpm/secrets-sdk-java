package locker.distribution;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.channels.FileChannel;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Test-scope Maven Central publisher used only by protected release jobs.
 *
 * <p>The utility deliberately lives outside the published SDK. Credentials
 * are read from the process environment and are never accepted as command
 * arguments or written to disk.
 */
public final class CentralPublisher {
    private static final URI CENTRAL_BASE =
            URI.create("https://central.sonatype.com");
    private static final Duration CONNECT_TIMEOUT =
            Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(60);
    private static final Duration UPLOAD_TIMEOUT =
            Duration.ofMinutes(5);
    private static final Duration POLL_INTERVAL =
            Duration.ofSeconds(5);
    private static final int MAX_STATUS_POLLS = 360;
    private static final int MAX_RECOVERY_POLLS = 24;
    private static final int DEPLOYMENT_LOOKUP_PAGE_SIZE = 20;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_CREDENTIAL_CHARACTERS = 4096;
    private static final long MAX_BUNDLE_BYTES =
            128L * 1024L * 1024L;
    private static final Pattern DEPLOYMENT_ID_PATTERN =
            Pattern.compile(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                            + "[0-9a-f]{4}-[0-9a-f]{12}$"
            );
    private static final Pattern CENTRAL_RELATIVE_PATH =
            Pattern.compile("^[A-Za-z0-9._/-]+$");

    private final Transport transport;
    private final Sleeper sleeper;
    private final String authorization;

    private CentralPublisher() {
        throw new AssertionError("No instances without dependencies");
    }

    CentralPublisher(
            Transport transport,
            Sleeper sleeper,
            String username,
            String password
    ) {
        this.transport = java.util.Objects.requireNonNull(
                transport,
                "transport"
        );
        this.sleeper = java.util.Objects.requireNonNull(
                sleeper,
                "sleeper"
        );
        this.authorization = createAuthorization(
                username,
                password
        );
    }

    public static void main(String[] arguments) throws Exception {
        requireArguments(arguments);
        String username = System.getenv("MAVEN_CENTRAL_USERNAME");
        String password = System.getenv("MAVEN_CENTRAL_PASSWORD");

        try (HttpTransport transport = new HttpTransport()) {
            CentralPublisher publisher = new CentralPublisher(
                    transport,
                    Thread::sleep,
                    username,
                    password
            );
            if ("stage".equals(arguments[0])) {
                publisher.stageVerified(
                        Path.of(arguments[1]),
                        Path.of(arguments[2]),
                        System.getenv("LOCKER_RELEASE_TAG"),
                        requiredEnvironmentPath(
                                "MAVEN_GPG_KEY_FILE"
                        )
                );
            } else {
                publisher.publish(Path.of(arguments[1]));
            }
        }
    }

    void stage(
            Path bundle,
            Path deploymentIdOutput,
            String releaseTag
    ) throws Exception {
        stage(
                bundle,
                deploymentIdOutput,
                releaseTag,
                deploymentId -> {
                }
        );
    }

    void stageVerified(
            Path bundle,
            Path deploymentIdOutput,
            String releaseTag,
            Path signingKey
    ) throws Exception {
        String checkedTag = requireReleaseTag(releaseTag);
        CentralPublicReconciler.ExpectedRelease expected =
                CentralPublicReconciler.ExpectedRelease.fromBundle(
                        bundle,
                        checkedTag.substring(1),
                        signingKey
                );
        try {
            stage(
                    bundle,
                    deploymentIdOutput,
                    checkedTag,
                    deploymentId -> verifyDeployment(
                            expected,
                            deploymentId
                    )
            );
        } finally {
            expected.erase();
        }
    }

    void stage(
            Path bundle,
            Path deploymentIdOutput,
            String releaseTag,
            DeploymentVerifier deploymentVerifier
    ) throws Exception {
        String checkedTag = requireReleaseTag(releaseTag);
        String deploymentName = "lockersm-java-" + checkedTag;
        java.util.Objects.requireNonNull(
                deploymentVerifier,
                "deploymentVerifier"
        );
        requireDeploymentIdOutputAvailable(deploymentIdOutput);
        byte[] bundleBytes = readRegularFile(
                bundle,
                MAX_BUNDLE_BYTES,
                "The verified Maven Central bundle"
        );
        byte[] prefix = null;
        byte[] suffix = null;
        try {
            String deploymentId = findUniqueDeployment(
                    deploymentName
            );
            if (deploymentId == null) {
                String boundary = "locker-central-"
                        + UUID.randomUUID().toString()
                        .replace("-", "");
                prefix = (
                        "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; "
                                + "name=\"bundle\"; "
                                + "filename=\"central-bundle.zip\"\r\n"
                                + "Content-Type: "
                                + "application/octet-stream\r\n"
                                + "\r\n"
                ).getBytes(StandardCharsets.US_ASCII);
                suffix = (
                        "\r\n--" + boundary + "--\r\n"
                ).getBytes(StandardCharsets.US_ASCII);
                long contentLength = Math.addExact(
                        Math.addExact(
                                (long) prefix.length,
                                bundleBytes.length
                        ),
                        suffix.length
                );
                HttpRequest.BodyPublisher multipart =
                        HttpRequest.BodyPublishers.fromPublisher(
                                HttpRequest.BodyPublishers.ofByteArrays(
                                        List.of(
                                                prefix,
                                                bundleBytes,
                                                suffix
                                        )
                                ),
                                contentLength
                        );
                URI uploadUri = centralUri(
                        "/api/v1/publisher/upload"
                                + "?publishingType=USER_MANAGED"
                                + "&name="
                                + encodeQueryValue(deploymentName)
                );
                Response response;
                try {
                    response = transport.send(
                            new Request(
                                    "POST",
                                    uploadUri,
                                    "multipart/form-data; boundary="
                                            + boundary,
                                    multipart,
                                    UPLOAD_TIMEOUT
                            ),
                            authorization
                    );
                } catch (IOException exception) {
                    deploymentId = recoverAmbiguousUpload(
                            deploymentName,
                            exception
                    );
                    response = null;
                }
                if (response != null) {
                    try {
                        if (response.statusCode == 201) {
                            try {
                                deploymentId = parseDeploymentId(
                                        response.body
                                );
                            } catch (IOException exception) {
                                deploymentId = recoverAmbiguousUpload(
                                        deploymentName,
                                        exception
                                );
                            }
                        } else if (response.statusCode == 429
                                || response.statusCode >= 500) {
                            deploymentId = recoverAmbiguousUpload(
                                    deploymentName,
                                    new IOException(
                                            "Maven Central bundle upload "
                                                    + "returned ambiguous "
                                                    + "HTTP status "
                                                    + response.statusCode
                                    )
                            );
                        } else {
                            requireHttpStatus(
                                    response,
                                    201,
                                    "Maven Central bundle upload"
                            );
                            throw new AssertionError(
                                    "Unreachable upload status"
                            );
                        }
                    } finally {
                        response.erase();
                    }
                }
            } else {
                System.out.println(
                        "Recovered Maven Central deployment: "
                                + deploymentId
                );
            }

            writeDeploymentId(
                    deploymentIdOutput,
                    deploymentId
            );
            waitUntilReadyForPublication(deploymentId);
            deploymentVerifier.verify(deploymentId);
            System.out.println(
                    "Maven Central deployment ready and verified: "
                            + deploymentId
            );
        } finally {
            Arrays.fill(bundleBytes, (byte) 0);
            if (prefix != null) {
                Arrays.fill(prefix, (byte) 0);
            }
            if (suffix != null) {
                Arrays.fill(suffix, (byte) 0);
            }
        }
    }

    private String findUniqueDeployment(String deploymentName)
            throws Exception {
        URI lookup = centralUri(
                "/api/v1/publisher/deployments"
                        + "?namespace=io.locker"
                        + "&deploymentName="
                        + encodeQueryValue(deploymentName)
                        + "&page=0"
                        + "&size=" + DEPLOYMENT_LOOKUP_PAGE_SIZE
                        + "&sortField=createTimestamp"
                        + "&sortDirection=desc"
        );
        Response response = transport.send(
                new Request(
                        "GET",
                        lookup,
                        null,
                        HttpRequest.BodyPublishers.noBody(),
                        REQUEST_TIMEOUT
                ),
                authorization
        );
        try {
            requireHttpStatus(
                    response,
                    200,
                    "Maven Central deployment lookup"
            );
            return parseDeploymentLookup(
                    response.body,
                    deploymentName
            );
        } finally {
            response.erase();
        }
    }

    private String recoverAmbiguousUpload(
            String deploymentName,
            IOException uploadFailure
    ) throws Exception {
        IOException lastLookupFailure = null;
        for (int poll = 0; poll < MAX_RECOVERY_POLLS; poll++) {
            try {
                String deploymentId = findUniqueDeployment(
                        deploymentName
                );
                if (deploymentId != null) {
                    return deploymentId;
                }
                lastLookupFailure = null;
            } catch (InterruptedException exception) {
                throw exception;
            } catch (IOException exception) {
                lastLookupFailure = exception;
            }
            if (poll + 1 < MAX_RECOVERY_POLLS) {
                sleeper.sleep(POLL_INTERVAL.toMillis());
            }
        }
        IOException failure = new IOException(
                "Maven Central upload result is ambiguous and no "
                        + "uniquely named deployment became visible",
                uploadFailure
        );
        if (lastLookupFailure != null) {
            failure.addSuppressed(lastLookupFailure);
        }
        throw failure;
    }

    private void verifyDeployment(
            CentralPublicReconciler.ExpectedRelease expected,
            String deploymentId
    ) throws Exception {
        requireDeploymentId(deploymentId);
        CentralPublicReconciler.State state =
                new CentralPublicReconciler().inspectAt(
                        expected,
                        (uri, maximumSize) -> {
                            if (maximumSize < 1
                                    || maximumSize > MAX_BUNDLE_BYTES) {
                                throw new IOException(
                                        "Maven Central deployment "
                                                + "artifact bound is invalid"
                                );
                            }
                            Response response = transport.send(
                                    new Request(
                                            "GET",
                                            uri,
                                            null,
                                            HttpRequest.BodyPublishers
                                                    .noBody(),
                                            REQUEST_TIMEOUT,
                                            (int) maximumSize
                                    ),
                                    authorization
                            );
                            try {
                                return new CentralPublicReconciler.Response(
                                        response.statusCode,
                                        response.body
                                );
                            } finally {
                                response.erase();
                            }
                        },
                        relativePath -> deploymentArtifactUri(
                                deploymentId,
                                relativePath
                        )
                );
        if (state != CentralPublicReconciler.State.EXACT) {
            throw new IOException(
                    "Maven Central deployment does not contain the "
                            + "exact verified release"
            );
        }
    }

    private static URI deploymentArtifactUri(
            String deploymentId,
            String relativePath
    ) {
        try {
            requireDeploymentId(deploymentId);
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Maven Central deployment identifier is invalid",
                    exception
            );
        }
        if (relativePath == null
                || !CENTRAL_RELATIVE_PATH.matcher(
                relativePath
        ).matches()
                || relativePath.startsWith("/")
                || relativePath.contains("..")
                || relativePath.contains("//")) {
            throw new IllegalArgumentException(
                    "Maven Central deployment artifact path is invalid"
            );
        }
        return centralUri(
                "/api/v1/publisher/deployment/"
                        + deploymentId
                        + "/download/"
                        + relativePath
        );
    }

    void publish(Path deploymentIdFile) throws Exception {
        String deploymentId = readDeploymentId(deploymentIdFile);
        DeploymentState state = requestStatus(deploymentId);
        switch (state) {
            case PUBLISHED:
                System.out.println(
                        "Maven Central deployment already published: "
                                + deploymentId
                );
                return;
            case PUBLISHING:
                waitUntilPublished(deploymentId);
                break;
            case VALIDATED:
                Response response = transport.send(
                        new Request(
                                "POST",
                                centralUri(
                                        "/api/v1/publisher/deployment/"
                                                + deploymentId
                                ),
                                null,
                                HttpRequest.BodyPublishers.noBody(),
                                REQUEST_TIMEOUT
                        ),
                        authorization
                );
                try {
                    requireHttpStatus(
                            response,
                            204,
                            "Maven Central publish request"
                    );
                    if (response.body.length != 0) {
                        throw new IOException(
                                "Maven Central publish response "
                                        + "must be empty"
                        );
                    }
                } finally {
                    response.erase();
                }
                waitUntilPublished(deploymentId);
                break;
            case FAILED:
                throw new IOException(
                        "Maven Central deployment validation failed"
                );
            default:
                throw new IOException(
                        "Maven Central deployment is not ready to publish"
                );
        }
        System.out.println(
                "Maven Central deployment published: " + deploymentId
        );
    }

    private void waitUntilReadyForPublication(String deploymentId)
            throws Exception {
        for (int poll = 0; poll < MAX_STATUS_POLLS; poll++) {
            DeploymentState state = requestStatus(deploymentId);
            switch (state) {
                case VALIDATED:
                case PUBLISHING:
                case PUBLISHED:
                    return;
                case PENDING:
                case VALIDATING:
                    break;
                case FAILED:
                    throw new IOException(
                            "Maven Central deployment validation failed; "
                                    + "the deployment ID was persisted"
                    );
                default:
                    throw new IOException(
                            "A user-managed Maven Central deployment "
                                    + "entered an unexpected state"
                    );
            }
            pauseBeforeNextPoll(poll);
        }
        throw new IOException(
                "Maven Central deployment validation timed out; "
                        + "the deployment ID was persisted"
        );
    }

    private void waitUntilPublished(String deploymentId)
            throws Exception {
        for (int poll = 0; poll < MAX_STATUS_POLLS; poll++) {
            DeploymentState state = requestStatus(deploymentId);
            switch (state) {
                case PUBLISHED:
                    return;
                case VALIDATED:
                case PUBLISHING:
                    break;
                case FAILED:
                    throw new IOException(
                            "Maven Central deployment publication failed"
                    );
                default:
                    throw new IOException(
                            "Maven Central deployment regressed to an "
                                    + "unexpected state"
                    );
            }
            pauseBeforeNextPoll(poll);
        }
        throw new IOException(
                "Maven Central deployment publication timed out"
        );
    }

    private void pauseBeforeNextPoll(int completedPoll)
            throws InterruptedException, IOException {
        if (completedPoll + 1 >= MAX_STATUS_POLLS) {
            throw new IOException(
                    "Maven Central deployment status timed out"
            );
        }
        sleeper.sleep(POLL_INTERVAL.toMillis());
    }

    private DeploymentState requestStatus(String deploymentId)
            throws Exception {
        Response response = transport.send(
                new Request(
                        "POST",
                        centralUri(
                                "/api/v1/publisher/status?id="
                                        + deploymentId
                        ),
                        null,
                        HttpRequest.BodyPublishers.noBody(),
                        REQUEST_TIMEOUT
                ),
                authorization
        );
        try {
            requireHttpStatus(
                    response,
                    200,
                    "Maven Central deployment status"
            );
            return parseStatus(
                    response.body,
                    deploymentId
            );
        } finally {
            response.erase();
        }
    }

    static DeploymentState parseStatus(
            byte[] responseBytes,
            String expectedDeploymentId
    ) throws IOException {
        requireDeploymentId(expectedDeploymentId);
        JsonElement parsed;
        try {
            parsed = StrictJson.parse(responseBytes, 16);
        } catch (CliDistributionException exception) {
            throw new IOException(
                    "Maven Central returned invalid status JSON",
                    exception
            );
        }
        if (!parsed.isJsonObject()) {
            throw new IOException(
                    "Maven Central status JSON must be an object"
            );
        }
        JsonObject object = parsed.getAsJsonObject();
        String actualDeploymentId = requireJsonString(
                object,
                "deploymentId"
        );
        if (!expectedDeploymentId.equals(actualDeploymentId)
                || !DEPLOYMENT_ID_PATTERN.matcher(
                actualDeploymentId
        ).matches()) {
            throw new IOException(
                    "Maven Central status is not bound to the deployment"
            );
        }

        String stateValue = requireJsonString(
                object,
                "deploymentState"
        );
        DeploymentState state;
        try {
            state = DeploymentState.valueOf(
                    stateValue.toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            throw new IOException(
                    "Maven Central returned an unknown deployment state",
                    exception
            );
        }
        if (!state.name().equals(stateValue)) {
            throw new IOException(
                    "Maven Central deployment state is not canonical"
            );
        }

        if (object.has("deploymentName")) {
            requireJsonString(object, "deploymentName");
        }
        if (object.has("purls")) {
            JsonElement purlsElement = object.get("purls");
            if (!purlsElement.isJsonArray()) {
                throw new IOException(
                        "Maven Central status purls must be an array"
                );
            }
            JsonArray purls = purlsElement.getAsJsonArray();
            for (JsonElement purl : purls) {
                requireJsonString(purl, "purl");
            }
        }
        return state;
    }

    static String parseDeploymentLookup(
            byte[] responseBytes,
            String expectedDeploymentName
    ) throws IOException {
        if (expectedDeploymentName == null
                || expectedDeploymentName.isBlank()
                || !expectedDeploymentName.equals(
                expectedDeploymentName.trim()
        )
                || expectedDeploymentName.length() > 200) {
            throw new IllegalArgumentException(
                    "Expected Maven Central deployment name is invalid"
            );
        }
        JsonElement parsed;
        try {
            parsed = StrictJson.parse(responseBytes, 32);
        } catch (CliDistributionException exception) {
            throw new IOException(
                    "Maven Central returned invalid deployment-list JSON",
                    exception
            );
        }
        if (!parsed.isJsonObject()) {
            throw new IOException(
                    "Maven Central deployment list must be an object"
            );
        }
        JsonObject object = parsed.getAsJsonObject();
        JsonElement deploymentsElement = object.get("deployments");
        if (deploymentsElement == null
                || !deploymentsElement.isJsonArray()) {
            throw new IOException(
                    "Maven Central deployment list is missing deployments"
            );
        }
        int page = requireJsonInteger(object, "page");
        int pageSize = requireJsonInteger(object, "pageSize");
        int pageCount = requireJsonInteger(object, "pageCount");
        int total = requireJsonInteger(
                object,
                "totalResultCount"
        );
        JsonArray deployments = deploymentsElement.getAsJsonArray();
        if (page != 0
                || pageSize < 1
                || pageSize > DEPLOYMENT_LOOKUP_PAGE_SIZE
                || pageCount < 0
                || pageCount > 1
                || total != deployments.size()) {
            throw new IOException(
                    "Maven Central deployment lookup is incomplete"
            );
        }

        String match = null;
        for (JsonElement element : deployments) {
            if (!element.isJsonObject()) {
                throw new IOException(
                        "Maven Central deployment entry must be an object"
                );
            }
            JsonObject deployment = element.getAsJsonObject();
            String deploymentId = requireJsonString(
                    deployment,
                    "deploymentId"
            );
            requireDeploymentId(deploymentId);
            String deploymentName = requireJsonString(
                    deployment,
                    "deploymentName"
            );
            String namespace = requireJsonString(
                    deployment,
                    "namespace"
            );
            String state = requireJsonString(
                    deployment,
                    "deploymentState"
            );
            try {
                if (!DeploymentState.valueOf(state).name().equals(
                        state
                )) {
                    throw new IllegalArgumentException(
                            "noncanonical deployment state"
                    );
                }
            } catch (IllegalArgumentException exception) {
                throw new IOException(
                        "Maven Central deployment list contains "
                                + "an unknown state",
                        exception
                );
            }
            if (expectedDeploymentName.equals(deploymentName)) {
                if (!"io.locker".equals(namespace)
                        || match != null) {
                    throw new IOException(
                            "Maven Central deployment-name recovery "
                                    + "is ambiguous"
                    );
                }
                match = deploymentId;
            }
        }
        return match;
    }

    private static int requireJsonInteger(
            JsonObject object,
            String name
    ) throws IOException {
        JsonElement element = object.get(name);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException(
                    "Maven Central deployment list " + name
                            + " must be an integer"
            );
        }
        String value = element.getAsString();
        if (!value.matches("^(?:0|[1-9][0-9]*)$")) {
            throw new IOException(
                    "Maven Central deployment list " + name
                            + " is not a canonical integer"
            );
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IOException(
                    "Maven Central deployment list " + name
                            + " is outside its integer bound",
                    exception
            );
        }
    }

    private static String requireJsonString(
            JsonObject object,
            String name
    ) throws IOException {
        if (!object.has(name)) {
            throw new IOException(
                    "Maven Central status is missing " + name
            );
        }
        return requireJsonString(object.get(name), name);
    }

    private static String requireJsonString(
            JsonElement element,
            String name
    ) throws IOException {
        if (element == null
                || !element.isJsonPrimitive()) {
            throw new IOException(
                    "Maven Central status " + name
                            + " must be a string"
            );
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw new IOException(
                    "Maven Central status " + name
                            + " must be a string"
            );
        }
        return primitive.getAsString();
    }

    private static void requireHttpStatus(
            Response response,
            int expected,
            String operation
    ) throws IOException {
        if (response.statusCode != expected) {
            throw new IOException(
                    operation + " failed with HTTP status "
                            + response.statusCode
            );
        }
    }

    private static String parseDeploymentId(byte[] bytes)
            throws IOException {
        String deploymentId = decodeAscii(
                bytes,
                "Maven Central deployment identifier"
        );
        requireDeploymentId(deploymentId);
        return deploymentId;
    }

    private static String readDeploymentId(Path path)
            throws IOException {
        byte[] bytes = readRegularFile(
                path,
                37,
                "The Maven Central deployment identifier"
        );
        try {
            String value = decodeAscii(
                    bytes,
                    "Maven Central deployment identifier"
            );
            if (value.endsWith("\n")) {
                value = value.substring(0, value.length() - 1);
            }
            requireDeploymentId(value);
            return value;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static void writeDeploymentId(
            Path output,
            String deploymentId
    ) throws IOException {
        requireDeploymentId(deploymentId);
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException(
                    "Maven Central deployment identifier parent is missing"
            );
        }
        BasicFileAttributes parentAttributes = Files.readAttributes(
                parent,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!parentAttributes.isDirectory()) {
            throw new IOException(
                    "Maven Central deployment identifier parent "
                            + "must be a real directory"
            );
        }
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Maven Central deployment identifier already exists"
            );
        }

        Path temporary = Files.createTempFile(
                parent,
                ".central-deployment-id-",
                ".tmp",
                privateFileAttributes(parent)
        );
        boolean published = false;
        try {
            securePrivate(temporary);
            byte[] bytes = (
                    deploymentId + "\n"
            ).getBytes(StandardCharsets.US_ASCII);
            try {
                try (FileChannel channel = FileChannel.open(
                        temporary,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        LinkOption.NOFOLLOW_LINKS
                )) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
            securePrivate(temporary);
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException(
                        "Atomic Maven Central deployment identifier "
                                + "publication is unavailable",
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
                || !sameIdentity(before, after)) {
            Arrays.fill(bytes, (byte) 0);
            throw new IOException(
                    label + " changed while it was being read"
            );
        }
        return bytes;
    }

    private static boolean sameIdentity(
            BasicFileAttributes before,
            BasicFileAttributes after
    ) {
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        return before.size() == after.size()
                && before.lastModifiedTime().equals(
                after.lastModifiedTime()
        )
                && (beforeKey == null
                || afterKey == null
                || beforeKey.equals(afterKey));
    }

    private static String requireReleaseTag(String value) {
        if (value == null
                || value.length() < 2
                || value.charAt(0) != 'v'
                || !ReleaseReadinessVerifier.isSemver(
                value.substring(1)
        )) {
            throw new IllegalArgumentException(
                    "LOCKER_RELEASE_TAG must be a canonical v-prefixed SemVer"
            );
        }
        return value;
    }

    private static Path requiredEnvironmentPath(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    name + " is required for Maven Central release"
            );
        }
        return Path.of(value);
    }

    private static void requireDeploymentIdOutputAvailable(
            Path output
    ) throws IOException {
        Path absolute = output.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException(
                    "Maven Central deployment identifier parent is missing"
            );
        }
        BasicFileAttributes parentAttributes = Files.readAttributes(
                parent,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!parentAttributes.isDirectory()) {
            throw new IOException(
                    "Maven Central deployment identifier parent "
                            + "must be a real directory"
            );
        }
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Maven Central deployment identifier already exists"
            );
        }
    }

    private static void requireDeploymentId(String value)
            throws IOException {
        if (value == null
                || !DEPLOYMENT_ID_PATTERN.matcher(value).matches()) {
            throw new IOException(
                    "Maven Central deployment identifier is invalid"
            );
        }
    }

    private static String decodeAscii(
            byte[] bytes,
            String label
    ) throws IOException {
        try {
            return StandardCharsets.US_ASCII
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException(label + " must be ASCII", exception);
        }
    }

    private static String createAuthorization(
            String username,
            String password
    ) {
        requireCredential("username", username);
        requireCredential("password", password);
        int characterCount = Math.addExact(
                Math.addExact(username.length(), 1),
                password.length()
        );
        char[] joined = new char[characterCount];
        username.getChars(0, username.length(), joined, 0);
        joined[username.length()] = ':';
        password.getChars(
                0,
                password.length(),
                joined,
                username.length() + 1
        );

        byte[] utf8 = null;
        byte[] token = null;
        ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(joined));
            utf8 = new byte[encoded.remaining()];
            encoded.get(utf8);
            token = Base64.getEncoder().encode(utf8);
            return "Bearer " + new String(
                    token,
                    StandardCharsets.US_ASCII
            );
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException(
                    "Maven Central credentials contain invalid Unicode",
                    exception
            );
        } finally {
            Arrays.fill(joined, '\0');
            if (encoded != null && encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
            if (utf8 != null) {
                Arrays.fill(utf8, (byte) 0);
            }
            if (token != null) {
                Arrays.fill(token, (byte) 0);
            }
        }
    }

    private static void requireCredential(
            String label,
            String value
    ) {
        if (value == null
                || value.isBlank()
                || value.length() > MAX_CREDENTIAL_CHARACTERS) {
            throw new IllegalArgumentException(
                    "Maven Central " + label
                            + " is missing or outside its size bound"
            );
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == ':'
                    || Character.isISOControl(character)) {
                throw new IllegalArgumentException(
                        "Maven Central " + label
                                + " contains a forbidden character"
                );
            }
        }
    }

    private static URI centralUri(String pathAndQuery) {
        URI uri = CENTRAL_BASE.resolve(pathAndQuery);
        if (!"https".equals(uri.getScheme())
                || !"central.sonatype.com".equals(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "Maven Central request URI is invalid"
            );
        }
        return uri;
    }

    private static String encodeQueryValue(String value) {
        return URLEncoder.encode(
                value,
                StandardCharsets.UTF_8
        );
    }

    private static void requireArguments(String[] arguments) {
        if (arguments == null
                || arguments.length < 1
                || ("stage".equals(arguments[0])
                && arguments.length != 3)
                || ("publish".equals(arguments[0])
                && arguments.length != 2)
                || (!"stage".equals(arguments[0])
                && !"publish".equals(arguments[0]))) {
            throw new IllegalArgumentException(
                    "Expected: stage <bundle> <deployment-id-output> "
                            + "or publish <deployment-id-file>"
            );
        }
    }

    private static FileAttribute<?>[] privateFileAttributes(
            Path parent
    ) {
        PosixFileAttributeView view = Files.getFileAttributeView(
                parent,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (view == null) {
            return new FileAttribute<?>[0];
        }
        return new FileAttribute<?>[]{
                PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")
                )
        };
    }

    private static void securePrivate(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(
                path,
                PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (posix != null) {
            Files.setPosixFilePermissions(
                    path,
                    PosixFilePermissions.fromString("rw-------")
            );
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(
                path,
                AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (acl == null) {
            throw new IOException(
                    "The filesystem cannot enforce private Maven "
                            + "Central release metadata"
            );
        }
        UserPrincipal owner = acl.getOwner();
        AclEntry ownerAccess = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(
                        EnumSet.allOf(AclEntryPermission.class)
                )
                .build();
        acl.setAcl(Collections.singletonList(ownerAccess));
    }

    private static byte[] readBounded(
            InputStream stream,
            int maximum
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total = Math.addExact(total, read);
            if (total > maximum) {
                throw new IOException(
                        "Maven Central response exceeds the size limit"
                );
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    enum DeploymentState {
        PENDING,
        VALIDATING,
        VALIDATED,
        PUBLISHING,
        PUBLISHED,
        FAILED
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    @FunctionalInterface
    interface DeploymentVerifier {
        void verify(String deploymentId) throws Exception;
    }

    interface Transport {
        Response send(
                Request request,
                String authorization
        ) throws IOException, InterruptedException;
    }

    static final class Request {
        final String method;
        final URI uri;
        final String contentType;
        final HttpRequest.BodyPublisher body;
        final Duration timeout;
        final int maximumResponseBytes;

        Request(
                String method,
                URI uri,
                String contentType,
                HttpRequest.BodyPublisher body,
                Duration timeout
        ) {
            this(
                    method,
                    uri,
                    contentType,
                    body,
                    timeout,
                    MAX_RESPONSE_BYTES
            );
        }

        Request(
                String method,
                URI uri,
                String contentType,
                HttpRequest.BodyPublisher body,
                Duration timeout,
                int maximumResponseBytes
        ) {
            if (maximumResponseBytes < 1
                    || maximumResponseBytes > MAX_BUNDLE_BYTES) {
                throw new IllegalArgumentException(
                        "Maven Central response bound is invalid"
                );
            }
            this.method = method;
            this.uri = uri;
            this.contentType = contentType;
            this.body = body;
            this.timeout = timeout;
            this.maximumResponseBytes = maximumResponseBytes;
        }
    }

    static final class Response {
        final int statusCode;
        final byte[] body;

        Response(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = Arrays.copyOf(body, body.length);
        }

        void erase() {
            Arrays.fill(body, (byte) 0);
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
                        "locker-central-publisher-http"
                );
                thread.setDaemon(true);
                return thread;
            });
            client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(
                            HttpClient.Redirect.NEVER
                    )
                    .executor(executor)
                    .build();
        }

        @Override
        public Response send(
                Request request,
                String authorization
        ) throws IOException, InterruptedException {
            centralUri(request.uri.getRawPath()
                    + (request.uri.getRawQuery() == null
                    ? ""
                    : "?" + request.uri.getRawQuery()));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(request.uri)
                    .timeout(request.timeout)
                    .header("Authorization", authorization)
                    .header(
                            "User-Agent",
                            "LockerSM-Java-Release"
                    );
            if (request.contentType != null) {
                builder.header(
                        "Content-Type",
                        request.contentType
                );
            }
            HttpRequest httpRequest = builder.method(
                    request.method,
                    request.body
            ).build();
            HttpResponse<InputStream> response = client.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            byte[] body;
            try (InputStream stream = response.body()) {
                int maximum = response.statusCode() == 200
                        ? request.maximumResponseBytes
                        : MAX_RESPONSE_BYTES;
                body = readBounded(stream, maximum);
            }
            return new Response(response.statusCode(), body);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
