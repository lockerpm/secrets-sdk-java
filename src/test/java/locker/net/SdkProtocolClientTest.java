package locker.net;

import com.google.gson.reflect.TypeToken;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import locker.LockerClient;
import locker.exception.AlreadyExistsError;
import locker.exception.ApiConnectionError;
import locker.exception.ApiError;
import locker.exception.ApiServerError;
import locker.exception.AuthenticationError;
import locker.exception.CliRunError;
import locker.exception.ConflictError;
import locker.exception.IntegrityError;
import locker.exception.OperationCancelledError;
import locker.exception.ProtocolError;
import locker.exception.RateLimitError;
import locker.exception.ResourceNotFoundError;
import locker.exception.RequestRejectedError;
import locker.exception.ResponseTooLargeError;
import locker.exception.StorageError;
import locker.exception.ValidationError;
import locker.model.LockerCollection;
import locker.model.Secret;
import locker.model.SecretPage;
import locker.model.EnvironmentPage;
import locker.param.environment.EnvironmentListPageParams;
import locker.param.secret.SecretCreateParams;
import locker.param.secret.SecretListPageParams;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SdkProtocolClientTest {
    private static final String VALID_ACCESS_KEY_ID =
            "00000000-0000-4000-8000-000000000001";
    private static final String VALID_SECRET_ACCESS_KEY =
            "dGVzdC1vbmx5LWNyZWRlbnRpYWw=";

    @Test
    public void negotiatesCapabilitiesAndReturnsSecretValue() throws Exception {
        LockerClient client = client("success");

        String value = client.secrets().retrieve(
                "DATABASE_PASSWORD",
                String.class,
                "production"
        );

        assertEquals("retrieved-value", value);
    }

    @Test
    public void bindsTypedErrorsOnlyWhenAdvertised() throws Exception {
        assertEquals(
                "retrieved-value",
                client("legacy-error-contract").secrets().retrieve(
                        "DATABASE_PASSWORD",
                        String.class,
                        "production"
                )
        );
        assertEquals(
                "retrieved-value",
                client("unknown-error-contract").secrets().retrieve(
                        "DATABASE_PASSWORD",
                        String.class,
                        "production"
                )
        );
        assertThrows(
                ApiConnectionError.class,
                () -> client("invalid-error-contract")
                        .secrets()
                        .retrieve(
                                "DATABASE_PASSWORD",
                                String.class,
                                "production"
                        )
        );
    }

    @Test
    public void sendsMutationValuesThroughProtocolStdin() throws Exception {
        LockerClient client = client("success");
        SecretCreateParams params = SecretCreateParams.builder()
                .setKey("CREATED_KEY")
                .setValue("sensitive-create-value")
                .setDescription("")
                .build();

        Secret created = client.secrets().create(params, Secret.class);

        assertEquals("CREATED_KEY", created.getKey());
        assertEquals("sensitive-create-value", created.getValue());
        assertFalse(created.toString().contains("sensitive-create-value"));
        assertTrue(created.toString().contains("[REDACTED]"));
    }

    @Test
    public void deserializesRawLockerCollectionWithTypedItems() throws Exception {
        LockerClient client = client("success");

        Object result = client.secrets().list(LockerCollection.class);

        LockerCollection<?> secrets = assertInstanceOf(
                LockerCollection.class,
                result
        );
        assertEquals(2, secrets.size());
        assertInstanceOf(Secret.class, secrets.get(0));
    }

    @Test
    public void acceptsParameterizedCollectionType() throws Exception {
        LockerClient client = client("success");
        Type type = new TypeToken<LockerCollection<Secret>>() {
        }.getType();

        LockerCollection<Secret> secrets = client.secrets().list(type);

        assertEquals(2, secrets.size());
        assertEquals("FIRST", secrets.get(0).getKey());
    }

    @Test
    public void returnsTypedSecretAndEnvironmentPages() throws Exception {
        LockerClient client = client("success");

        SecretPage first = client.secrets().listPage(
                SecretListPageParams.builder()
                        .setEnvironmentName("production")
                        .setPageSize(2)
                        .build()
        );
        assertEquals("secret_page", first.getObject());
        assertEquals("FIRST", first.getItems().get(0).getKey());
        assertEquals("secret-next", first.getNextCursor());
        assertThrows(
                UnsupportedOperationException.class,
                () -> first.getItems().clear()
        );

        SecretPage last = client.secrets().listPage(
                SecretListPageParams.builder()
                        .setEnvironmentName("production")
                        .setPageSize(2)
                        .setCursor(first.getNextCursor())
                        .build()
        );
        assertEquals(null, last.getNextCursor());

        EnvironmentPage environments = client.environments().listPage(
                EnvironmentListPageParams.builder()
                        .setPageSize(1)
                        .build()
        );
        assertEquals("environment_page", environments.getObject());
        assertEquals(
                "production",
                environments.getItems().get(0).getName()
        );
        assertEquals(null, environments.getNextCursor());
    }

    @Test
    public void validatesPageParametersBeforeLaunchingCli() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SecretListPageParams.builder().setPageSize(0).build()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> EnvironmentListPageParams.builder()
                        .setCursor("")
                        .build()
        );
    }

    @Test
    public void rejectsMalformedCredentialsBeforeResolvingOrLaunchingCli() {
        AtomicInteger identityCalls = new AtomicInteger();
        AtomicInteger executorCalls = new AtomicInteger();
        SdkProtocolClient protocolClient = new SdkProtocolClient(
                (request, limit) -> {
                    executorCalls.incrementAndGet();
                    throw new AssertionError(
                            "malformed credentials launched the CLI"
                    );
                },
                () -> {
                    identityCalls.incrementAndGet();
                    return "unused";
                }
        );
        LockerResponseGetterOptions options = LockerClient.builder()
                .setAccessKeyId(VALID_ACCESS_KEY_ID)
                .setSecretAccessKey("not canonical base64")
                .buildOptions();
        LockerClient client = new LockerClient(
                new LiveLockerResponseGetter(options, protocolClient)
        );

        AuthenticationError error = assertThrows(
                AuthenticationError.class,
                () -> client.secrets().retrieve(
                        "DATABASE_PASSWORD",
                        String.class,
                        "production"
                )
        );

        assertEquals(
                "malformed_secret_access_key",
                error.getErrorCode()
        );
        assertEquals(
                "secret access key must be non-empty canonical base64",
                error.getUserMessage()
        );
        assertEquals(0, identityCalls.get());
        assertEquals(0, executorCalls.get());
    }

    @Test
    public void rejectsMalformedCredentialsBeforeManagedCliResolution() {
        AtomicInteger resolverCalls = new AtomicInteger();
        LockerResponseGetterOptions options = LockerClient.builder()
                .setAccessKeyId(VALID_ACCESS_KEY_ID)
                .setSecretAccessKey("not canonical base64")
                .buildOptions();
        LockerClient client = new LockerClient(
                new LiveLockerResponseGetter(
                        options,
                        explicitPath -> {
                            resolverCalls.incrementAndGet();
                            throw new AssertionError(
                                    "malformed credentials resolved the CLI"
                            );
                        }
                )
        );

        AuthenticationError error = assertThrows(
                AuthenticationError.class,
                () -> client.secrets().retrieve(
                        "DATABASE_PASSWORD",
                        String.class,
                        "production"
                )
        );

        assertEquals(
                "malformed_secret_access_key",
                error.getErrorCode()
        );
        assertEquals(
                "secret access key must be non-empty canonical base64",
                error.getUserMessage()
        );
        assertEquals(0, resolverCalls.get());
    }

    @Test
    public void rejectsMalformedPageResponses() {
        assertThrows(
                ApiConnectionError.class,
                () -> client("bad-page-cursor").secrets().listPage(
                        SecretListPageParams.builder()
                                .setEnvironmentName("production")
                                .setPageSize(2)
                                .build()
                )
        );
    }

    @Test
    public void retainsStructuredProtocolErrorDetails() {
        LockerClient client = client("not-found");

        ResourceNotFoundError error = assertThrows(
                ResourceNotFoundError.class,
                () -> client.secrets().retrieve("missing", Secret.class)
        );

        assertEquals(-32004, error.getProtocolCode());
        assertEquals("not_found_error", error.getErrorCode());
        assertEquals(false, error.getRetryable());
        assertTrue(error.getRequestId().startsWith("java-"));
    }

    @Test
    public void mapsResponseTooLargeAsNonRetryableOperationError() {
        ResponseTooLargeError error = assertThrows(
                ResponseTooLargeError.class,
                () -> client("response-too-large-error")
                        .secrets()
                        .retrieve("missing", Secret.class)
        );

        assertEquals(-32000, error.getProtocolCode());
        assertEquals("response_too_large", error.getErrorCode());
        assertEquals(false, error.getRetryable());
        assertEquals(
                "protocol response exceeds the size limit",
                error.getUserMessage()
        );
        OperationCancelledError cancelled = assertThrows(
                OperationCancelledError.class,
                () -> client("operation-cancelled-error")
                        .secrets()
                        .retrieve("cancelled", Secret.class)
        );
        assertEquals(false, cancelled.getRetryable());
        assertEquals("request cancelled", cancelled.getUserMessage());
    }

    @Test
    public void mapsStableAndLegacyConflictTaxonomy() {
        AlreadyExistsError duplicate = assertThrows(
                AlreadyExistsError.class,
                () -> client("already-exists")
                        .secrets()
                        .retrieve("duplicate", Secret.class)
        );
        assertEquals(-32009, duplicate.getProtocolCode());
        assertEquals("secret_already_exists", duplicate.getErrorCode());
        assertEquals(
                "a secret with this key already exists",
                duplicate.getUserMessage()
        );
        assertEquals(false, duplicate.getRetryable());
        assertTrue(duplicate instanceof ConflictError);

        assertThrows(
                ConflictError.class,
                () -> client("conflict")
                        .secrets()
                        .retrieve("conflict", Secret.class)
        );
        assertThrows(
                ValidationError.class,
                () -> client("validation-error")
                        .secrets()
                        .retrieve("invalid", Secret.class)
        );
        IntegrityError integrity = assertThrows(
                IntegrityError.class,
                () -> client("integrity-error")
                        .secrets()
                        .retrieve("integrity", Secret.class)
        );
        assertEquals(-32070, integrity.getProtocolCode());
        assertEquals(false, integrity.getRetryable());
        ProtocolError protocol = assertThrows(
                ProtocolError.class,
                () -> client("protocol-error")
                        .secrets()
                        .retrieve("invalid", Secret.class)
        );
        assertEquals(
                "the Locker request parameters are invalid",
                protocol.getUserMessage()
        );
        assertEquals(false, protocol.getRetryable());
        StorageError storage = assertThrows(
                StorageError.class,
                () -> client("storage-error")
                        .secrets()
                        .retrieve("storage", Secret.class)
        );
        assertEquals(false, storage.getRetryable());
        ApiServerError internal = assertThrows(
                ApiServerError.class,
                () -> client("internal-server-error")
                        .secrets()
                        .retrieve("internal", Secret.class)
        );
        assertEquals(
                "the request could not be completed",
                internal.getUserMessage()
        );
        assertEquals(false, internal.getRetryable());

        assertThrows(
                AlreadyExistsError.class,
                () -> client("legacy-already-exists")
                        .secrets()
                        .retrieve("duplicate", Secret.class)
        );
        assertThrows(
                ConflictError.class,
                () -> client("legacy-conflict")
                        .secrets()
                        .retrieve("conflict", Secret.class)
        );

        RequestRejectedError generic = assertThrows(
                RequestRejectedError.class,
                () -> client("generic-request-rejected")
                        .secrets()
                        .retrieve("rejected", Secret.class)
        );
        assertEquals("the request is invalid", generic.getUserMessage());
        assertEquals(false, generic.getRetryable());

        ApiError future = assertThrows(
                ApiError.class,
                () -> client("future-server-error")
                        .secrets()
                        .retrieve("future", Secret.class)
        );
        assertEquals(ApiError.class, future.getClass());
        assertEquals(-32099, future.getProtocolCode());
        assertEquals(true, future.getRetryable());

        ApiError futureKnownKind = assertThrows(
                ApiError.class,
                () -> client("future-known-kind")
                        .secrets()
                        .retrieve("future", Secret.class)
        );
        assertEquals(ApiError.class, futureKnownKind.getClass());
        assertEquals(
                "the Locker operation failed",
                futureKnownKind.getUserMessage()
        );
        assertEquals(true, futureKnownKind.getRetryable());

        assertThrows(
                ProtocolError.class,
                () -> client("outside-server-range")
                        .secrets()
                        .retrieve("future", Secret.class)
        );
        for (String mode : List.of(
                "invalid-error-kind",
                "invalid-error-message"
        )) {
            assertThrows(
                    CliRunError.class,
                    () -> client(mode)
                            .secrets()
                            .retrieve("invalid", Secret.class)
            );
        }
    }

    @Test
    public void validatesAndExposesRateLimitRetryAfterSeconds() {
        for (Map.Entry<String, Integer> fixture : Map.of(
                "rate-limit-zero",
                0,
                "rate-limit-boundary",
                86400
        ).entrySet()) {
            RateLimitError error = assertThrows(
                    RateLimitError.class,
                    () -> client(fixture.getKey())
                            .secrets()
                            .retrieve("limited", Secret.class)
            );
            assertEquals(
                    fixture.getValue(),
                    error.getRetryAfterSeconds()
            );
            assertEquals(true, error.getRetryable());
        }

        for (String mode : List.of(
                "rate-limit-bool",
                "rate-limit-negative",
                "rate-limit-too-large",
                "rate-limit-fraction"
        )) {
            assertThrows(
                    ProtocolError.class,
                    () -> client(mode)
                            .secrets()
                            .retrieve("limited", Secret.class)
            );
        }
    }

    @Test
    public void doesNotExposeRawCliErrorMessages() {
        LockerClient client = client("unsafe-error-message");

        ResourceNotFoundError error = assertThrows(
                ResourceNotFoundError.class,
                () -> client.secrets().retrieve("missing", Secret.class)
        );

        assertEquals(
                "the requested resource was not found",
                error.getUserMessage()
        );
        assertFalse(
                error.getMessage().contains(
                        "sensitive-value-from-broken-cli"
                )
        );
    }

    @Test
    public void failsClosedWhenProtocolVersionIsIncompatible() {
        LockerClient client = client("incompatible");

        assertThrows(
                ApiConnectionError.class,
                () -> client.secrets().retrieve("missing", Secret.class)
        );
    }

    @Test
    public void honorsTheCliAdvertisedRequestLimit() {
        LockerClient client = client("small-limit");

        assertThrows(
                CliRunError.class,
                () -> client.secrets().retrieve("missing", Secret.class)
        );
    }

    @Test
    public void requiresAdvertisedResponseLimitAndBaseMethods() {
        for (String mode : List.of(
                "missing-response-limit",
                "missing-base-method",
                "missing-system-method"
        )) {
            assertThrows(
                    ApiConnectionError.class,
                    () -> client(mode).secrets().retrieve(
                            "missing",
                            Secret.class
                    )
            );
        }
    }

    @Test
    public void negotiatesWithoutAdditivePageMethodsAndFailsPageLocally()
            throws Exception {
        LockerClient client = client("missing-page-method");

        assertEquals(
                "retrieved-value",
                client.secrets().retrieve(
                        "DATABASE_PASSWORD",
                        String.class,
                        "production"
                )
        );
        assertThrows(
                ApiConnectionError.class,
                () -> client.secrets().listPage(
                        SecretListPageParams.builder()
                                .setEnvironmentName("production")
                                .setPageSize(2)
                                .build()
                )
        );
        assertThrows(
                ApiConnectionError.class,
                () -> client.environments().listPage(
                        EnvironmentListPageParams.builder()
                                .setPageSize(1)
                                .build()
                )
        );
    }

    @Test
    public void honorsTheCliAdvertisedResponseLimit() {
        assertThrows(
                CliRunError.class,
                () -> client("advertised-small-response")
                        .secrets()
                        .retrieve(
                                "DATABASE_PASSWORD",
                                Secret.class,
                                "production"
                        )
        );
    }

    @Test
    public void honorsTheCliAdvertisedJsonDepth() {
        assertThrows(
                CliRunError.class,
                () -> client("advertised-small-depth")
                        .secrets()
                        .retrieve(
                                "DATABASE_PASSWORD",
                                String.class,
                                "production"
                        )
        );
    }

    @Test
    public void capsVeryLargeAdvertisedLimitsToLocalBounds()
            throws Exception {
        String value = client("huge-advertised-limits")
                .secrets()
                .retrieve(
                        "DATABASE_PASSWORD",
                        String.class,
                        "production"
                );

        assertEquals("retrieved-value", value);
    }

    @Test
    public void rejectsTrailingProtocolValues() {
        LockerClient client = client("trailing");

        assertThrows(
                CliRunError.class,
                () -> client.secrets().retrieve("missing", Secret.class)
        );
    }

    @Test
    public void rejectsMismatchedProtocolRequestIds() {
        LockerClient client = client("wrong-id");

        assertThrows(
                CliRunError.class,
                () -> client.secrets().retrieve("missing", Secret.class)
        );
    }

    @Test
    public void rejectsNonStandardJsonAndDuplicateFields() {
        for (String mode : List.of(
                "commented-json",
                "duplicate-fields",
                "unpaired-surrogate"
        )) {
            LockerClient client = client(mode);

            assertThrows(
                    CliRunError.class,
                    () -> client.secrets().retrieve(
                            "missing",
                            Secret.class
                    )
            );
        }
    }

    @Test
    public void rejectsMalformedUtf8ProtocolOutput() {
        LockerClient client = client("invalid-utf8");

        assertThrows(
                CliRunError.class,
                () -> client.secrets().retrieve("missing", Secret.class)
        );
    }

    @Test
    public void bindsResponsesToNegotiatedCliVersion() {
        assertThrows(
                ApiConnectionError.class,
                () -> client("cli-version-mismatch")
                        .secrets()
                        .retrieve(
                                "DATABASE_PASSWORD",
                                String.class,
                                "production"
                        )
        );
        assertThrows(
                ApiConnectionError.class,
                () -> client("capability-version-mismatch")
                        .secrets()
                        .retrieve(
                                "DATABASE_PASSWORD",
                                String.class,
                                "production"
                        )
        );
    }

    @Test
    public void binaryIdentityChangeRenegotiatesCapabilities()
            throws Exception {
        Path countPath = Files.createTempFile(
                "locker-java-capabilities-",
                ".count"
        );
        Files.deleteIfExists(countPath);
        try {
            List<String> launcher = fixtureLauncher("success");
            launcher.add(
                    1,
                    "-Dlocker.fixture.capabilityCountPath=" + countPath
            );
            CliProcessRunner runner = new CliProcessRunner(
                    launcher,
                    Duration.ofSeconds(5),
                    1 << 20,
                    1 << 16
            );
            AtomicReference<String> identity =
                    new AtomicReference<>("identity-one");
            SdkProtocolClient protocolClient = new SdkProtocolClient(
                    runner,
                    identity::get
            );
            LockerResponseGetterOptions options = LockerClient.builder()
                    .setAccessKeyId(VALID_ACCESS_KEY_ID)
                    .setSecretAccessKey(VALID_SECRET_ACCESS_KEY)
                    .setHeaders(Map.of(
                            "X-Test-Header",
                            "fake-header-secret"
                    ))
                    .buildOptions();
            LockerClient client = new LockerClient(
                    new LiveLockerResponseGetter(options, protocolClient)
            );

            client.secrets().retrieve(
                    "DATABASE_PASSWORD",
                    String.class,
                    "production"
            );
            identity.set("identity-two");
            client.secrets().retrieve(
                    "DATABASE_PASSWORD",
                    String.class,
                    "production"
            );

            assertEquals(2, Files.readAllLines(countPath).size());
        } finally {
            Files.deleteIfExists(countPath);
        }
    }

    @Test
    public void managedCliIsReverifiedBeforeEveryProcessExecution()
            throws Exception {
        Path managedArtifact = Files.createTempFile(
                "locker-java-managed-cli-",
                ".bin"
        );
        byte[] original = new byte[4096];
        for (int index = 0; index < original.length; index++) {
            original[index] = (byte) index;
        }
        Files.write(managedArtifact, original);
        byte[] expectedDigest = MessageDigest.getInstance("SHA-256")
                .digest(original);
        Arrays.fill(original, (byte) 0);
        FileTime originalMtime =
                Files.getLastModifiedTime(managedArtifact);
        AtomicInteger verificationCount = new AtomicInteger();
        try {
            CliProcessRunner runner = new CliProcessRunner(
                    fixtureLauncher("success"),
                    Duration.ofSeconds(5),
                    1 << 20,
                    1 << 16,
                    deadlineNanos -> {
                        verificationCount.incrementAndGet();
                        byte[] observed =
                                Files.readAllBytes(managedArtifact);
                        byte[] digest = MessageDigest
                                .getInstance("SHA-256")
                                .digest(observed);
                        Arrays.fill(observed, (byte) 0);
                        boolean verified = MessageDigest.isEqual(
                                expectedDigest,
                                digest
                        );
                        Arrays.fill(digest, (byte) 0);
                        if (!verified) {
                            throw new CliRunError(
                                    "Managed Locker CLI failed signed "
                                            + "cache verification"
                            );
                        }
                    }
            );
            byte[] capabilitiesRequest = (
                    "{\"jsonrpc\":\"2.0\",\"id\":\"managed-test\","
                            + "\"method\":\"system.capabilities\","
                            + "\"params\":{}}"
            ).getBytes(StandardCharsets.UTF_8);
            CliProcessRunner.Result first = runner.execute(
                    capabilitiesRequest
            );
            assertEquals(0, first.getExitCode());
            first.clear();
            assertEquals(1, verificationCount.get());

            byte[] tampered = Files.readAllBytes(managedArtifact);
            tampered[tampered.length - 1] ^= 1;
            Files.write(managedArtifact, tampered);
            Arrays.fill(tampered, (byte) 0);
            Files.setLastModifiedTime(
                    managedArtifact,
                    originalMtime
            );
            assertEquals(4096, Files.size(managedArtifact));
            assertEquals(
                    originalMtime,
                    Files.getLastModifiedTime(managedArtifact)
            );

            CliProcessException failure = assertThrows(
                    CliProcessException.class,
                    () -> runner.execute(capabilitiesRequest)
            );
            assertTrue(
                    failure
                            .getMessage()
                            .contains("pre-execution verification"),
                    failure::getMessage
            );
            assertEquals(2, verificationCount.get());
            Arrays.fill(capabilitiesRequest, (byte) 0);
        } finally {
            Arrays.fill(expectedDigest, (byte) 0);
            Files.deleteIfExists(managedArtifact);
        }
    }

    @Test
    public void concurrentReplacementCannotMixCapabilitySnapshots()
            throws Exception {
        AtomicReference<String> identity =
                new AtomicReference<>("identity-one");
        InterleavingProtocolExecutor transport =
                new InterleavingProtocolExecutor();
        SdkProtocolClient protocolClient = new SdkProtocolClient(
                transport,
                identity::get
        );
        LockerClient client = client(protocolClient);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = workers.submit(
                    () -> client.secrets().retrieve(
                            "DATABASE_PASSWORD",
                            String.class,
                            "production"
                    )
            );
            assertTrue(
                    transport.firstOperationStarted.await(
                            5,
                            TimeUnit.SECONDS
                    )
            );

            identity.set("identity-two");
            Future<String> second = workers.submit(
                    () -> client.secrets().retrieve(
                            "DATABASE_PASSWORD",
                            String.class,
                            "production"
                    )
            );
            assertEquals("retrieved-value", second.get(5, TimeUnit.SECONDS));

            transport.releaseFirstOperation.countDown();
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> first.get(5, TimeUnit.SECONDS)
            );
            assertInstanceOf(ApiConnectionError.class, failure.getCause());
            assertEquals(2, transport.capabilityRequests.get());
        } finally {
            transport.releaseFirstOperation.countDown();
            workers.shutdownNow();
        }
    }

    private static LockerClient client(String mode) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Test-Header", "fake-header-secret");
        LockerResponseGetterOptions options = LockerClient.builder()
                .setAccessKeyId(VALID_ACCESS_KEY_ID)
                .setSecretAccessKey(VALID_SECRET_ACCESS_KEY)
                .setHeaders(headers)
                .buildOptions();
        CliProcessRunner runner = new CliProcessRunner(
                fixtureLauncher(mode),
                Duration.ofSeconds(5),
                1 << 20,
                1 << 16
        );
        LiveLockerResponseGetter responseGetter =
                new LiveLockerResponseGetter(
                        options,
                        new SdkProtocolClient(runner)
                );
        return new LockerClient(responseGetter);
    }

    private static LockerClient client(
            SdkProtocolClient protocolClient
    ) {
        LockerResponseGetterOptions options = LockerClient.builder()
                .setAccessKeyId(VALID_ACCESS_KEY_ID)
                .setSecretAccessKey(VALID_SECRET_ACCESS_KEY)
                .buildOptions();
        return new LockerClient(
                new LiveLockerResponseGetter(options, protocolClient)
        );
    }

    private static List<String> fixtureLauncher(String mode) {
        String executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toString();
        List<String> launcher = new ArrayList<>();
        launcher.add(executable);
        launcher.add("-Dlocker.fixture.mode=" + mode);
        launcher.add(
                "-Dlocker.fixture.sdkVersion="
                        + System.getProperty("locker.sdk.version")
        );
        launcher.add("-cp");
        launcher.add(System.getProperty("java.class.path"));
        launcher.add(SdkProtocolFixture.class.getName());
        return launcher;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name")
                .toLowerCase()
                .contains("win");
    }

    private static final class InterleavingProtocolExecutor
            implements SdkProtocolClient.ProtocolExecutor {
        private final CountDownLatch firstOperationStarted =
                new CountDownLatch(1);
        private final CountDownLatch releaseFirstOperation =
                new CountDownLatch(1);
        private final AtomicInteger capabilityRequests =
                new AtomicInteger();
        private final AtomicInteger operationRequests =
                new AtomicInteger();

        @Override
        public CliProcessRunner.Result execute(
                byte[] requestBytes,
                int maxResponseBytes
        ) throws CliProcessException {
            JsonObject request = JsonParser.parseString(
                    new String(
                            requestBytes,
                            StandardCharsets.UTF_8
                    )
            ).getAsJsonObject();
            String method = request.get("method").getAsString();
            JsonElement data;
            if ("system.capabilities".equals(method)) {
                capabilityRequests.incrementAndGet();
                data = capabilities();
            } else {
                int operation = operationRequests.incrementAndGet();
                if (operation == 1) {
                    firstOperationStarted.countDown();
                    try {
                        if (!releaseFirstOperation.await(
                                5,
                                TimeUnit.SECONDS
                        )) {
                            throw new CliProcessException(
                                    CliProcessException.Reason.TIMEOUT,
                                    "Deterministic protocol test timed out"
                            );
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new CliProcessException(
                                CliProcessException.Reason.INTERRUPTED,
                                "Deterministic protocol test interrupted",
                                exception
                        );
                    }
                }
                data = secret();
            }
            byte[] response = successResponse(request, data)
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            if (response.length > maxResponseBytes) {
                throw new CliProcessException(
                        CliProcessException.Reason.OUTPUT_LIMIT,
                        "Deterministic protocol response is too large"
                );
            }
            return new CliProcessRunner.Result(
                    0,
                    response,
                    new byte[0]
            );
        }

        private static JsonObject capabilities() {
            JsonObject protocol = new JsonObject();
            protocol.addProperty("name", "locker.sdk");
            protocol.addProperty("min_version", 1);
            protocol.addProperty("max_version", 1);
            protocol.addProperty(
                    "transport",
                    "json-rpc-2.0-stdio"
            );
            JsonArray methods = new JsonArray();
            for (String method : List.of(
                    "environment.create",
                    "environment.get",
                    "environment.list",
                    "environment.update",
                    "secret.create",
                    "secret.get",
                    "secret.list",
                    "secret.update",
                    "system.capabilities"
            )) {
                methods.add(method);
            }
            JsonObject cli = new JsonObject();
            cli.addProperty("version", "fixture-cli");
            JsonObject limits = new JsonObject();
            limits.addProperty("max_request_bytes", 1 << 20);
            limits.addProperty("max_response_bytes", 1 << 20);
            limits.addProperty("max_json_depth", 64);
            JsonObject data = new JsonObject();
            data.add("protocol", protocol);
            data.add("methods", methods);
            data.add("cli", cli);
            data.add("limits", limits);
            return data;
        }

        private static JsonObject successResponse(
                JsonObject request,
                JsonElement data
        ) {
            JsonObject meta = new JsonObject();
            meta.addProperty("cli_version", "fixture-cli");
            JsonObject result = new JsonObject();
            result.addProperty("protocol_version", 1);
            result.add("data", data);
            result.add("meta", meta);
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", request.get("id").deepCopy());
            response.add("result", result);
            return response;
        }

        private static JsonObject secret() {
            JsonObject secret = new JsonObject();
            secret.addProperty("object", "secret");
            secret.addProperty("id", "secret-id");
            secret.addProperty("creation_date", 1710000000);
            secret.addProperty("revision_date", 1710000001);
            secret.add("updated_date", null);
            secret.add("deleted_date", null);
            secret.add("last_use_date", null);
            secret.addProperty("project_id", 42);
            secret.add("environment_id", null);
            secret.addProperty("environment_name", "production");
            secret.addProperty("key", "DATABASE_PASSWORD");
            secret.addProperty("value", "retrieved-value");
            secret.addProperty("description", "");
            return secret;
        }
    }
}
