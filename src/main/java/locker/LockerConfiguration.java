package locker;

import java.util.regex.Pattern;

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
    private static final Pattern STABLE_VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "\\.(0|[1-9][0-9]*)$"
    );

    /**
     * Version embedded into the built SDK artifact.
     *
     * <p>Packaged releases read this from the JAR manifest generated from
     * Maven's CI-friendly {@code revision}. Exploded Maven test classes use
     * the same project version through a system property. Source code never
     * contains a second manually maintained release version.
     */
    public static final String SDK_VERSION = resolveSdkVersion();
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

    private static String resolveSdkVersion() {
        Package runtimePackage = LockerConfiguration.class.getPackage();
        String artifactVersion = runtimePackage == null
                ? null
                : runtimePackage.getImplementationVersion();
        if (isStableVersion(artifactVersion)) {
            return artifactVersion;
        }

        String buildVersion = System.getProperty("locker.sdk.version");
        if (isStableVersion(buildVersion)) {
            return buildVersion;
        }
        throw new ExceptionInInitializerError(
                "Locker SDK artifact version metadata is missing or invalid"
        );
    }

    private static boolean isStableVersion(String value) {
        return value != null
                && value.equals(value.trim())
                && STABLE_VERSION.matcher(value).matches();
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
