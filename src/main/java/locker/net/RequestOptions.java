package locker.net;

import locker.exception.ApiConnectionError;
import locker.exception.LockerError;
import locker.model.LockerObjectInterface;
import lombok.Getter;

import java.lang.reflect.Type;
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
        this.headers = headers;
    }

    public static RequestOptionsBuilder builder() {
        return new RequestOptionsBuilder();
    }

    public static Boolean getIsJsonFromType(Type typeToken) throws LockerError {

        if (typeToken.equals(String.class)) {
            return false;
        } else if (LockerObjectInterface.class.isAssignableFrom((Class<?>) typeToken)) {
            return true;
        } else if (List.class.isAssignableFrom((Class<?>) typeToken)) {
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

        if (options == null) {
            return new RequestOptions(
                    clientOptions.getAccessKeyId(),
                    clientOptions.getSecretAccessKey(),
                    clientOptions.getApiBase(),
                    clientOptions.getHeaders()
            );
        }
        return new RequestOptions(
                options.getAccessKeyId() != null ? options.getAccessKeyId() : clientOptions.getAccessKeyId(),
                options.getSecretAccessKey() != null ? options.getSecretAccessKey() : clientOptions.getSecretAccessKey(),
                options.getApiBase() != null ? options.getApiBase() : clientOptions.getApiBase(),
                options.getHeaders() != null ? options.getHeaders() : clientOptions.getHeaders()
        );
    }

}
