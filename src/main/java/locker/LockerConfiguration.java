package locker;

/**
 * Runtime configuration shared by the Locker Java SDK.
 *
 * <p>The SDK deliberately does not download a CLI binary during class loading
 * or client construction. Managed installation is an explicit lifecycle
 * operation backed by update-channel v2 metadata signed by the release key
 * embedded in the SDK artifact. CI never rewrites that committed trust root;
 * release validation compares it with an independently protected key.
 */
public final class LockerConfiguration {
    public static final String SDK_VERSION = "1.0.0";
    public static final String CLI_PATH_ENVIRONMENT_VARIABLE =
            "LOCKER_CLI_PATH";

    private static final LockerConfiguration INSTANCE =
            new LockerConfiguration();

    private final String binaryFilePath;

    private LockerConfiguration() {
        binaryFilePath = configuredValue(
                System.getenv(CLI_PATH_ENVIRONMENT_VARIABLE)
        );
    }

    public static LockerConfiguration getInstance() {
        return INSTANCE;
    }

    public String getSdkVersion() {
        return SDK_VERSION;
    }

    /**
     * Returns the caller-configured CLI path, or {@code null} when no trusted
     * CLI distribution has been configured.
     */
    public String getBinaryFilePath() {
        return binaryFilePath;
    }

    private static String configuredValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
