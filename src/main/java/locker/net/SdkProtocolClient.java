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
import locker.exception.AlreadyExistsError;
import locker.exception.CliRunError;
import locker.exception.ConflictError;
import locker.exception.IntegrityError;
import locker.exception.LockerError;
import locker.exception.OperationCancelledError;
import locker.exception.PermissionDeniedError;
import locker.exception.ProtocolError;
import locker.exception.RateLimitError;
import locker.exception.ResourceNotFoundError;
import locker.exception.RequestRejectedError;
import locker.exception.ResponseTooLargeError;
import locker.exception.StorageError;
import locker.exception.ValidationError;

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
    private static final int CODE_OPERATION = -32000;
    private static final int CODE_AUTHENTICATION = -32001;
    private static final int CODE_PERMISSION = -32003;
    private static final int CODE_NOT_FOUND = -32004;
    private static final int CODE_CONFLICT = -32009;
    private static final int CODE_VALIDATION = -32022;
    private static final int CODE_RATE_LIMITED = -32029;
    private static final int CODE_NETWORK = -32050;
    private static final int CODE_SERVER = -32051;
    private static final int CODE_STORAGE = -32060;
    private static final int CODE_INTEGRITY = -32070;
    private static final String TRANSPORT = "json-rpc-2.0-stdio";
    private static final String TYPED_ERROR_CONTRACT = "typed-v1";
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
        SdkProtocolRequestFactory.Credentials credentials =
                requestFactory.credentials(options);
        return execute(request, options, credentials);
    }

    Payload execute(
            CliRequest request,
            RequestOptions options,
            SdkProtocolRequestFactory.Credentials credentials
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
        Capabilities current = state.capabilities;
        JsonObject params = requestFactory.addContext(
                operation,
                options,
                credentials,
                current.errorContracts.contains(TYPED_ERROR_CONTRACT)
        );
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
        Set<String> errorContracts = new HashSet<>();
        if (data.has("error_contracts")) {
            JsonElement contractsElement = data.get("error_contracts");
            if (contractsElement == null
                    || !contractsElement.isJsonArray()
                    || contractsElement.getAsJsonArray().size() > 8) {
                throw new ApiConnectionError(
                        "Invalid Locker CLI capabilities response"
                );
            }
            for (JsonElement contract
                    : contractsElement.getAsJsonArray()) {
                if (!contract.isJsonPrimitive()
                        || !contract.getAsJsonPrimitive().isString()) {
                    throw new ApiConnectionError(
                            "Invalid Locker CLI capabilities response"
                    );
                }
                String contractName = contract.getAsString();
                if (!isValidErrorContract(contractName)
                        || !errorContracts.add(contractName)) {
                    throw new ApiConnectionError(
                            "Invalid Locker CLI capabilities response"
                    );
                }
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
                Collections.unmodifiableSet(errorContracts),
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
            throw new ProtocolError(
                    "Invalid Locker CLI protocol response"
            );
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
            throw new ProtocolError(
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
            throw new ProtocolError(
                    "Invalid Locker CLI protocol response",
                    exception
            );
        } catch (ApiConnectionError exception) {
            throw new ProtocolError(
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
                throw new ProtocolError(
                        "Invalid Locker CLI protocol response"
                );
            }
        } catch (ApiConnectionError exception) {
            throw new ProtocolError(
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
            throw new ProtocolError(
                    exception.getMessage(),
                    exception
            );
        }
    }

    static LockerError protocolError(
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
            String message = requiredString(
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
            Integer retryAfterSeconds = null;
            if (data.has("retry_after_seconds")) {
                int value = requiredInteger(
                        data,
                        "retry_after_seconds",
                        "Invalid Locker CLI protocol error"
                );
                if (value < 0 || value > 86400) {
                    throw new ApiConnectionError(
                            "Invalid Locker CLI protocol error"
                    );
                }
                if (code == CODE_RATE_LIMITED
                        && "rate_limited".equals(kind)) {
                    retryAfterSeconds = value;
                }
            }
            String serverRequestId = null;
            if (data.has("server_request_id")) {
                serverRequestId = requiredString(
                        data,
                        "server_request_id",
                        "Invalid Locker CLI protocol error"
                );
                if (!isValidServerRequestId(serverRequestId)) {
                    throw new ApiConnectionError(
                            "Invalid Locker CLI protocol error"
                    );
                }
            }
            if (protocolVersion
                    != SdkProtocolRequestFactory.PROTOCOL_VERSION
                    || !isValidErrorKind(kind)
                    || !isValidErrorMessage(message)) {
                throw new ApiConnectionError(
                        "Invalid Locker CLI protocol error"
                );
            }
            if (!isStandardProtocolCode(code)
                    && !isLockerServerErrorCode(code)) {
                return new ProtocolError(
                        "unsupported JSON-RPC error code",
                        kind,
                        code,
                        requestId,
                        false,
                        serverRequestId
                );
            }
            boolean effectiveRetryable = retryable
                    && !isNormativelyNonRetryable(code, kind);
            String safeMessage = safeErrorMessage(code, kind);

            if ((code == CODE_CONFLICT || code == CODE_OPERATION)
                    && isAlreadyExistsKind(kind)) {
                return new AlreadyExistsError(
                        safeMessage,
                        kind,
                        code,
                        requestId,
                        effectiveRetryable,
                        serverRequestId
                );
            }
            if (code == CODE_CONFLICT
                    || (code == CODE_OPERATION
                    && "conflict".equals(kind))) {
                return new ConflictError(
                        safeMessage,
                        kind,
                        code,
                        requestId,
                        effectiveRetryable,
                        serverRequestId
                );
            }
            if (code == CODE_VALIDATION
                    || (code == CODE_OPERATION
                    && "validation_error".equals(kind))) {
                return new ValidationError(
                        safeMessage,
                        kind,
                        code,
                        requestId,
                        effectiveRetryable,
                        serverRequestId
                );
            }
            if (code == CODE_INTEGRITY
                    || (code == CODE_OPERATION
                    && isIntegrityKind(kind))) {
                return new IntegrityError(
                        safeMessage,
                        kind,
                        code,
                        requestId,
                        effectiveRetryable,
                        serverRequestId
                );
            }
            if (code == CODE_OPERATION
                    && "request_rejected".equals(kind)) {
                return new RequestRejectedError(
                        safeMessage,
                        kind,
                        code,
                        requestId,
                        false,
                        serverRequestId
                );
            }
            if (code == CODE_OPERATION
                    && "response_too_large".equals(kind)) {
                return new ResponseTooLargeError(
                        safeMessage,
                        kind,
                        code,
                        requestId,
                        false,
                        serverRequestId
                );
            }
            if (code == CODE_OPERATION
                    && "cancelled".equals(kind)) {
                return new OperationCancelledError(
                        safeMessage,
                        kind,
                        code,
                        requestId,
                        false,
                        serverRequestId
                );
            }
            switch (code) {
                case CODE_PARSE:
                case CODE_INVALID_REQUEST:
                case CODE_METHOD_NOT_FOUND:
                case CODE_INVALID_PARAMS:
                case CODE_INTERNAL:
                    return new ProtocolError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            false,
                            serverRequestId
                    );
                case CODE_AUTHENTICATION:
                    return new AuthenticationError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            effectiveRetryable,
                            serverRequestId
                    );
                case CODE_PERMISSION:
                    return new PermissionDeniedError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            effectiveRetryable,
                            serverRequestId
                    );
                case CODE_NOT_FOUND:
                    return new ResourceNotFoundError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            effectiveRetryable,
                            serverRequestId
                    );
                case CODE_RATE_LIMITED:
                    return new RateLimitError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            effectiveRetryable,
                            retryAfterSeconds,
                            serverRequestId
                    );
                case CODE_NETWORK:
                    return new ApiConnectionError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            effectiveRetryable,
                            serverRequestId
                    );
                case CODE_SERVER:
                    return new ApiServerError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            effectiveRetryable,
                            serverRequestId
                    );
                case CODE_STORAGE:
                    return new StorageError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            effectiveRetryable,
                            serverRequestId
                    );
                default:
                    return new ApiError(
                            safeMessage,
                            kind,
                            code,
                            requestId,
                            effectiveRetryable,
                            serverRequestId
                    );
            }
        } catch (ApiConnectionError exception) {
            throw new ProtocolError(
                    "Invalid Locker CLI protocol error",
                    exception
            );
        }
    }

    private static String safeErrorMessage(int code, String kind) {
        if (isAlreadyExistsKind(kind)
                && (code == CODE_CONFLICT || code == CODE_OPERATION)) {
            if ("secret_already_exists".equals(kind)) {
                return "a secret with this key already exists";
            }
            if ("environment_already_exists".equals(kind)) {
                return "an environment with this name already exists";
            }
            return "the requested resource already exists";
        }
        switch (code) {
            case CODE_PARSE:
                return "the Locker CLI returned invalid JSON";
            case CODE_INVALID_REQUEST:
                return "the Locker CLI rejected the request envelope";
            case CODE_METHOD_NOT_FOUND:
                return "the requested Locker operation is not supported";
            case CODE_INVALID_PARAMS:
                return "the Locker request parameters are invalid";
            case CODE_INTERNAL:
                return "the Locker CLI encountered an internal protocol error";
            case CODE_AUTHENTICATION:
                if ("missing_credentials".equals(kind)) {
                    return "access key ID and secret access key are required";
                }
                if ("invalid_access_key_id".equals(kind)) {
                    return "access key ID must be a UUIDv4";
                }
                if ("malformed_secret_access_key".equals(kind)) {
                    return "secret access key must be non-empty canonical base64";
                }
                if ("invalid_secret_access_key".equals(kind)) {
                    return "the secret access key does not match the access key ID";
                }
                return "authentication failed";
            case CODE_PERMISSION:
                return "you do not have permission to perform this operation";
            case CODE_NOT_FOUND:
                if ("secret_not_found".equals(kind)) {
                    return "the requested secret was not found";
                }
                if ("environment_not_found".equals(kind)) {
                    return "the requested environment was not found";
                }
                return "the requested resource was not found";
            case CODE_CONFLICT:
                return "the operation conflicts with current state";
            case CODE_VALIDATION:
                return "the request is invalid";
            case CODE_RATE_LIMITED:
                return "too many requests; retry later";
            case CODE_NETWORK:
                return "network_timeout".equals(kind)
                        ? "network request timed out"
                        : "network request failed";
            case CODE_SERVER:
                if ("internal_error".equals(kind)) {
                    return "the request could not be completed";
                }
                return "the service is temporarily unavailable";
            case CODE_STORAGE:
                return "local storage operation failed";
            case CODE_INTEGRITY:
                return integrityMessage(kind);
            default:
                if (code != CODE_OPERATION) {
                    return "the Locker operation failed";
                }
                if ("conflict".equals(kind)) {
                    return "the operation conflicts with current state";
                }
                if ("validation_error".equals(kind)) {
                    return "the request is invalid";
                }
                if (isIntegrityKind(kind)) {
                    return integrityMessage(kind);
                }
                if ("request_rejected".equals(kind)) {
                    return "the request is invalid";
                }
                if ("response_too_large".equals(kind)) {
                    return "protocol response exceeds the size limit";
                }
                if ("cancelled".equals(kind)) {
                    return "request cancelled";
                }
                return "the Locker operation failed";
        }
    }

    private static boolean isAlreadyExistsKind(String kind) {
        return "already_exists".equals(kind)
                || "secret_already_exists".equals(kind)
                || "environment_already_exists".equals(kind)
                || "duplicate_hash".equals(kind);
    }

    private static boolean isIntegrityKind(String kind) {
        return "integrity_error".equals(kind)
                || "transport_integrity_error".equals(kind)
                || "data_integrity_error".equals(kind)
                || "data_error".equals(kind);
    }

    private static String integrityMessage(String kind) {
        switch (kind) {
            case "integrity_error":
                return "stored data failed an integrity check";
            case "transport_integrity_error":
                return "transport integrity verification failed";
            case "data_integrity_error":
            case "data_error":
                return "data integrity verification failed";
            default:
                return "data integrity verification failed";
        }
    }

    private static boolean isNormativelyNonRetryable(
            int code,
            String kind
    ) {
        return isStandardProtocolCode(code)
                || code == CODE_AUTHENTICATION
                || code == CODE_PERMISSION
                || code == CODE_NOT_FOUND
                || code == CODE_OPERATION
                || code == CODE_CONFLICT
                || code == CODE_VALIDATION
                || code == CODE_STORAGE
                || code == CODE_INTEGRITY
                || (code == CODE_SERVER
                && "internal_error".equals(kind));
    }

    private static boolean isStandardProtocolCode(int code) {
        return code == CODE_PARSE
                || code == CODE_INVALID_REQUEST
                || code == CODE_METHOD_NOT_FOUND
                || code == CODE_INVALID_PARAMS
                || code == CODE_INTERNAL;
    }

    private static boolean isLockerServerErrorCode(int code) {
        return code >= -32099 && code <= -32000;
    }

    private static boolean isValidErrorKind(String kind) {
        if (kind.length() < 1 || kind.length() > 64
                || kind.charAt(0) < 'a' || kind.charAt(0) > 'z') {
            return false;
        }
        for (int index = 1; index < kind.length(); index++) {
            char value = kind.charAt(index);
            if ((value < 'a' || value > 'z')
                    && (value < '0' || value > '9')
                    && value != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidErrorContract(String contract) {
        if (contract.length() < 1 || contract.length() > 32
                || contract.charAt(0) < 'a'
                || contract.charAt(0) > 'z') {
            return false;
        }
        for (int index = 1; index < contract.length(); index++) {
            char value = contract.charAt(index);
            if ((value < 'a' || value > 'z')
                    && (value < '0' || value > '9')
                    && value != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidErrorMessage(String message) {
        if (message.isEmpty()
                || message.codePointCount(0, message.length()) > 512) {
            return false;
        }
        return message.codePoints().noneMatch(
                value -> value <= 0x1f
                        || (value >= 0x7f && value <= 0x9f)
        );
    }

    private static boolean isValidServerRequestId(String requestId) {
        if (requestId.length() < 16 || requestId.length() > 128) {
            return false;
        }
        for (int index = 0; index < requestId.length(); index++) {
            char value = requestId.charAt(index);
            if ((value < 'A' || value > 'Z')
                    && (value < 'a' || value > 'z')
                    && (value < '0' || value > '9')
                    && value != '_'
                    && value != '-') {
                return false;
            }
        }
        return true;
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
        private final Set<String> errorContracts;
        private final int maxRequestBytes;
        private final int maxResponseBytes;
        private final int maxJsonDepth;
        private final String cliVersion;

        private Capabilities(
                Set<String> methods,
                Set<String> errorContracts,
                int maxRequestBytes,
                int maxResponseBytes,
                int maxJsonDepth,
                String cliVersion
        ) {
            this.methods = methods;
            this.errorContracts = errorContracts;
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
