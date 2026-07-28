package locker.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import locker.distribution.LockerCliResolver;
import locker.exception.ApiConnectionError;
import locker.exception.CliRunError;
import locker.exception.LockerError;
import locker.model.Environment;
import locker.model.EnvironmentPage;
import locker.model.LockerCollection;
import locker.model.LockerObjectInterface;
import locker.model.Secret;
import locker.model.SecretPage;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Executes Locker operations through the stable SDK protocol exposed by the
 * Locker CLI.
 */
public class LiveLockerResponseGetter implements LockerResponseGetter {
    private static final Gson GSON = new GsonBuilder().create();

    private final LockerResponseGetterOptions options;
    private final boolean fixedProtocolClient;
    private final ManagedCliResolver cliResolver;
    private volatile ClientBinding protocolBinding;

    public LiveLockerResponseGetter() {
        this(null);
    }

    public LiveLockerResponseGetter(LockerResponseGetterOptions options) {
        this.options = options;
        this.fixedProtocolClient = false;
        this.cliResolver = LockerCliResolver::resolve;
    }

    LiveLockerResponseGetter(
            LockerResponseGetterOptions options,
            SdkProtocolClient protocolClient
    ) {
        this.options = options;
        this.protocolBinding = new ClientBinding(
                null,
                java.util.Objects.requireNonNull(
                        protocolClient,
                        "protocolClient"
                )
        );
        this.fixedProtocolClient = true;
        this.cliResolver = null;
    }

    LiveLockerResponseGetter(
            LockerResponseGetterOptions options,
            ManagedCliResolver cliResolver
    ) {
        this.options = options;
        this.fixedProtocolClient = false;
        this.cliResolver = java.util.Objects.requireNonNull(
                cliResolver,
                "cliResolver"
        );
    }

    @Override
    public <T> T request(
            CliResource.RequestMethod method,
            List<String> cli,
            Map<String, Object> params,
            Type typeToken,
            RequestOptions requestOptions
    ) throws LockerError {
        return request(
                new CliRequest(method, cli, params, requestOptions),
                typeToken
        );
    }

    @Override
    public <T> T request(
            CliRequest request,
            Type typeToken
    ) throws LockerError {
        if (typeToken == null) {
            throw new ApiConnectionError("Response type must not be null");
        }
        RequestOptions merged = RequestOptions.merge(
                this.options,
                request.getOptions()
        );
        SdkProtocolClient.Payload payload = client().execute(request, merged);
        return deserialize(
                payload.getData(),
                operationName(request),
                typeToken
        );
    }

    SdkProtocolClient client() throws CliRunError {
        ClientBinding current = protocolBinding;
        if (fixedProtocolClient) {
            return current.client;
        }

        String cliPath = cliResolver.resolve(
                options == null ? null : options.getCliPath()
        );
        if (current != null
                && cliPath.equals(current.path)) {
            return current.client;
        }
        synchronized (this) {
            current = protocolBinding;
            if (current != null
                    && cliPath.equals(current.path)) {
                return current.client;
            }

            Duration timeout = options == null
                    ? CliProcessRunner.DEFAULT_TIMEOUT
                    : options.getCliTimeout();
            if (timeout == null
                    || timeout.isZero()
                    || timeout.isNegative()) {
                throw new CliRunError(
                        "Locker CLI timeout must be positive"
                );
            }
            SdkProtocolClient client = new SdkProtocolClient(
                    new CliProcessRunner(
                            cliPath,
                            timeout
                    )
            );
            protocolBinding = new ClientBinding(cliPath, client);
            return client;
        }
    }

    @FunctionalInterface
    interface ManagedCliResolver {
        String resolve(String explicitPath) throws CliRunError;
    }

    private static final class ClientBinding {
        private final String path;
        private final SdkProtocolClient client;

        private ClientBinding(
                String path,
                SdkProtocolClient client
        ) {
            this.path = path;
            this.client = client;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T deserialize(
            JsonElement data,
            String operation,
            Type typeToken
    ) throws LockerError {
        if (typeToken.equals(String.class)) {
            if ("secret.get".equals(operation)) {
                JsonObject secret = requireObject(data, operation);
                validateSecretData(secret);
                JsonElement value = secret.get("value");
                if (value == null
                        || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isString()) {
                    throw new ApiConnectionError(
                            "Invalid Locker CLI secret response"
                    );
                }
                return (T) value.getAsString();
            }
            return (T) GSON.toJson(data);
        }

        Type rawType = typeToken;
        if (typeToken instanceof ParameterizedType) {
            rawType = ((ParameterizedType) typeToken).getRawType();
        }
        if (rawType.equals(LockerCollection.class)) {
            return (T) deserializeCollection(data, operation);
        }
        if (rawType.equals(SecretPage.class)
                && "secret.list_page".equals(operation)) {
            return (T) deserializeSecretPage(data);
        }
        if (rawType.equals(EnvironmentPage.class)
                && "environment.list_page".equals(operation)) {
            return (T) deserializeEnvironmentPage(data);
        }

        if (rawType.equals(Secret.class)) {
            JsonObject secretData = requireObject(data, operation);
            validateSecretData(secretData);
            Secret secret = GSON.fromJson(secretData, Secret.class);
            validateSecret(secret);
            secret.setResponseGetter(this);
            return (T) secret;
        }
        if (rawType.equals(Environment.class)) {
            JsonObject environmentData = requireObject(data, operation);
            validateEnvironmentData(environmentData);
            Environment environment = GSON.fromJson(
                    environmentData,
                    Environment.class
            );
            validateEnvironment(environment);
            environment.setResponseGetter(this);
            return (T) environment;
        }
        if (rawType instanceof Class<?>
                && LockerObjectInterface.class.isAssignableFrom(
                (Class<?>) rawType
        )) {
            T object = GSON.fromJson(data, typeToken);
            if (object == null) {
                throw new ApiConnectionError(
                        "Invalid Locker CLI object response"
                );
            }
            return object;
        }
        if (rawType instanceof Class<?>
                && List.class.isAssignableFrom((Class<?>) rawType)) {
            return GSON.fromJson(data, typeToken);
        }
        throw new ApiConnectionError("Unsupported response type");
    }

    private LockerCollection<?> deserializeCollection(
            JsonElement data,
            String operation
    ) throws ApiConnectionError {
        if (data == null || !data.isJsonArray()) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI collection response"
            );
        }
        JsonArray array = data.getAsJsonArray();
        if ("secret.list".equals(operation)) {
            LockerCollection<Secret> secrets = new LockerCollection<>();
            for (JsonElement element : array) {
                JsonObject secretData = requireObject(element, operation);
                validateSecretData(secretData);
                Secret secret = GSON.fromJson(secretData, Secret.class);
                validateSecret(secret);
                secret.setResponseGetter(this);
                secrets.add(secret);
            }
            return secrets;
        }
        if ("environment.list".equals(operation)) {
            LockerCollection<Environment> environments =
                    new LockerCollection<>();
            for (JsonElement element : array) {
                JsonObject environmentData = requireObject(
                        element,
                        operation
                );
                validateEnvironmentData(environmentData);
                Environment environment = GSON.fromJson(
                        environmentData,
                        Environment.class
                );
                validateEnvironment(environment);
                environment.setResponseGetter(this);
                environments.add(environment);
            }
            return environments;
        }
        throw new ApiConnectionError(
                "Invalid Locker CLI collection response"
        );
    }

    private SecretPage deserializeSecretPage(
            JsonElement data
    ) throws ApiConnectionError {
        JsonObject page = requireObject(data, "secret.list_page");
        requireString(page, "object", "secret_page");
        JsonArray items = requirePageItems(page);
        LockerCollection<Secret> secrets = new LockerCollection<>();
        for (JsonElement element : items) {
            JsonObject secretData = requireObject(
                    element,
                    "secret.list_page"
            );
            validateSecretData(secretData);
            Secret secret = GSON.fromJson(secretData, Secret.class);
            validateSecret(secret);
            secret.setResponseGetter(this);
            secrets.add(secret);
        }
        return new SecretPage(secrets, requireNextCursor(page));
    }

    private EnvironmentPage deserializeEnvironmentPage(
            JsonElement data
    ) throws ApiConnectionError {
        JsonObject page = requireObject(data, "environment.list_page");
        requireString(page, "object", "environment_page");
        JsonArray items = requirePageItems(page);
        LockerCollection<Environment> environments =
                new LockerCollection<>();
        for (JsonElement element : items) {
            JsonObject environmentData = requireObject(
                    element,
                    "environment.list_page"
            );
            validateEnvironmentData(environmentData);
            Environment environment = GSON.fromJson(
                    environmentData,
                    Environment.class
            );
            validateEnvironment(environment);
            environment.setResponseGetter(this);
            environments.add(environment);
        }
        return new EnvironmentPage(
                environments,
                requireNextCursor(page)
        );
    }

    private static JsonArray requirePageItems(
            JsonObject page
    ) throws ApiConnectionError {
        JsonElement items = page.get("items");
        if (items == null
                || !items.isJsonArray()
                || items.getAsJsonArray().size() > 1000) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI page response"
            );
        }
        return items.getAsJsonArray();
    }

    private static String requireNextCursor(
            JsonObject page
    ) throws ApiConnectionError {
        if (!page.has("next_cursor")) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI page response"
            );
        }
        JsonElement cursor = page.get("next_cursor");
        if (cursor.isJsonNull()) {
            return null;
        }
        if (!cursor.isJsonPrimitive()
                || !cursor.getAsJsonPrimitive().isString()) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI page response"
            );
        }
        String value = cursor.getAsString();
        if (value.isEmpty() || value.length() > 4096) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI page response"
            );
        }
        return value;
    }

    private static JsonObject requireObject(
            JsonElement data,
            String operation
    ) throws ApiConnectionError {
        if (data == null || !data.isJsonObject()) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI object response"
            );
        }
        return data.getAsJsonObject();
    }

    private static void validateSecretData(
            JsonObject secret
    ) throws ApiConnectionError {
        forbidField(secret, "secret_hash");
        forbidField(secret, "environment_hash");
        requireString(secret, "object", "secret");
        requireString(secret, "id", null);
        requireNumber(secret, "creation_date");
        requireNumber(secret, "revision_date");
        requireNullableNumber(secret, "updated_date");
        requireNullableNumber(secret, "deleted_date");
        requireNullableNumber(secret, "last_use_date");
        requireInteger(secret, "project_id");
        requireNullableString(secret, "environment_id");
        requireNullableString(secret, "environment_name");
        requireString(secret, "key", null);
        requireString(secret, "value", null);
        requireString(secret, "description", null);
    }

    private static void validateEnvironmentData(
            JsonObject environment
    ) throws ApiConnectionError {
        forbidField(environment, "environment_hash");
        requireString(environment, "object", "environment");
        requireString(environment, "id", null);
        requireString(environment, "name", null);
        requireString(environment, "external_url", null);
        requireString(environment, "description", null);
        requireNumber(environment, "creation_date");
        requireNumber(environment, "revision_date");
        requireNullableNumber(environment, "updated_date");
        requireInteger(environment, "project_id");
    }

    private static void forbidField(
            JsonObject object,
            String field
    ) throws ApiConnectionError {
        if (object.has(field)) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI object response"
            );
        }
    }

    private static void requireString(
            JsonObject object,
            String field,
            String expected
    ) throws ApiConnectionError {
        JsonElement value = object.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()
                || (expected != null && !expected.equals(value.getAsString()))) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI object response"
            );
        }
    }

    private static void requireNullableString(
            JsonObject object,
            String field
    ) throws ApiConnectionError {
        JsonElement value = object.get(field);
        if (value == null
                || (!value.isJsonNull()
                && (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()))) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI object response"
            );
        }
    }

    private static void requireNumber(
            JsonObject object,
            String field
    ) throws ApiConnectionError {
        JsonElement value = object.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI object response"
            );
        }
    }

    private static void requireNullableNumber(
            JsonObject object,
            String field
    ) throws ApiConnectionError {
        JsonElement value = object.get(field);
        if (value == null
                || (!value.isJsonNull()
                && (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()))) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI object response"
            );
        }
    }

    private static void requireInteger(
            JsonObject object,
            String field
    ) throws ApiConnectionError {
        JsonElement value = object.get(field);
        if (value == null
                || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI object response"
            );
        }
        try {
            value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI object response"
            );
        }
    }

    private static void validateSecret(
            Secret secret
    ) throws ApiConnectionError {
        if (secret == null
                || secret.getId() == null
                || secret.getObject() == null
                || secret.getKey() == null
                || secret.getValue() == null) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI secret response"
            );
        }
    }

    private static void validateEnvironment(
            Environment environment
    ) throws ApiConnectionError {
        if (environment == null
                || environment.getId() == null
                || environment.getObject() == null
                || environment.getName() == null) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI environment response"
            );
        }
    }

    private static String operationName(
            CliRequest request
    ) throws ApiConnectionError {
        List<String> cli = request.getCli();
        if (cli == null || cli.size() < 2) {
            throw new ApiConnectionError("Unsupported Locker SDK operation");
        }
        return cli.get(0) + "." + cli.get(1);
    }
}
