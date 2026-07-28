package locker.net;

import java.time.Duration;
import java.util.Map;

public abstract class LockerResponseGetterOptions {
    // When adding settings here keep them in sync with settings in RequestOptions and
    // in the RequestOptions.merge method
    public abstract String getAccessKeyId();

    public abstract String getSecretAccessKey();

    public abstract String getApiBase();


    public abstract Map<String, String> getHeaders();

    /**
     * Optional explicit Locker CLI path. Returning {@code null} falls back to
     * {@code LOCKER_CLI_PATH}, then the signed managed update channel.
     */
    public String getCliPath() {
        return null;
    }

    /**
     * Maximum duration for one CLI protocol exchange.
     */
    public Duration getCliTimeout() {
        return CliProcessRunner.DEFAULT_TIMEOUT;
    }

}
