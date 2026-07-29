package locker.service;

import locker.exception.LockerError;
import locker.net.CliResource;
import locker.net.LockerResponseGetter;
import locker.net.RequestOptions;
import locker.param.environment.EnvironmentCreateParams;
import locker.param.secret.SecretCreateParams;
import locker.param.secret.SecretUpdateParams;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServiceRequestSecurityTest {
    @Test
    public void secretMutationDataIsNotAddedToCommandArguments()
            throws LockerError {
        CapturingResponseGetter responseGetter =
                new CapturingResponseGetter();
        SecretService service = new SecretService(responseGetter);
        SecretCreateParams params = SecretCreateParams.builder()
                .setKey("DATABASE_PASSWORD")
                .setValue("sensitive-value")
                .setDescription("sensitive-description")
                .build();

        service.create(params, String.class);

        assertEquals(List.of("secret", "create"), responseGetter.cli);
        assertFalse(responseGetter.cli.contains("sensitive-value"));
        assertEquals("sensitive-value", responseGetter.params.get("value"));
        assertTrue(params.buildCliOptions().isEmpty());
    }

    @Test
    public void environmentMutationDataIsNotAddedToCommandArguments()
            throws LockerError {
        CapturingResponseGetter responseGetter =
                new CapturingResponseGetter();
        EnvironmentService service = new EnvironmentService(responseGetter);
        EnvironmentCreateParams params = EnvironmentCreateParams.builder()
                .setName("production")
                .setExternalUrl("https://example.com")
                .setDescription("internal-description")
                .build();

        service.create(params, String.class);

        assertEquals(List.of("environment", "create"), responseGetter.cli);
        assertFalse(responseGetter.cli.contains("internal-description"));
        assertTrue(responseGetter.params.containsKey("description"));
        assertTrue(params.buildCliOptions().isEmpty());
    }

    @Test
    public void secretUpdateDoesNotMutateCallerParametersOrExposeValues()
            throws LockerError {
        CapturingResponseGetter responseGetter =
                new CapturingResponseGetter();
        SecretService service = new SecretService(responseGetter);
        SecretUpdateParams params = SecretUpdateParams.builder()
                .setValue("rotated-sensitive-value")
                .build();

        service.modify("DATABASE_PASSWORD", params, String.class);

        assertNull(params.getKey());
        assertEquals(
                List.of(
                        "secret",
                        "update",
                        "--key",
                        "DATABASE_PASSWORD"
                ),
                responseGetter.cli
        );
        assertFalse(
                responseGetter.cli.contains("rotated-sensitive-value")
        );
        assertEquals(
                "rotated-sensitive-value",
                responseGetter.params.get("value")
        );
    }

    @SuppressWarnings("unchecked")
    private static final class CapturingResponseGetter
            implements LockerResponseGetter {
        private List<String> cli;
        private Map<String, Object> params;

        @Override
        public <T> T request(
                CliResource.RequestMethod method,
                List<String> cli,
                Map<String, Object> params,
                Type typeToken,
                RequestOptions options
        ) {
            this.cli = List.copyOf(cli);
            this.params = params;
            return (T) "ok";
        }
    }
}
