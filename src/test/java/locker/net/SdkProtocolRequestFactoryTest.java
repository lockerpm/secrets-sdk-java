package locker.net;

import com.google.gson.JsonObject;
import locker.exception.ApiConnectionError;
import locker.exception.AuthenticationError;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SdkProtocolRequestFactoryTest {
    private static final String VALID_ACCESS_KEY_ID =
            "00000000-0000-4000-8000-000000000001";
    private static final String VALID_SECRET_ACCESS_KEY =
            "dGVzdC1vbmx5LWNyZWRlbnRpYWw=";
    private static final RequestOptions AUTHENTICATED_OPTIONS =
            RequestOptions.builder()
                    .setAccessKeyId(VALID_ACCESS_KEY_ID)
                    .setSecretAccessKey(VALID_SECRET_ACCESS_KEY)
                    .build();

    @Test
    public void mapsSecretClearEnvironmentToProtocolNull()
            throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("value", "");
        values.put("description", "");
        values.put("environment_name", "");
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.UPDATE,
                List.of(
                        "secret",
                        "update",
                        "--key",
                        "DATABASE_PASSWORD",
                        "--environment",
                        "production"
                ),
                values,
                AUTHENTICATED_OPTIONS
        );

        SdkProtocolRequestFactory factory =
                new SdkProtocolRequestFactory();
        SdkProtocolRequestFactory.Operation operation =
                factory.operation(request);
        JsonObject params = factory.addContext(
                operation,
                AUTHENTICATED_OPTIONS
        );
        JsonObject changes = params.getAsJsonObject("changes");

        assertEquals("secret.update", operation.getMethod());
        assertEquals(
                "DATABASE_PASSWORD",
                params.get("key").getAsString()
        );
        assertEquals(
                "production",
                params.get("environment").getAsString()
        );
        assertEquals("", changes.get("value").getAsString());
        assertEquals("", changes.get("description").getAsString());
        assertTrue(changes.get("environment").isJsonNull());
    }

    @Test
    public void rejectsNullNonEnvironmentChangeFields() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("value", null);
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.UPDATE,
                List.of(
                        "secret",
                        "update",
                        "--key",
                        "DATABASE_PASSWORD"
                ),
                values,
                AUTHENTICATED_OPTIONS
        );

        assertThrows(
                ApiConnectionError.class,
                () -> new SdkProtocolRequestFactory().operation(request)
        );
    }

    @Test
    public void rejectsEmptyEnvironmentChanges() {
        CliRequest request = new CliRequest(
                CliResource.RequestMethod.UPDATE,
                List.of(
                        "environment",
                        "update",
                        "--name",
                        "production"
                ),
                Map.of(),
                AUTHENTICATED_OPTIONS
        );

        assertThrows(
                ApiConnectionError.class,
                () -> new SdkProtocolRequestFactory().operation(request)
        );
    }

    @Test
    public void secretCreateRequiresAnExplicitValueButAllowsEmpty()
            throws Exception {
        SdkProtocolRequestFactory factory =
                new SdkProtocolRequestFactory();
        CliRequest missing = new CliRequest(
                CliResource.RequestMethod.POST,
                List.of("secret", "create"),
                locker.param.secret.SecretCreateParams.builder()
                        .setKey("EMPTY_ALLOWED")
                        .build()
                        .toMap(),
                AUTHENTICATED_OPTIONS
        );
        assertThrows(
                ApiConnectionError.class,
                () -> factory.operation(missing)
        );

        CliRequest empty = new CliRequest(
                CliResource.RequestMethod.POST,
                List.of("secret", "create"),
                locker.param.secret.SecretCreateParams.builder()
                        .setKey("EMPTY_ALLOWED")
                        .setValue("")
                        .build()
                        .toMap(),
                AUTHENTICATED_OPTIONS
        );
        JsonObject params = factory.addContext(
                factory.operation(empty),
                AUTHENTICATED_OPTIONS
        );
        assertEquals("", params.get("value").getAsString());
    }

    @Test
    public void resolvesCredentialAliasesInCanonicalPrecedenceOrder() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("LOCKER_ACCESS_KEY_ID", "canonical-access");
        environment.put("ACCESS_KEY_ID", "legacy-access");
        environment.put(
                "LOCKER_SECRET_ACCESS_KEY",
                "canonical-secret"
        );
        environment.put("SECRET_ACCESS_KEY", "legacy-secret");
        environment.put(
                "LOCKER_ACCESS_KEY_SECRET",
                "older-secret"
        );
        environment.put("ACCESS_KEY_SECRET", "oldest-secret");

        assertEquals(
                "explicit-access",
                SdkProtocolRequestFactory.resolveAccessKeyId(
                        "explicit-access",
                        environment
                )
        );
        assertEquals(
                "canonical-access",
                SdkProtocolRequestFactory.resolveAccessKeyId(
                        null,
                        environment
                )
        );
        assertEquals(
                "explicit-secret",
                SdkProtocolRequestFactory.resolveSecretAccessKey(
                        "explicit-secret",
                        environment
                )
        );
        assertEquals(
                "canonical-secret",
                SdkProtocolRequestFactory.resolveSecretAccessKey(
                        null,
                        environment
                )
        );

        environment.remove("LOCKER_SECRET_ACCESS_KEY");
        assertEquals(
                "legacy-secret",
                SdkProtocolRequestFactory.resolveSecretAccessKey(
                        null,
                        environment
                )
        );
        environment.remove("SECRET_ACCESS_KEY");
        assertEquals(
                "older-secret",
                SdkProtocolRequestFactory.resolveSecretAccessKey(
                        null,
                        environment
                )
        );
        environment.remove("LOCKER_ACCESS_KEY_SECRET");
        assertEquals(
                "oldest-secret",
                SdkProtocolRequestFactory.resolveSecretAccessKey(
                        null,
                        environment
                )
        );
    }

    @Test
    public void validatesAndNormalizesCredentialsBeforeProtocolUse()
            throws Exception {
        SdkProtocolRequestFactory factory =
                new SdkProtocolRequestFactory();
        RequestOptions whitespace = RequestOptions.builder()
                .setAccessKeyId("  " + VALID_ACCESS_KEY_ID + "  ")
                .setSecretAccessKey(
                        "\t" + VALID_SECRET_ACCESS_KEY + System.lineSeparator()
                )
                .build();

        SdkProtocolRequestFactory.Credentials credentials =
                factory.credentials(whitespace, Map.of());
        JsonObject params = factory.addContext(
                factory.operation(new CliRequest(
                        CliResource.RequestMethod.GET,
                        List.of("secret", "get", "--key", "EXAMPLE"),
                        Map.of(),
                        whitespace
                )),
                whitespace,
                credentials,
                true
        );
        JsonObject encoded = params
                .getAsJsonObject("context")
                .getAsJsonObject("credentials");

        assertEquals(
                VALID_ACCESS_KEY_ID,
                encoded.get("access_key_id").getAsString()
        );
        assertEquals(
                VALID_SECRET_ACCESS_KEY,
                encoded.get("secret_access_key").getAsString()
        );
    }

    @Test
    public void rejectsCredentialsWithStableAuthenticationKinds() {
        SdkProtocolRequestFactory factory =
                new SdkProtocolRequestFactory();

        assertAuthenticationFailure(
                factory,
                RequestOptions.builder()
                        .setAccessKeyId(" ")
                        .setSecretAccessKey(" ")
                        .build(),
                "missing_credentials",
                "access key ID and secret access key are required"
        );
        assertAuthenticationFailure(
                factory,
                RequestOptions.builder()
                        .setAccessKeyId("not-a-uuid")
                        .setSecretAccessKey(VALID_SECRET_ACCESS_KEY)
                        .build(),
                "invalid_access_key_id",
                "access key ID must be a UUIDv4"
        );
        assertAuthenticationFailure(
                factory,
                RequestOptions.builder()
                        .setAccessKeyId(VALID_ACCESS_KEY_ID)
                        .setSecretAccessKey("not canonical base64")
                        .build(),
                "malformed_secret_access_key",
                "secret access key must be non-empty canonical base64"
        );
    }

    private static void assertAuthenticationFailure(
            SdkProtocolRequestFactory factory,
            RequestOptions options,
            String expectedKind,
            String expectedMessage
    ) {
        AuthenticationError error = assertThrows(
                AuthenticationError.class,
                () -> factory.credentials(options, Map.of())
        );

        assertEquals(expectedMessage, error.getUserMessage());
        assertEquals(expectedKind, error.getErrorCode());
        assertEquals(-32001, error.getProtocolCode());
        assertEquals(false, error.getRetryable());
    }
}
