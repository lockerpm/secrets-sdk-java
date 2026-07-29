package locker.net;

import locker.exception.ApiConnectionError;
import locker.exception.LockerError;
import locker.model.LockerObjectInterface;
import lombok.Getter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
public class RequestOptions {
    private final String accessKeyId;
    private final String secretAccessKey;
    private final String apiBase;
    private final Map<String, String> headers;


    public static RequestOptions getDefault() {
        return new RequestOptions(
                null,
                null,
                null,
                null

        );
    }


    public String getAccessKeyId() {
        return accessKeyId;
    }

    public RequestOptions(String accessKeyId, String secretAccessKey, String apiBase, Map<String, String> headers) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.apiBase = apiBase;
        this.headers = immutableHeaders(headers);
    }

    public static RequestOptionsBuilder builder() {
        return new RequestOptionsBuilder();
    }

    public static Boolean getIsJsonFromType(Type typeToken) throws LockerError {

        if (typeToken == null) {
            throw new ApiConnectionError("Response type must not be null");
        }

        Type rawType = typeToken;
        if (typeToken instanceof ParameterizedType) {
            rawType = ((ParameterizedType) typeToken).getRawType();
        }
        if (!(rawType instanceof Class<?>)) {
            throw new ApiConnectionError("Unsupported response type");
        }

        Class<?> responseClass = (Class<?>) rawType;
        if (responseClass.equals(String.class)) {
            return false;
        } else if (LockerObjectInterface.class.isAssignableFrom(responseClass)) {
            return true;
        } else if (List.class.isAssignableFrom(responseClass)) {
            return true;
        } else {

            throw new ApiConnectionError("Not support type");
        }

    }

    @Getter
    public static final class RequestOptionsBuilder {
        private String accessKeyId;
        private String secretAccessKey;
        private String apiBase;
        private Map<String, String> headers;

        /**
         * Constructs a request options builder with the global parameters (API key and client ID) as
         * default values.
         */
        public RequestOptionsBuilder() {
        }


        public RequestOptionsBuilder setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
            return this;
        }

        public RequestOptionsBuilder setSecretAccessKey(String secretAccessKey) {
            this.secretAccessKey = secretAccessKey;
            return this;
        }

        public RequestOptionsBuilder setApiBase(String apiBase) {
            this.apiBase = apiBase;
            return this;
        }


        public RequestOptionsBuilder setHeaders(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        /**
         * Constructs a {@link RequestOptions} with the specified values.
         */
        public RequestOptions build() {
            return new RequestOptions(
                    this.accessKeyId,
                    this.secretAccessKey,
                    this.apiBase,
                    this.headers
            );
        }
    }


    /**
     * GET request option from global or per-request options
     */
    static RequestOptions merge(LockerResponseGetterOptions clientOptions, RequestOptions options) {
        String clientAccessKeyId = clientOptions == null ? null : clientOptions.getAccessKeyId();
        String clientSecretAccessKey = clientOptions == null ? null : clientOptions.getSecretAccessKey();
        String clientApiBase = clientOptions == null ? null : clientOptions.getApiBase();
        Map<String, String> clientHeaders = clientOptions == null ? null : clientOptions.getHeaders();

        if (options == null) {
            return new RequestOptions(
                    clientAccessKeyId,
                    clientSecretAccessKey,
                    clientApiBase,
                    clientHeaders
            );
        }
        return new RequestOptions(
                options.getAccessKeyId() != null ? options.getAccessKeyId() : clientAccessKeyId,
                options.getSecretAccessKey() != null ? options.getSecretAccessKey() : clientSecretAccessKey,
                options.getApiBase() != null ? options.getApiBase() : clientApiBase,
                options.getHeaders() != null ? options.getHeaders() : clientHeaders
        );
    }

    private static Map<String, String> immutableHeaders(
            Map<String, String> headers
    ) {
        if (headers == null) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

}
