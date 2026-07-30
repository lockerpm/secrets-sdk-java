package locker.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

public final class SdkProtocolFixture {
    private SdkProtocolFixture() {
    }

    public static void main(String[] args) {
        if (args.length != 1 || !"sdk".equals(args[0])) {
            System.exit(91);
        }

        JsonObject request = JsonParser.parseReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
        ).getAsJsonObject();
        String method = request.get("method").getAsString();
        String mode = System.getProperty("locker.fixture.mode", "success");

        JsonObject response;
        if ("system.capabilities".equals(method)) {
            recordCapabilityNegotiation();
            response = capabilityResponse(
                    request,
                    "incompatible".equals(mode) ? 2 : 1
            );
        } else if ("not-found".equals(mode)
                || "unsafe-error-message".equals(mode)) {
            response = notFoundResponse(request);
        } else if ("response-too-large-error".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32000,
                    "response_too_large",
                    "response too large"
            );
        } else if ("operation-cancelled-error".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32000,
                    "cancelled",
                    "unsafe cancellation detail"
            );
        } else if ("already-exists".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32009,
                    "secret_already_exists",
                    "unsafe duplicate detail"
            );
        } else if ("conflict".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32009,
                    "conflict",
                    "unsafe conflict detail"
            );
        } else if ("validation-error".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32022,
                    "validation_error",
                    "unsafe validation detail"
            );
        } else if ("integrity-error".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32070,
                    "integrity_error",
                    "unsafe integrity detail"
            );
        } else if ("protocol-error".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32602,
                    "invalid_params",
                    "unsafe protocol detail"
            );
        } else if ("storage-error".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32060,
                    "storage_error",
                    "unsafe storage detail"
            );
        } else if ("internal-server-error".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32051,
                    "internal_error",
                    "unsafe internal detail"
            );
        } else if (mode.startsWith("rate-limit-")) {
            response = operationErrorResponse(
                    request,
                    -32029,
                    "rate_limited",
                    "unsafe rate limit detail"
            );
        } else if ("future-known-kind".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32099,
                    "secret_already_exists",
                    "safe future error"
            );
        } else if ("legacy-already-exists".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32000,
                    "duplicate_hash",
                    "unsafe legacy duplicate detail"
            );
        } else if ("legacy-conflict".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32000,
                    "conflict",
                    "unsafe legacy conflict detail"
            );
        } else if ("generic-request-rejected".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32000,
                    "request_rejected",
                    "unsafe generic rejection detail"
            );
        } else if ("future-server-error".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32099,
                    "future_error",
                    "safe future error"
            );
        } else if ("outside-server-range".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32100,
                    "future_error",
                    "safe future error"
            );
        } else if ("invalid-error-kind".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32000,
                    "Invalid-Kind",
                    "safe error"
            );
        } else if ("invalid-error-message".equals(mode)) {
            response = operationErrorResponse(
                    request,
                    -32000,
                    "operation_error",
                    "unsafe\nlog"
            );
        } else {
            validateContext(request);
            response = successResponse(request, method);
        }
        if ("invalid-utf8".equals(mode)) {
            try {
                System.out.write(new byte[]{(byte) 0xC3, 0x28});
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        } else if ("commented-json".equals(mode)) {
            System.out.print("/*not-json*/");
            System.out.print(response);
        } else if ("duplicate-fields".equals(mode)) {
            System.out.print(
                    "{\"jsonrpc\":\"2.0\","
                            + response.toString().substring(1)
            );
        } else if ("unpaired-surrogate".equals(mode)) {
            System.out.print(
                    response.toString().replace(
                            "retrieved-value",
                            "\\uD800"
                    )
            );
        } else {
            System.out.print(response);
        }
        if ("trailing".equals(mode)) {
            System.out.print("{}");
        }
    }

    private static JsonObject capabilityResponse(
            JsonObject request,
            int minVersion
    ) {
        if (!request.getAsJsonObject("params").entrySet().isEmpty()) {
            throw new IllegalArgumentException(
                    "Capabilities must not contain credentials"
            );
        }
        JsonObject protocol = new JsonObject();
        protocol.addProperty("name", "locker.sdk");
        protocol.addProperty("min_version", minVersion);
        protocol.addProperty("max_version", minVersion);
        protocol.addProperty("transport", "json-rpc-2.0-stdio");

        JsonArray methods = new JsonArray();
        if (!"missing-base-method".equals(
                System.getProperty("locker.fixture.mode")
        )) {
            methods.add("secret.get");
        }
        methods.add("secret.list");
        if (!"missing-page-method".equals(
                System.getProperty("locker.fixture.mode")
        )) {
            methods.add("secret.list_page");
        }
        methods.add("secret.create");
        methods.add("secret.update");
        methods.add("environment.get");
        methods.add("environment.list");
        if (!"missing-page-method".equals(
                System.getProperty("locker.fixture.mode")
        )) {
            methods.add("environment.list_page");
        }
        methods.add("environment.create");
        methods.add("environment.update");
        if (!"missing-system-method".equals(
                System.getProperty("locker.fixture.mode")
        )) {
            methods.add("system.capabilities");
        }

        JsonObject data = new JsonObject();
        data.add("protocol", protocol);
        data.add("methods", methods);
        data.add("cli", object("version", "fixture-cli"));
        String fixtureMode = System.getProperty("locker.fixture.mode");
        if (!"legacy-error-contract".equals(fixtureMode)) {
            JsonArray errorContracts = new JsonArray();
            if ("unknown-error-contract".equals(fixtureMode)) {
                errorContracts.add("future-v2");
            } else if ("invalid-error-contract".equals(fixtureMode)) {
                errorContracts.add("Invalid_Contract");
            } else {
                errorContracts.add("typed-v1");
            }
            data.add("error_contracts", errorContracts);
        }
        JsonObject limits = longObject(
                "max_request_bytes",
                "small-limit".equals(
                        fixtureMode
                ) ? 1 : "huge-advertised-limits".equals(fixtureMode)
                        ? 5L << 30
                        : 20L << 20
        );
        if (!"missing-response-limit".equals(
                System.getProperty("locker.fixture.mode")
        )) {
            limits.addProperty(
                    "max_response_bytes",
                    "advertised-small-response".equals(
                            fixtureMode
                    ) ? 256 : "huge-advertised-limits".equals(fixtureMode)
                            ? 5L << 30
                            : 20L << 20
            );
        }
        limits.addProperty(
                "max_json_depth",
                "advertised-small-depth".equals(fixtureMode)
                        ? 4
                        : 256
        );
        data.add("limits", limits);
        return successEnvelope(request, data);
    }

    private static JsonObject successResponse(
            JsonObject request,
            String method
    ) {
        if ("advertised-small-depth".equals(
                System.getProperty("locker.fixture.mode")
        )) {
            JsonObject nested = object("value", "too-deep");
            for (int depth = 0; depth < 5; depth++) {
                JsonObject parent = new JsonObject();
                parent.add("nested", nested);
                nested = parent;
            }
            return successEnvelope(request, nested);
        }
        JsonObject params = request.getAsJsonObject("params");
        switch (method) {
            case "secret.get":
                require(params, "key", "DATABASE_PASSWORD");
                require(params, "environment", "production");
                return successEnvelope(
                        request,
                        secret("DATABASE_PASSWORD", "retrieved-value")
                );
            case "secret.list": {
                JsonArray secrets = new JsonArray();
                secrets.add(secret("FIRST", "one"));
                secrets.add(secret("SECOND", "two"));
                return successEnvelope(request, secrets);
            }
            case "secret.list_page": {
                require(params, "environment", "production");
                requireInteger(params, "page_size", 2);
                JsonArray secrets = new JsonArray();
                secrets.add(secret("FIRST", "one"));
                JsonObject page = new JsonObject();
                page.addProperty("object", "secret_page");
                page.add("items", secrets);
                if ("bad-page-cursor".equals(
                        System.getProperty("locker.fixture.mode")
                )) {
                    page.addProperty("next_cursor", "");
                } else if (params.has("cursor")) {
                    require(params, "cursor", "secret-next");
                    page.add("next_cursor", null);
                } else {
                    page.addProperty("next_cursor", "secret-next");
                }
                return successEnvelope(request, page);
            }
            case "secret.create":
                require(params, "key", "CREATED_KEY");
                require(params, "value", "sensitive-create-value");
                require(params, "description", "");
                return successEnvelope(
                        request,
                        secret("CREATED_KEY", "sensitive-create-value")
                );
            case "environment.list_page": {
                requireInteger(params, "page_size", 1);
                JsonArray environments = new JsonArray();
                environments.add(environment("production"));
                JsonObject page = new JsonObject();
                page.addProperty("object", "environment_page");
                page.add("items", environments);
                page.add("next_cursor", null);
                return successEnvelope(request, page);
            }
            default:
                throw new IllegalArgumentException("Unsupported fixture method");
        }
    }

    private static void validateContext(JsonObject request) {
        JsonObject context = request.getAsJsonObject("params")
                .getAsJsonObject("context");
        if (context.get("protocol_version").getAsInt() != 1) {
            throw new IllegalArgumentException("protocol version");
        }
        String fixtureMode = System.getProperty("locker.fixture.mode");
        if ("legacy-error-contract".equals(fixtureMode)
                || "unknown-error-contract".equals(fixtureMode)) {
            if (context.has("error_contract")) {
                throw new IllegalArgumentException(
                        "unadvertised error contract"
                );
            }
        } else {
            require(context, "error_contract", "typed-v1");
        }
        JsonObject credentials = context.getAsJsonObject("credentials");
        require(
                credentials,
                "access_key_id",
                "00000000-0000-4000-8000-000000000001"
        );
        require(
                credentials,
                "secret_access_key",
                "dGVzdC1vbmx5LWNyZWRlbnRpYWw="
        );
        JsonObject client = context.getAsJsonObject("client");
        require(client, "name", "locker-java");
        String expectedSdkVersion = System.getProperty(
                "locker.fixture.sdkVersion"
        );
        if (expectedSdkVersion == null
                || expectedSdkVersion.isBlank()) {
            throw new IllegalArgumentException("SDK version");
        }
        require(client, "version", expectedSdkVersion);
        JsonObject transport = context.getAsJsonObject("transport");
        require(
                transport.getAsJsonObject("headers"),
                "X-Test-Header",
                "fake-header-secret"
        );
    }

    private static JsonObject notFoundResponse(JsonObject request) {
        return operationErrorResponse(
                request,
                -32004,
                "not_found_error",
                "unsafe-error-message".equals(
                        System.getProperty("locker.fixture.mode")
                )
                        ? "sensitive-value-from-broken-cli"
                        : "Locker resource was not found"
        );
    }

    private static JsonObject operationErrorResponse(
            JsonObject request,
            int code,
            String kind,
            String message
    ) {
        JsonObject data = new JsonObject();
        String fixtureMode = System.getProperty("locker.fixture.mode");
        data.addProperty("protocol_version", 1);
        data.addProperty("kind", kind);
        data.addProperty(
                "retryable",
                fixtureMode.startsWith("rate-limit-") || Set.of(
                        "already-exists",
                        "conflict",
                        "validation-error",
                        "integrity-error",
                        "legacy-already-exists",
                        "legacy-conflict",
                        "response-too-large-error",
                        "generic-request-rejected",
                        "operation-cancelled-error",
                        "not-found",
                        "protocol-error",
                        "storage-error",
                        "internal-server-error",
                        "future-server-error",
                        "future-known-kind"
                ).contains(fixtureMode)
        );
        switch (fixtureMode) {
            case "rate-limit-zero":
                data.addProperty("retry_after_seconds", 0);
                break;
            case "rate-limit-boundary":
                data.addProperty("retry_after_seconds", 86400);
                break;
            case "rate-limit-bool":
                data.addProperty("retry_after_seconds", true);
                break;
            case "rate-limit-negative":
                data.addProperty("retry_after_seconds", -1);
                break;
            case "rate-limit-too-large":
                data.addProperty("retry_after_seconds", 86401);
                break;
            case "rate-limit-fraction":
                data.addProperty("retry_after_seconds", 1.5);
                break;
            default:
                break;
        }

        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        error.add("data", data);

        JsonObject response = baseEnvelope(request);
        response.add("error", error);
        return response;
    }

    private static JsonObject successEnvelope(
            JsonObject request,
            JsonElement data
    ) {
        JsonObject result = new JsonObject();
        result.addProperty("protocol_version", 1);
        result.add("data", data);
        String fixtureMode = System.getProperty("locker.fixture.mode");
        boolean operationMismatch = "cli-version-mismatch".equals(
                fixtureMode
        ) && !"system.capabilities".equals(
                request.get("method").getAsString()
        );
        boolean capabilityMismatch =
                "capability-version-mismatch".equals(fixtureMode)
                        && "system.capabilities".equals(
                        request.get("method").getAsString()
                );
        String cliVersion = operationMismatch || capabilityMismatch
                ? "different-cli"
                : "fixture-cli";
        result.add("meta", object("cli_version", cliVersion));

        JsonObject response = baseEnvelope(request);
        response.add("result", result);
        return response;
    }

    private static void recordCapabilityNegotiation() {
        String countPath = System.getProperty(
                "locker.fixture.capabilityCountPath"
        );
        if (countPath == null || countPath.isBlank()) {
            return;
        }
        try {
            Files.writeString(
                    Path.of(countPath),
                    "1\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonObject baseEnvelope(JsonObject request) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if ("wrong-id".equals(
                System.getProperty("locker.fixture.mode")
        )) {
            response.addProperty("id", "wrong-request-id");
        } else {
            response.add("id", request.get("id").deepCopy());
        }
        return response;
    }

    private static JsonObject secret(String key, String value) {
        JsonObject secret = new JsonObject();
        secret.addProperty("object", "secret");
        secret.addProperty("id", "secret-id-" + key);
        secret.addProperty("creation_date", 1710000000);
        secret.addProperty("revision_date", 1710000001);
        secret.add("updated_date", null);
        secret.add("deleted_date", null);
        secret.add("last_use_date", null);
        secret.addProperty("project_id", 42);
        secret.addProperty("environment_id", "environment-id");
        secret.addProperty("environment_name", "production");
        secret.addProperty("key", key);
        secret.addProperty("value", value);
        secret.addProperty("description", "");
        return secret;
    }

    private static JsonObject environment(String name) {
        JsonObject environment = new JsonObject();
        environment.addProperty("object", "environment");
        environment.addProperty("id", "environment-id");
        environment.addProperty("name", name);
        environment.addProperty("external_url", "https://example.com");
        environment.addProperty("description", "");
        environment.addProperty("creation_date", 1710000000);
        environment.addProperty("revision_date", 1710000001);
        environment.add("updated_date", null);
        environment.addProperty("project_id", 42);
        return environment;
    }

    private static JsonObject object(String name, String value) {
        JsonObject object = new JsonObject();
        object.addProperty(name, value);
        return object;
    }

    private static JsonObject longObject(String name, long value) {
        JsonObject object = new JsonObject();
        object.addProperty(name, value);
        return object;
    }

    private static void require(
            JsonObject object,
            String field,
            String expected
    ) {
        if (object == null
                || !object.has(field)
                || !expected.equals(object.get(field).getAsString())) {
            throw new IllegalArgumentException("Invalid fixture request");
        }
    }

    private static void requireInteger(
            JsonObject object,
            String field,
            int expected
    ) {
        if (object == null
                || !object.has(field)
                || expected != object.get(field).getAsInt()) {
            throw new IllegalArgumentException("Invalid fixture request");
        }
    }
}
