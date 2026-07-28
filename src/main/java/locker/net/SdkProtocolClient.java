package locker.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import locker.exception.ApiConnectionError;
import locker.exception.ApiError;
import locker.exception.ApiServerError;
import locker.exception.AuthenticationError;
import locker.exception.CliRunError;
import locker.exception.LockerError;
import locker.exception.PermissionDeniedError;
import locker.exception.RateLimitError;
import locker.exception.ResourceNotFoundError;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class SdkProtocolClient {
    private static final int CODE_PARSE = -32700;
    private static final int CODE_INVALID_REQUEST = -32600;
    private static final int CODE_METHOD_NOT_FOUND = -32601;
    private static final int CODE_INVALID_PARAMS = -32602;
    private static final int CODE_INTERNAL = -32603;
    private static final int CODE_AUTHENTICATION = -32001;
    private static final int CODE_PERMISSION = -32003;
    private static final int CODE_NOT_FOUND = -32004;
    private static final int CODE_RATE_LIMITED = -32029;
    private static final int CODE_NETWORK = -32050;
    private static final int CODE_SERVER = -32051;
    private static final int CODE_STORAGE = -32060;
    private static final String TRANSPORT = "json-rpc-2.0-stdio";
    private static final int MAX_JSON_DEPTH = 256;
    private static final Set<String> REQUIRED_METHODS =
            Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
                    "environment.create",
                    "environment.get",
                    "environment.list",
                    "environment.update",
                    "secret.create",
                    "secret.get",
                    "secret.list",
                    "secret.update",
                    "system.capabilities"
            )));

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private final ProtocolExecutor protocolExecutor;
    private final SdkProtocolRequestFactory requestFactory;
    private final BinaryIdentityProvider binaryIdentityProvider;
    private volatile NegotiatedState negotiatedState;

    SdkProtocolClient(CliProcessRunner processRunner) {
        this(
                processRunner::execute,
                processRunner::executableIdentity
        );
    }

    SdkProtocolClient(
            CliProcessRunner processRunner,
            BinaryIdentityProvider binaryIdentityProvider
    ) {
        this(processRunner::execute, binaryIdentityProvider);
    }

    SdkProtocolClient(
            ProtocolExecutor protocolExecutor,
            BinaryIdentityProvider binaryIdentityProvider
    ) {
        this.protocolExecutor = protocolExecutor;
        this.requestFactory = new SdkProtocolRequestFactory();
        this.binaryIdentityProvider = binaryIdentityProvider;
    }

    Payload execute(
            CliRequest request,
            RequestOptions options
    ) throws LockerError {
        SdkProtocolRequestFactory.Operation operation =
                requestFactory.operation(request);
        NegotiatedState state = ensureCapabilities(
                operation.getMethod()
        );
        String operationIdentity = currentIdentity();
        if (!operationIdentity.equals(state.identity)) {
            invalidate(state);
            state = ensureCapabilities(operation.getMethod());
            operationIdentity = currentIdentity();
            if (!operationIdentity.equals(state.identity)) {
                invalidate(state);
                throw new ApiConnectionError(
                        "Locker CLI executable changed before the "
                                + "protocol exchange"
                );
            }
        }
        JsonObject params = requestFactory.addContext(operation, options);
        Capabilities current = state.capabilities;
        Payload response = exchange(
                operation.getMethod(),
                params,
                current.maxRequestBytes,
                current.maxResponseBytes,
                current.maxJsonDepth
        );
        if (!current.cliVersion.equals(response.cliVersion)) {
            throw new ApiConnectionError(
                    "Locker CLI response version differs from "
                            + "negotiated capabilities"
            );
        }
        if (!currentIdentity().equals(operationIdentity)) {
            invalidate(state);
            throw new ApiConnectionError(
                    "Locker CLI executable changed during the protocol exchange"
            );
        }
        return response;
    }

    private NegotiatedState ensureCapabilities(
            String method
    ) throws LockerError {
        String observedIdentity = currentIdentity();
        NegotiatedState state = negotiatedState;
        if (state == null || !observedIdentity.equals(state.identity)) {
            synchronized (this) {
                state = negotiatedState;
                observedIdentity = currentIdentity();
                if (state == null
                        || !observedIdentity.equals(state.identity)) {
                    Capabilities capabilities = negotiateCapabilities();
                    String identityAfter = currentIdentity();
                    if (!observedIdentity.equals(identityAfter)) {
                        throw new ApiConnectionError(
                                "Locker CLI executable changed during "
                                        + "capability negotiation"
                        );
                    }
                    state = new NegotiatedState(
                            capabilities,
                            identityAfter
                    );
                    negotiatedState = state;
                }
            }
        }
        if (!state.capabilities.methods.contains(method)) {
            throw new ApiConnectionError(
                    "Locker CLI does not support the requested SDK operation"
            );
        }
        return state;
    }

    private void invalidate(NegotiatedState expected) {
        synchronized (this) {
            if (negotiatedState == expected) {
                negotiatedState = null;
            }
        }
    }

    private String currentIdentity() throws ApiConnectionError {
        try {
            return binaryIdentityProvider.current();
        } catch (CliProcessException exception) {
            throw new ApiConnectionError(
                    "Locker CLI executable identity is unavailable"
            );
        }
    }

    private Capabilities negotiateCapabilities() throws LockerError {
        Payload response = exchange(
                "system.capabilities",
                new JsonObject()
        );
        JsonObject data = requireObject(
                response.data,
                "Invalid Locker CLI capabilities response"
        );
        JsonObject protocol = requiredObject(
                data,
                "protocol",
                "Invalid Locker CLI capabilities response"
        );
        String name = requiredString(
                protocol,
                "name",
                "Invalid Locker CLI capabilities response"
        );
        String transport = requiredString(
                protocol,
                "transport",
                "Invalid Locker CLI capabilities response"
        );
        int minVersion = requiredInteger(
                protocol,
                "min_version",
                "Invalid Locker CLI capabilities response"
        );
        int maxVersion = requiredInteger(
                protocol,
                "max_version",
                "Invalid Locker CLI capabilities response"
        );
        if (!SdkProtocolRequestFactory.PROTOCOL_NAME.equals(name)
                || !TRANSPORT.equals(transport)
                || minVersion <= 0
                || maxVersion < minVersion
                || minVersion > SdkProtocolRequestFactory.PROTOCOL_VERSION
                || maxVersion < SdkProtocolRequestFactory.PROTOCOL_VERSION) {
            throw new ApiConnectionError(
                    "Locker CLI is incompatible with SDK protocol v1"
            );
        }

        JsonElement methodsElement = data.get("methods");
        if (methodsElement == null || !methodsElement.isJsonArray()) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI capabilities response"
            );
        }
        Set<String> methods = new HashSet<>();
        JsonArray methodsArray = methodsElement.getAsJsonArray();
        for (JsonElement method : methodsArray) {
            if (!method.isJsonPrimitive()
                    || !method.getAsJsonPrimitive().isString()) {
                throw new ApiConnectionError(
                        "Invalid Locker CLI capabilities response"
                );
            }
            String methodName = method.getAsString();
            if (methodName.isBlank() || !methods.add(methodName)) {
                throw new ApiConnectionError(
                        "Invalid Locker CLI capabilities response"
                );
            }
        }
        JsonObject cli = requiredObject(
                data,
                "cli",
                "Invalid Locker CLI capabilities response"
        );
        String cliVersion = requiredString(
                cli,
                "version",
                "Invalid Locker CLI capabilities response"
        );
        if (cliVersion.isBlank()) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI capabilities response"
            );
        }
        if (!cliVersion.equals(response.cliVersion)) {
            throw new ApiConnectionError(
                    "Locker CLI capability version differs from "
                            + "response metadata"
            );
        }
        JsonObject limits = requiredObject(
                data,
                "limits",
                "Invalid Locker CLI capabilities response"
        );
        long maxRequestBytes = requiredLong(
                limits,
                "max_request_bytes",
                "Invalid Locker CLI capabilities response"
        );
        long maxResponseBytes = requiredLong(
                limits,
                "max_response_bytes",
                "Invalid Locker CLI capabilities response"
        );
        long maxJsonDepth = MAX_JSON_DEPTH;
        if (limits.has("max_json_depth")) {
            maxJsonDepth = requiredLong(
                    limits,
                    "max_json_depth",
                    "Invalid Locker CLI capabilities response"
            );
            if (maxJsonDepth <= 0) {
                throw new ApiConnectionError(
                        "Invalid Locker CLI capabilities response"
                );
            }
        }
        if (maxRequestBytes <= 0
                || maxResponseBytes <= 0
                || !methods.containsAll(REQUIRED_METHODS)) {
            throw new ApiConnectionError(
                    "Invalid Locker CLI capabilities response"
            );
        }
        return new Capabilities(
                Collections.unmodifiableSet(methods),
                (int) Math.min(
                        maxRequestBytes,
                        CliProcessRunner.MAX_REQUEST_BYTES
                ),
                (int) Math.min(
                        maxResponseBytes,
                        CliProcessRunner.DEFAULT_MAX_STDOUT_BYTES
                ),
                (int) Math.min(maxJsonDepth, MAX_JSON_DEPTH),
                cliVersion
        );
    }

    private Payload exchange(
            String method,
            JsonObject params
    ) throws LockerError {
        return exchange(
                method,
                params,
                CliProcessRunner.MAX_REQUEST_BYTES,
                CliProcessRunner.DEFAULT_MAX_STDOUT_BYTES,
                MAX_JSON_DEPTH
        );
    }

    private Payload exchange(
            String method,
            JsonObject params,
            int maxRequestBytes,
            int maxResponseBytes,
            int maxJsonDepth
    ) throws LockerError {
        String requestId = "java-" + UUID.randomUUID();
        JsonObject request = new JsonObject();
        request.addProperty(
                "jsonrpc",
                SdkProtocolRequestFactory.JSON_RPC_VERSION
        );
        request.addProperty("id", requestId);
        request.addProperty("method", method);
        request.add("params", params);

        if (jsonDepth(request, 0) > maxJsonDepth) {
            throw new CliRunError(
                    "Locker SDK protocol request exceeds the CLI JSON "
                            + "nesting limit"
            );
        }
        byte[] requestBytes = GSON.toJson(request)
                .getBytes(StandardCharsets.UTF_8);
        if (requestBytes.length > maxRequestBytes) {
            Arrays.fill(requestBytes, (byte) 0);
            throw new CliRunError(
                    "Locker SDK protocol request exceeds the CLI request limit"
            );
        }
        CliProcessRunner.Result processResult;
        try {
            processResult = protocolExecutor.execute(
                    requestBytes,
                    maxResponseBytes
            );
        } catch (CliProcessException exception) {
            throw new CliRunError(exception.getMessage(), exception);
        } finally {
            Arrays.fill(requestBytes, (byte) 0);
        }
        if (processResult.getExitCode() != 0) {
            processResult.clear();
            throw new CliRunError(
                    "Locker CLI could not complete the SDK protocol exchange"
            );
        }

        byte[] responseBytes = processResult.getStdout();
        JsonObject response;
        try {
            response = parseResponse(responseBytes, maxJsonDepth);
        } finally {
            Arrays.fill(responseBytes, (byte) 0);
            processResult.clear();
        }
        validateEnvelope(response, requestId);
        boolean hasResult = response.has("result");
        boolean hasError = response.has("error");
        if (hasResult == hasError) {
            throw new CliRunError("Invalid Locker CLI protocol response");
        }
        if (hasError) {
            throw protocolError(response.get("error"), requestId);
        }
        return parseResult(response.get("result"));
    }

    private static JsonObject parseResponse(
            byte[] bytes,
            int maxJsonDepth
    ) throws CliRunError {
        String decoded;
        try {
            decoded = decodeUtf8(bytes);
            requireValidEscapedUnicode(decoded);
        } catch (IOException exception) {
            throw new CliRunError(
                    "Invalid Locker CLI protocol response",
                    exception
            );
        }
        try (
                JsonReader reader = new JsonReader(
                        new StringReader(decoded)
                )
        ) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement response = readJsonElement(
                    reader,
                    0,
                    maxJsonDepth
            );
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException(
                        "Trailing Locker CLI protocol data"
                );
            }
            return requireObject(
                    response,
                    "Invalid Locker CLI protocol response"
            );
        } catch (IOException
                 | IllegalStateException
                 | NumberFormatException exception) {
            throw new CliRunError(
                    "Invalid Locker CLI protocol response",
                    exception
            );
        } catch (ApiConnectionError exception) {
            throw new CliRunError(
                    "Invalid Locker CLI protocol response",
                    exception
            );
        }
    }

    private static void requireValidEscapedUnicode(
            String json
    ) throws IOException {
        boolean inString = false;
        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);
            if (!inString) {
                if (current == '"') {
                    inString = true;
                }
                continue;
            }
            if (current == '"') {
                inString = false;
                continue;
            }
            if (current != '\\') {
                continue;
            }
            index++;
            if (index >= json.length()) {
                return;
            }
            if (json.charAt(index) != 'u') {
                continue;
            }
            int codeUnit = readEscapedCodeUnit(json, index);
            index += 4;
            if (Character.isLowSurrogate((char) codeUnit)) {
                throw new IOException(
                        "Unpaired Locker CLI protocol Unicode surrogate"
                );
            }
            if (!Character.isHighSurrogate((char) codeUnit)) {
                continue;
            }
            if (index + 6 >= json.length()
                    || json.charAt(index + 1) != '\\'
                    || json.charAt(index + 2) != 'u') {
                throw new IOException(
                        "Unpaired Locker CLI protocol Unicode surrogate"
                );
            }
            int low = readEscapedCodeUnit(json, index + 2);
            if (!Character.isLowSurrogate((char) low)) {
                throw new IOException(
                        "Unpaired Locker CLI protocol Unicode surrogate"
                );
            }
            index += 6;
        }
    }

    private static int readEscapedCodeUnit(
            String json,
            int uIndex
    ) throws IOException {
        if (uIndex + 4 >= json.length()) {
            throw new IOException(
                    "Incomplete Locker CLI protocol Unicode escape"
            );
        }
        int value = 0;
        for (int offset = 1; offset <= 4; offset++) {
            int digit = Character.digit(json.charAt(uIndex + offset), 16);
            if (digit < 0) {
                throw new IOException(
                        "Invalid Locker CLI protocol Unicode escape"
                );
            }
            value = (value << 4) + digit;
        }
        return value;
    }

    private static String decodeUtf8(byte[] bytes)
            throws CharacterCodingException {
        CharBuffer characters = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return characters.toString();
    }

    private static JsonElement readJsonElement(
            JsonReader reader,
            int depth,
            int maxDepth
    ) throws IOException {
        if (depth > maxDepth || depth > MAX_JSON_DEPTH) {
            throw new IOException(
                    "Locker CLI protocol JSON nesting limit exceeded"
            );
        }
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    requireWellFormedUnicode(name);
                    if (object.has(name)) {
                        throw new IOException(
                                "Duplicate Locker CLI protocol field"
                        );
                    }
                    object.add(
                            name,
                            readJsonElement(
                                    reader,
                                    depth + 1,
                                    maxDepth
                            )
                    );
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(readJsonElement(
                            reader,
                            depth + 1,
                            maxDepth
                    ));
                }
                reader.endArray();
                return array;
            case STRING:
                String value = reader.nextString();
                requireWellFormedUnicode(value);
                return new JsonPrimitive(value);
            case NUMBER:
                return new JsonPrimitive(
                        new BigDecimal(reader.nextString())
                );
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new IOException(
                        "Invalid Locker CLI protocol JSON token"
                );
        }
    }

    private static int jsonDepth(
            JsonElement element,
            int depth
    ) throws CliRunError {
        if (depth > MAX_JSON_DEPTH) {
            throw new CliRunError(
                    "Locker SDK protocol request exceeds the local JSON "
                            + "nesting limit"
            );
        }
        int maximum = depth;
        if (element.isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> entry
                    : element.getAsJsonObject().entrySet()) {
                maximum = Math.max(
                        maximum,
                        jsonDepth(entry.getValue(), depth + 1)
                );
            }
        } else if (element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) {
                maximum = Math.max(
                        maximum,
                        jsonDepth(value, depth + 1)
                );
            }
        }
        return maximum;
    }

    private static void requireWellFormedUnicode(
            String value
    ) throws IOException {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(
                        value.charAt(index + 1)
                )) {
                    throw new IOException(
                            "Invalid Locker CLI protocol Unicode string"
                    );
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IOException(
                        "Invalid Locker CLI protocol Unicode string"
                );
            }
        }
    }

    private static void validateEnvelope(
            JsonObject response,
            String requestId
    ) throws CliRunError {
        try {
            String jsonRpc = requiredString(
                    response,
                    "jsonrpc",
                    "Invalid Locker CLI protocol response"
            );
            String responseId = requiredString(
                    response,
                    "id",
                    "Invalid Locker CLI protocol response"
            );
            if (!SdkProtocolRequestFactory.JSON_RPC_VERSION.equals(jsonRpc)
                    || !requestId.equals(responseId)) {
                throw new CliRunError("Invalid Locker CLI protocol response");
            }
        } catch (ApiConnectionError exception) {
            throw new CliRunError(
                    "Invalid Locker CLI protocol response",
                    exception
            );
        }
    }

    private static Payload parseResult(
            JsonElement resultElement
    ) throws CliRunError {
        try {
            JsonObject result = requireObject(
                    resultElement,
                    "Invalid Locker CLI protocol result"
            );
            int protocolVersion = requiredInteger(
                    result,
                    "protocol_version",
                    "Invalid Locker CLI protocol result"
            );
            if (protocolVersion
                    != SdkProtocolRequestFactory.PROTOCOL_VERSION) {
                throw new ApiConnectionError(
                        "Locker CLI returned an incompatible protocol version"
                );
            }
            if (!result.has("data")) {
                throw new ApiConnectionError(
                        "Invalid Locker CLI protocol result"
                );
            }
            JsonObject meta = requiredObject(
                    result,
                    "meta",
                    "Invalid Locker CLI protocol result"
            );
            String cliVersion = requiredString(
                    meta,
                    "cli_version",
                    "Invalid Locker CLI protocol result"
            );
            if (cliVersion.isBlank()) {
                throw new ApiConnectionError(
                        "Invalid Locker CLI protocol result"
                );
            }
            return new Payload(result.get("data").deepCopy(), cliVersion);
        } catch (ApiConnectionError exception) {
            throw new CliRunError(
                    exception.getMessage(),
                    exception
            );
        }
    }

    private static LockerError protocolError(
            JsonElement errorElement,
            String requestId
    ) throws CliRunError {
        try {
            JsonObject error = requireObject(
                    errorElement,
                    "Invalid Locker CLI protocol error"
            );
            int code = requiredInteger(
                    error,
                    "code",
                    "Invalid Locker CLI protocol error"
            );
            requiredString(
                    error,
                    "message",
                    "Invalid Locker CLI protocol error"
            );
            JsonObject data = requiredObject(
                    error,
                    "data",
                    "Invalid Locker CLI protocol error"
            );
            int protocolVersion = requiredInteger(
                    data,
                    "protocol_version",
                    "Invalid Locker CLI protocol error"
            );
            String kind = requiredString(
                    data,
                    "kind",
                    "Invalid Locker CLI protocol error"
            );
            boolean retryable = requiredBoolean(
                    data,
                    "retryable",
                    "Invalid Locker CLI protocol error"
            );
            if (protocolVersion
                    != SdkProtocolRequestFactory.PROTOCOL_VERSION
                    || kind.isBlank()
                    || kind.length() > 256) {
                throw new ApiConnectionError(
                    "Invalid Locker CLI protocol error"
                );
            }
            String safeMessage = safeErrorMessage(code);

            switch (code) {
                case CODE_AUTHENTICATION:
                    return new AuthenticationError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            retryable
                    );
                case CODE_PERMISSION:
                    return new PermissionDeniedError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            retryable
                    );
                case CODE_NOT_FOUND:
                    return new ResourceNotFoundError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            retryable
                    );
                case CODE_RATE_LIMITED:
                    return new RateLimitError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            retryable
                    );
                case CODE_NETWORK:
                    return new ApiConnectionError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            retryable
                    );
                case CODE_SERVER:
                    return new ApiServerError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            retryable
                    );
                case CODE_STORAGE:
                    return new CliRunError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            retryable
                    );
                default:
                    return new ApiError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            retryable
                    );
            }
        } catch (ApiConnectionError exception) {
            throw new CliRunError(
                    "Invalid Locker CLI protocol error",
                    exception
            );
        }
    }

    private static String safeErrorMessage(int code) {
        switch (code) {
            case CODE_PARSE:
                return "Locker CLI could not parse the SDK protocol request";
            case CODE_INVALID_REQUEST:
                return "Locker CLI rejected the SDK protocol request";
            case CODE_METHOD_NOT_FOUND:
                return "Locker CLI does not support the SDK operation";
            case CODE_INVALID_PARAMS:
                return "Locker CLI rejected the SDK operation parameters";
            case CODE_INTERNAL:
                return "Locker CLI protocol failed";
            case CODE_AUTHENTICATION:
                return "Locker authentication failed";
            case CODE_PERMISSION:
                return "Locker permission was denied";
            case CODE_NOT_FOUND:
                return "Locker resource was not found";
            case CODE_RATE_LIMITED:
                return "Locker request was rate limited";
            case CODE_NETWORK:
                return "Locker network request failed";
            case CODE_SERVER:
                return "Locker server request failed";
            case CODE_STORAGE:
                return "Locker local storage operation failed";
            default:
                return "Locker operation failed";
        }
    }

    private static JsonObject requiredObject(
            JsonObject parent,
            String field,
            String error
    ) throws ApiConnectionError {
        JsonElement element = parent.get(field);
        return requireObject(element, error);
    }

    private static JsonObject requireObject(
            JsonElement element,
            String error
    ) throws ApiConnectionError {
        if (element == null || !element.isJsonObject()) {
            throw new ApiConnectionError(error);
        }
        return element.getAsJsonObject();
    }

    private static String requiredString(
            JsonObject object,
            String field,
            String error
    ) throws ApiConnectionError {
        JsonElement element = object.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new ApiConnectionError(error);
        }
        return element.getAsString();
    }

    private static int requiredInteger(
            JsonObject object,
            String field,
            String error
    ) throws ApiConnectionError {
        JsonElement element = object.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new ApiConnectionError(error);
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException exception) {
            throw new ApiConnectionError(error);
        }
    }

    private static long requiredLong(
            JsonObject object,
            String field,
            String error
    ) throws ApiConnectionError {
        JsonElement element = object.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new ApiConnectionError(error);
        }
        try {
            return element.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new ApiConnectionError(error);
        }
    }

    private static boolean requiredBoolean(
            JsonObject object,
            String field,
            String error
    ) throws ApiConnectionError {
        JsonElement element = object.get(field);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isBoolean()) {
            throw new ApiConnectionError(error);
        }
        return element.getAsBoolean();
    }

    static final class Payload {
        private final JsonElement data;
        private final String cliVersion;

        private Payload(JsonElement data, String cliVersion) {
            this.data = data;
            this.cliVersion = cliVersion;
        }

        JsonElement getData() {
            return data.deepCopy();
        }

        String getCliVersion() {
            return cliVersion;
        }
    }

    @FunctionalInterface
    interface BinaryIdentityProvider {
        String current() throws CliProcessException;
    }

    @FunctionalInterface
    interface ProtocolExecutor {
        CliProcessRunner.Result execute(
                byte[] request,
                int maxResponseBytes
        ) throws CliProcessException;
    }

    private static final class Capabilities {
        private final Set<String> methods;
        private final int maxRequestBytes;
        private final int maxResponseBytes;
        private final int maxJsonDepth;
        private final String cliVersion;

        private Capabilities(
                Set<String> methods,
                int maxRequestBytes,
                int maxResponseBytes,
                int maxJsonDepth,
                String cliVersion
        ) {
            this.methods = methods;
            this.maxRequestBytes = maxRequestBytes;
            this.maxResponseBytes = maxResponseBytes;
            this.maxJsonDepth = maxJsonDepth;
            this.cliVersion = cliVersion;
        }
    }

    private static final class NegotiatedState {
        private final Capabilities capabilities;
        private final String identity;

        private NegotiatedState(
                Capabilities capabilities,
                String identity
        ) {
            this.capabilities = capabilities;
            this.identity = identity;
        }
    }
}
