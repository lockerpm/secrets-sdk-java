package locker.net;

import com.google.gson.JsonObject;
import locker.exception.ApiConnectionError;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SdkProtocolRequestFactoryTest {
    private static final RequestOptions AUTHENTICATED_OPTIONS =
            RequestOptions.builder()
                    .setAccessKeyId("fake-access-key")
                    .setSecretAccessKey("fake-secret-key")
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
}
