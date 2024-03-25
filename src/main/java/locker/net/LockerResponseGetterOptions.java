package locker.net;

import java.util.Map;

public abstract class LockerResponseGetterOptions {
    // When adding settings here keep them in sync with settings in RequestOptions and
    // in the RequestOptions.merge method
    public abstract String getAccessKeyId();

    public abstract String getSecretAccessKey();

    public abstract String getApiBase();


    public abstract Map<String, String> getHeaders();

}
