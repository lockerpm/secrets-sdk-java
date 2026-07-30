package locker.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import locker.exception.AlreadyExistsError;
import locker.exception.ApiConnectionError;
import locker.exception.ApiError;
import locker.exception.ApiServerError;
import locker.exception.AuthenticationError;
import locker.exception.ConflictError;
import locker.exception.IntegrityError;
import locker.exception.LockerError;
import locker.exception.OperationCancelledError;
import locker.exception.PermissionDeniedError;
import locker.exception.ProtocolError;
import locker.exception.RateLimitError;
import locker.exception.RequestRejectedError;
import locker.exception.ResourceNotFoundError;
import locker.exception.ResponseTooLargeError;
import locker.exception.StorageError;
import locker.exception.ValidationError;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RpcErrorCatalogTest {
    private static final String CATALOG_RESOURCE =
            "/locker/protocol/locker-rpc-errors.v1.json";
    private static final String CATALOG_SHA256 =
            "bec020bea51d694371d738a9a44c1764"
                    + "4ea66728706d7027f6bc86988ee93c7d";

    @Test
    public void vendoredCatalogMatchesRuntimeErrorMapping() throws Exception {
        byte[] raw;
        try (InputStream input = getClass().getResourceAsStream(
                CATALOG_RESOURCE
        )) {
            assertNotNull(input, "RPC error catalog resource is missing");
            raw = input.readAllBytes();
        }
        assertEquals(
                CATALOG_SHA256,
                lowercaseHex(
                        MessageDigest.getInstance("SHA-256").digest(raw)
                )
        );

        JsonObject catalog = JsonParser.parseString(
                new String(raw, java.nio.charset.StandardCharsets.UTF_8)
        ).getAsJsonObject();
        JsonArray errors = catalog.getAsJsonArray("errors");
        Map<String, Class<? extends LockerError>> types =
                expectedTypes();
        for (JsonElement element : errors) {
            JsonObject catalogError = element.getAsJsonObject();
            int code = catalogError.get("rpc_code").getAsInt();
            String kind = catalogError.get("kind").getAsString();
            String expectedMessage =
                    catalogError.get("message").getAsString();
            boolean expectedRetryable =
                    catalogError.get("retryable").getAsBoolean();
            String sdkError = catalogError.get("sdk_error").getAsString();

            JsonObject data = new JsonObject();
            data.addProperty("protocol_version", 1);
            data.addProperty("kind", kind);
            data.addProperty("retryable", expectedRetryable);
            JsonObject wireError = new JsonObject();
            wireError.addProperty("code", code);
            wireError.addProperty(
                    "message",
                    "untrusted catalog fixture message"
            );
            wireError.add("data", data);

            LockerError mapped = SdkProtocolClient.protocolError(
                    wireError,
                    "request-catalog"
            );
            assertEquals(types.get(sdkError), mapped.getClass(), kind);
            assertEquals(expectedMessage, mapped.getUserMessage(), kind);
            assertEquals(expectedRetryable, mapped.getRetryable(), kind);
        }

        JsonObject policy = catalog.getAsJsonObject(
                "unknown_server_code_policy"
        );
        JsonObject data = new JsonObject();
        data.addProperty("protocol_version", 1);
        data.addProperty("kind", "future_error");
        data.addProperty(
                "retryable",
                policy.get("preserve_retryable").getAsBoolean()
        );
        JsonObject wireError = new JsonObject();
        wireError.addProperty(
                "code",
                policy.get("minimum").getAsInt()
        );
        wireError.addProperty("message", "untrusted future error");
        wireError.add("data", data);
        LockerError unknown = SdkProtocolClient.protocolError(
                wireError,
                "request-unknown"
        );
        assertEquals(ApiError.class, unknown.getClass());
        assertEquals(
                policy.get("message").getAsString(),
                unknown.getUserMessage()
        );
        assertEquals(
                policy.get("preserve_retryable").getAsBoolean(),
                unknown.getRetryable()
        );
    }

    @Test
    public void validatesAndSeparatesServerRequestId() throws Exception {
        JsonObject data = new JsonObject();
        data.addProperty("protocol_version", 1);
        data.addProperty("kind", "service_unavailable");
        data.addProperty("retryable", true);
        data.addProperty(
                "server_request_id",
                "upstream_Request-123456"
        );
        JsonObject wireError = new JsonObject();
        wireError.addProperty("code", -32051);
        wireError.addProperty("message", "unsafe server detail");
        wireError.add("data", data);

        LockerError mapped = SdkProtocolClient.protocolError(
                wireError,
                "json-rpc-request-id"
        );
        assertEquals("json-rpc-request-id", mapped.getRequestId());
        assertEquals(
                "upstream_Request-123456",
                mapped.getServerRequestId()
        );

        for (JsonElement invalid : new JsonElement[]{
                new com.google.gson.JsonPrimitive(true),
                new com.google.gson.JsonPrimitive("short"),
                new com.google.gson.JsonPrimitive(
                        "request.id.not.allowed"
                ),
                new com.google.gson.JsonPrimitive(
                        "a".repeat(129)
                )
        }) {
            data.add("server_request_id", invalid);
            assertThrows(
                    ProtocolError.class,
                    () -> SdkProtocolClient.protocolError(
                            wireError,
                            "json-rpc-request-id"
                    )
            );
        }
    }

    private static Map<String, Class<? extends LockerError>>
            expectedTypes() {
        Map<String, Class<? extends LockerError>> types =
                new HashMap<>();
        types.put("ProtocolError", ProtocolError.class);
        types.put("OperationError", ApiError.class);
        types.put(
                "OperationCancelledError",
                OperationCancelledError.class
        );
        types.put("RequestRejectedError", RequestRejectedError.class);
        types.put("ResponseTooLargeError", ResponseTooLargeError.class);
        types.put("ConflictError", ConflictError.class);
        types.put("AlreadyExistsError", AlreadyExistsError.class);
        types.put("ValidationError", ValidationError.class);
        types.put("AuthenticationError", AuthenticationError.class);
        types.put("PermissionDeniedError", PermissionDeniedError.class);
        types.put("NotFoundError", ResourceNotFoundError.class);
        types.put("RateLimitError", RateLimitError.class);
        types.put("NetworkError", ApiConnectionError.class);
        types.put("ServerError", ApiServerError.class);
        types.put("StorageError", StorageError.class);
        types.put("IntegrityError", IntegrityError.class);
        return types;
    }

    private static String lowercaseHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(
                    Character.forDigit((value >>> 4) & 0xf, 16)
            );
            result.append(Character.forDigit(value & 0xf, 16));
        }
        return result.toString();
    }
}
