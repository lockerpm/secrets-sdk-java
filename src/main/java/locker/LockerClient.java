package locker;

import locker.net.LiveLockerResponseGetter;
import locker.net.LockerResponseGetter;
import locker.net.LockerResponseGetterOptions;
import lombok.Getter;

import java.util.Map;


public class LockerClient {
    private final LockerResponseGetter responseGetter;

    /**
     * constructs a LockerClient with default settings, using provided access key id, secret access key
     */
    public LockerClient() {
        this(null, null, null);

    }

    public LockerClient(String apiBase) {
        this(null, null, apiBase);
    }

    public LockerClient(String accessKeyId, String secretAccessKey) {
        this(accessKeyId, secretAccessKey, null);
    }

    public LockerClient(String accessKeyId, String secretAccessKey, String apiBase) {
        this.responseGetter = new LiveLockerResponseGetter(
                builder().setAccessKeyId(accessKeyId).setSecretAccessKey(secretAccessKey).setApiBase(apiBase)
                        .buildOptions()
        );
    }

    public LockerClient(LockerResponseGetter responseGetter) {
        this.responseGetter = responseGetter;
    }

    public LockerClient(String accessKeyId, String secretAccessKey, String apiBase, Map<String, String> headers) {
        this.responseGetter = new LiveLockerResponseGetter(
                builder().setAccessKeyId(accessKeyId).setSecretAccessKey(secretAccessKey).setApiBase(apiBase)
                        .setHeaders(headers)
                        .buildOptions()
        );
    }

    protected LockerResponseGetter getResponseGetter() {
        return responseGetter;
    }

    public locker.service.SecretService secrets() {
        return new locker.service.SecretService(this.getResponseGetter());
    }

    public locker.service.EnvironmentService environments() {
        return new locker.service.EnvironmentService(this.getResponseGetter());
    }

    public static LockerClientBuilder builder() {
        return new LockerClientBuilder();
    }

    static class ClientLockerResponseGetterOptions extends LockerResponseGetterOptions {
        // When adding setting here keep them in sync with settings in RequestOptions and
        // in the RequestOptions.merge method
        private final String accessKeyId;
        private final String secretAccessKey;
        private final String apiBase;
        private final Map<String, String> headers;

        ClientLockerResponseGetterOptions(String accessKeyId, String secretAccessKey) {
            this(accessKeyId, secretAccessKey, null, null);

        }

        ClientLockerResponseGetterOptions(String accessKeyId, String secretAccessKey, String apiBase) {
            this(accessKeyId, secretAccessKey, apiBase, null);

        }

        public ClientLockerResponseGetterOptions(String accessKeyId, String secretAccessKey, String apiBase, Map<String, String> headers) {
            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
            this.apiBase = apiBase;
            this.headers = headers;
        }

        @Override
        public String getAccessKeyId() {
            return this.accessKeyId;
        }

        @Override
        public String getSecretAccessKey() {
            return this.secretAccessKey;
        }

        @Override
        public String getApiBase() {
            return this.apiBase;
        }

        @Override
        public Map<String, String> getHeaders() {
            return this.headers;
        }
    }


    public static final class LockerClientBuilder {
        @Getter
        private String accessKeyId;
        @Getter
        private String secretAccessKey;
        @Getter
        String apiBase;
        @Getter
        private Map<String, String> headers;

        public LockerClientBuilder(String accessKeyId, String secretAccessKey) {

            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
        }

        public LockerClientBuilder(String accessKeyId, String secretAccessKey, String apiBase) {
            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
            this.apiBase = apiBase;
        }

        public LockerClientBuilder(String accessKeyId, String secretAccessKey, String apiBase, Map<String, String> headers) {
            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
            this.apiBase = apiBase;
            this.headers = headers;
        }

        public LockerClientBuilder setAccessKeyId(String accessKeyId) {
            this.accessKeyId = accessKeyId;
            return this;
        }

        public LockerClientBuilder setSecretAccessKey(String secretAccessKey) {
            this.secretAccessKey = secretAccessKey;
            return this;
        }

        public LockerClientBuilder setApiBase(String apiBase) {
            this.apiBase = apiBase;
            return this;
        }

        public LockerClientBuilder setHeaders(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public LockerClientBuilder() {

        }

        public LockerResponseGetterOptions buildOptions() {
            return new ClientLockerResponseGetterOptions(
                    this.accessKeyId,
                    this.secretAccessKey,
                    this.apiBase,
                    this.headers
            );
        }
    }
}
