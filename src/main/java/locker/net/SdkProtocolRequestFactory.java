package locker.net;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import locker.LockerConfiguration;
import locker.exception.ApiConnectionError;
import locker.exception.AuthenticationError;
import locker.exception.LockerError;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class SdkProtocolRequestFactory {
    static final int PROTOCOL_VERSION = 1;
    static final String PROTOCOL_NAME = "locker.sdk";
    static final String JSON_RPC_VERSION = "2.0";
    static final String CLIENT_NAME = "locker-java";
    private static final int CODE_AUTHENTICATION = -32001;
    private static final int MAX_CREDENTIAL_LENGTH = 65_536;
    private static final Pattern ACCESS_KEY_ID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
                    + "[89ab][0-9a-f]{3}-[0-9a-f]{12}",
            Pattern.CASE_INSENSITIVE
    );

    Operation operation(CliRequest request) throws ApiConnectionError {
        if (request == null) {
            throw new ApiConnectionError("Locker SDK request must not be null");
        }
        List<String> cli = request.getCli();
        if (cli == null || cli.size() < 2) {
            throw new ApiConnectionError("Unsupported Locker SDK operation");
        }

        String resource = cli.get(0);
        String action = cli.get(1);
        String method = resource + "." + action;
        JsonObject params = new JsonObject();
        Map<String, Object> values = request.getParams();

        switch (method) {
            case "secret.get":
                addRequired(params, "key", flag(cli, "--key"));
                addOptional(params, "environment", flag(cli, "--environment"));
                break;
            case "secret.list":
                String listEnvironment = flag(cli, "--environment");
                if (listEnvironment == null) {
                    listEnvironment = mapString(
                            values,
                            "environment_name",
                            false
                    );
                }
                addOptional(params, "environment", listEnvironment);
                break;
            case "secret.list_page":
                addOptional(
                        params,
                        "environment",
                        mapString(values, "environment_name", false)
                );
                addOptional(
                        params,
                        "page_size",
                        mapInteger(values, "page_size")
                );
                addOptional(
                        params,
                        "cursor",
                        mapString(values, "cursor", false)
                );
                break;
            case "secret.create":
                addRequired(params, "key", mapString(values, "key", true));
                addRequired(params, "value", mapString(values, "value", true));
                addOptional(
                        params,
                        "environment",
                        mapString(values, "environment_name", false)
                );
                addOptional(
                        params,
                        "description",
                        mapString(values, "description", false)
                );
                break;
            case "secret.update":
                addRequired(params, "key", flag(cli, "--key"));
                addOptional(params, "environment", flag(cli, "--environment"));
                params.add("changes", secretChanges(values));
                break;
            case "environment.get":
                addRequired(params, "name", flag(cli, "--name"));
                break;
            case "environment.list":
                break;
            case "environment.list_page":
                addOptional(
                        params,
                        "page_size",
                        mapInteger(values, "page_size")
                );
                addOptional(
                        params,
                        "cursor",
                        mapString(values, "cursor", false)
                );
                break;
            case "environment.create":
                addRequired(params, "name", mapString(values, "name", true));
                addOptional(
                        params,
                        "external_url",
                        mapString(values, "external_url", false)
                );
                addOptional(
                        params,
                        "description",
                        mapString(values, "description", false)
                );
                break;
            case "environment.update":
                addRequired(params, "name", flag(cli, "--name"));
                params.add("changes", environmentChanges(values));
                break;
            default:
                throw new ApiConnectionError("Unsupported Locker SDK operation");
        }
        return new Operation(method, params);
    }

    JsonObject addContext(
            Operation operation,
            RequestOptions options
    ) throws LockerError {
        return addContext(operation, options, false);
    }

    JsonObject addContext(
            Operation operation,
            RequestOptions options,
            boolean typedErrorContract
    ) throws LockerError {
        return addContext(
                operation,
                options,
                credentials(options),
                typedErrorContract
        );
    }

    JsonObject addContext(
            Operation operation,
            RequestOptions options,
            Credentials resolvedCredentials,
            boolean typedErrorContract
    ) throws LockerError {
        JsonObject credentials = new JsonObject();
        credentials.addProperty(
                "access_key_id",
                resolvedCredentials.accessKeyId
        );
        credentials.addProperty(
                "secret_access_key",
                resolvedCredentials.secretAccessKey
        );

        JsonObject client = new JsonObject();
        client.addProperty("name", CLIENT_NAME);
        client.addProperty("version", LockerConfiguration.SDK_VERSION);

        JsonObject context = new JsonObject();
        context.addProperty("protocol_version", PROTOCOL_VERSION);
        if (typedErrorContract) {
            context.addProperty("error_contract", "typed-v1");
        }
        context.add("credentials", credentials);
        context.add("client", client);

        JsonObject transport = transport(options);
        if (transport.size() > 0) {
            context.add("transport", transport);
        }

        JsonObject params = new JsonObject();
        params.add("context", context);
        for (Map.Entry<String, JsonElement> entry
                : operation.params.entrySet()) {
            params.add(entry.getKey(), entry.getValue().deepCopy());
        }
        return params;
    }

    Credentials credentials(RequestOptions options) throws AuthenticationError {
        return credentials(options, System.getenv());
    }

    Credentials credentials(
            RequestOptions options,
            Map<String, String> environment
    ) throws AuthenticationError {
        String accessKeyId = resolveAccessKeyId(
                options == null ? null : options.getAccessKeyId(),
                environment
        );
        String secretAccessKey = resolveSecretAccessKey(
                options == null ? null : options.getSecretAccessKey(),
                environment
        );
        if (accessKeyId == null || secretAccessKey == null) {
            throw authenticationError(
                    "access key ID and secret access key are required",
                    "missing_credentials"
            );
        }
        if (accessKeyId.length() > MAX_CREDENTIAL_LENGTH
                || !ACCESS_KEY_ID_PATTERN.matcher(accessKeyId).matches()) {
            throw authenticationError(
                    "access key ID must be a UUIDv4",
                    "invalid_access_key_id"
            );
        }

        if (secretAccessKey.length() > MAX_CREDENTIAL_LENGTH) {
            throw authenticationError(
                    "secret access key must be non-empty canonical base64",
                    "malformed_secret_access_key"
            );
        }

        byte[] decodedSecret = null;
        boolean canonicalSecret = false;
        try {
            decodedSecret = Base64.getDecoder().decode(secretAccessKey);
            canonicalSecret = decodedSecret.length > 0
                    && Base64.getEncoder()
                    .encodeToString(decodedSecret)
                    .equals(secretAccessKey);
        } catch (IllegalArgumentException ignored) {
            canonicalSecret = false;
        } finally {
            if (decodedSecret != null) {
                Arrays.fill(decodedSecret, (byte) 0);
            }
        }
        if (!canonicalSecret) {
            throw authenticationError(
                    "secret access key must be non-empty canonical base64",
                    "malformed_secret_access_key"
            );
        }
        return new Credentials(accessKeyId, secretAccessKey);
    }

    private static AuthenticationError authenticationError(
            String message,
            String kind
    ) {
        return new AuthenticationError(
                message,
                kind,
                CODE_AUTHENTICATION,
                null,
                false
        );
    }

    private static JsonObject transport(
            RequestOptions options
    ) throws ApiConnectionError {
        JsonObject transport = new JsonObject();
        if (options == null) {
            return transport;
        }
        if (options.getApiBase() != null && !options.getApiBase().isBlank()) {
            transport.addProperty("api_base", options.getApiBase());
        }
        Map<String, String> headers = options.getHeaders();
        if (headers != null && !headers.isEmpty()) {
            JsonObject protocolHeaders = new JsonObject();
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey() == null || header.getValue() == null) {
                    throw new ApiConnectionError(
                            "Locker transport headers must not contain null values"
                    );
                }
                protocolHeaders.addProperty(header.getKey(), header.getValue());
            }
            transport.add("headers", protocolHeaders);
        }
        return transport;
    }

    private static JsonObject secretChanges(
            Map<String, Object> values
    ) throws ApiConnectionError {
        JsonObject changes = new JsonObject();
        copyNonNullMapString(values, "key", changes, "key");
        copyNonNullMapString(values, "value", changes, "value");
        copyNonNullMapString(
                values,
                "description",
                changes,
                "description"
        );
        if (contains(values, "environment_name")) {
            String environment = mapString(
                    values,
                    "environment_name",
                    false
            );
            if (environment == null || environment.isEmpty()) {
                changes.add("environment", JsonNull.INSTANCE);
            } else {
                changes.addProperty("environment", environment);
            }
        }
        requireChanges(changes);
        return changes;
    }

    private static JsonObject environmentChanges(
            Map<String, Object> values
    ) throws ApiConnectionError {
        JsonObject changes = new JsonObject();
        copyNonNullMapString(values, "name", changes, "name");
        copyNonNullMapString(
                values,
                "external_url",
                changes,
                "external_url"
        );
        copyNonNullMapString(
                values,
                "description",
                changes,
                "description"
        );
        requireChanges(changes);
        return changes;
    }

    private static void copyNonNullMapString(
            Map<String, Object> source,
            String sourceName,
            JsonObject target,
            String targetName
    ) throws ApiConnectionError {
        if (contains(source, sourceName)) {
            String value = mapString(source, sourceName, false);
            if (value == null) {
                throw new ApiConnectionError(
                        "Locker SDK change fields must not be null"
                );
            }
            target.addProperty(targetName, value);
        }
    }

    private static void requireChanges(JsonObject changes)
            throws ApiConnectionError {
        if (changes.size() == 0) {
            throw new ApiConnectionError(
                    "Locker SDK update requires at least one change"
            );
        }
    }

    private static String flag(List<String> cli, String flagName)
            throws ApiConnectionError {
        for (int index = 2; index < cli.size(); index++) {
            if (!flagName.equals(cli.get(index))) {
                continue;
            }
            if (index + 1 >= cli.size() || cli.get(index + 1) == null) {
                throw new ApiConnectionError("Invalid Locker SDK operation");
            }
            return cli.get(index + 1);
        }
        return null;
    }

    private static String mapString(
            Map<String, Object> values,
            String name,
            boolean required
    ) throws ApiConnectionError {
        if (!contains(values, name)) {
            if (required) {
                throw new ApiConnectionError("Invalid Locker SDK operation");
            }
            return null;
        }
        Object value = values.get(name);
        if (value == null) {
            if (required) {
                throw new ApiConnectionError("Invalid Locker SDK operation");
            }
            return null;
        }
        if (!(value instanceof String)) {
            throw new ApiConnectionError("Invalid Locker SDK operation");
        }
        return (String) value;
    }

    private static Integer mapInteger(
            Map<String, Object> values,
            String name
    ) throws ApiConnectionError {
        if (!contains(values, name) || values.get(name) == null) {
            return null;
        }
        Object value = values.get(name);
        if (!(value instanceof Number)) {
            throw new ApiConnectionError("Invalid Locker SDK operation");
        }
        try {
            return new BigDecimal(value.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ApiConnectionError("Invalid Locker SDK operation");
        }
    }

    private static boolean contains(Map<String, Object> values, String name) {
        return values != null && values.containsKey(name);
    }

    private static void addRequired(
            JsonObject object,
            String name,
            String value
    ) throws ApiConnectionError {
        if (value == null) {
            throw new ApiConnectionError("Invalid Locker SDK operation");
        }
        object.addProperty(name, value);
    }

    private static void addOptional(
            JsonObject object,
            String name,
            String value
    ) {
        if (value != null) {
            object.addProperty(name, value);
        }
    }

    private static void addOptional(
            JsonObject object,
            String name,
            Integer value
    ) {
        if (value != null) {
            object.addProperty(name, value);
        }
    }

    private static String firstConfigured(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    static String resolveAccessKeyId(
            String configured,
            Map<String, String> environment
    ) {
        return firstConfigured(
                configured,
                environment.get("LOCKER_ACCESS_KEY_ID"),
                environment.get("ACCESS_KEY_ID")
        );
    }

    static String resolveSecretAccessKey(
            String configured,
            Map<String, String> environment
    ) {
        return firstConfigured(
                configured,
                environment.get("LOCKER_SECRET_ACCESS_KEY"),
                environment.get("SECRET_ACCESS_KEY"),
                environment.get("LOCKER_ACCESS_KEY_SECRET"),
                environment.get("ACCESS_KEY_SECRET")
        );
    }

    static final class Operation {
        private final String method;
        private final JsonObject params;

        private Operation(String method, JsonObject params) {
            this.method = method;
            this.params = params;
        }

        String getMethod() {
            return method;
        }
    }

    static final class Credentials {
        private final String accessKeyId;
        private final String secretAccessKey;

        private Credentials(String accessKeyId, String secretAccessKey) {
            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
        }
    }
}
