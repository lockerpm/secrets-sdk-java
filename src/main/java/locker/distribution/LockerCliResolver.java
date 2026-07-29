package locker.distribution;

import locker.LockerConfiguration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Resolves caller-owned explicit binaries or the signed managed CLI.
 */
public final class LockerCliResolver {
    private LockerCliResolver() {
    }

    /**
     * Resolves an explicit path, then {@code LOCKER_CLI_PATH}. If neither is
     * configured, it verifies and periodically refreshes the managed CLI.
     * Explicit caller-owned binaries bypass every managed update check.
     */
    public static String resolve(String explicitPath)
            throws CliDistributionException {
        return resolve(
                explicitPath,
                System.getenv(
                        LockerConfiguration
                                .CLI_PATH_ENVIRONMENT_VARIABLE
                ),
                null
        );
    }

    /**
     * Resolves the current verified managed executable. Managed releases are
     * immutable generations, so the returned path can change after an update.
     */
    public static Path canonicalManagedPath()
            throws CliDistributionException {
        return ManagedInstallerHolder.INSTANCE.resolve();
    }

    static String resolve(
            String explicitPath,
            String environmentPath,
            LockerCliInstaller installer
    ) throws CliDistributionException {
        String explicit = configuredValue(explicitPath);
        if (explicit != null) {
            return validateCallerPath(explicit, "configured CLI path");
        }
        String environment = configuredValue(environmentPath);
        if (environment != null) {
            return validateCallerPath(
                    environment,
                    LockerConfiguration.CLI_PATH_ENVIRONMENT_VARIABLE
            );
        }
        LockerCliInstaller updater = installer == null
                ? ManagedInstallerHolder.INSTANCE
                : installer;
        return updater.resolve().toString();
    }

    private static String validateCallerPath(
            String value,
            String source
    ) throws CliDistributionException {
        if (value.indexOf('\0') >= 0) {
            throw new CliDistributionException(
                    "The " + source + " is invalid"
            );
        }
        final Path candidate;
        try {
            candidate = Paths.get(value);
        } catch (InvalidPathException exception) {
            throw new CliDistributionException(
                    "The " + source + " is invalid",
                    exception
            );
        }
        if (!candidate.isAbsolute()) {
            throw new CliDistributionException(
                    "The " + source
                            + " must be an absolute executable path"
            );
        }
        Path absolute = candidate.normalize();
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    absolute,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (!attributes.isRegularFile()
                    || attributes.isSymbolicLink()
                    || !Files.isExecutable(absolute)) {
                throw new CliDistributionException(
                        "The " + source
                                + " does not identify an executable "
                                + "regular file"
                );
            }
            return absolute.toRealPath().toString();
        } catch (IOException exception) {
            throw new CliDistributionException(
                    "The " + source
                            + " does not identify an executable regular file",
                    exception
            );
        }
    }

    private static String configuredValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * Defers construction until the managed path is actually requested and
     * reuses the production transport across SDK operations. The installer is
     * stateless between calls apart from immutable configuration; its
     * filesystem lock serializes refresh and publication.
     */
    private static final class ManagedInstallerHolder {
        private static final LockerCliInstaller INSTANCE =
                new LockerCliInstaller();

        private ManagedInstallerHolder() {
        }
    }
}
