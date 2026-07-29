package locker.distribution;

import java.util.Locale;
import java.util.Objects;

final class PlatformIdentity {
    private final String os;
    private final String arch;

    private PlatformIdentity(String os, String arch) {
        this.os = os;
        this.arch = arch;
    }

    static PlatformIdentity current() throws CliDistributionException {
        return from(
                System.getProperty("os.name"),
                System.getProperty("os.arch")
        );
    }

    static PlatformIdentity from(String osName, String architecture)
            throws CliDistributionException {
        String normalizedOs = normalize(osName);
        String normalizedArch = normalize(architecture);
        String os;
        if (normalizedOs.startsWith("mac")
                || normalizedOs.startsWith("darwin")) {
            os = "darwin";
        } else if (normalizedOs.startsWith("windows")) {
            os = "windows";
        } else if (normalizedOs.startsWith("linux")) {
            os = "linux";
        } else {
            throw unsupported();
        }

        String arch;
        if ("amd64".equals(normalizedArch)
                || "x86_64".equals(normalizedArch)
                || "x64".equals(normalizedArch)) {
            arch = "amd64";
        } else if ("arm64".equals(normalizedArch)
                || "aarch64".equals(normalizedArch)) {
            arch = "arm64";
        } else {
            throw unsupported();
        }

        if ("windows".equals(os) && "arm64".equals(arch)) {
            throw unsupported();
        }
        return new PlatformIdentity(os, arch);
    }

    String getOs() {
        return os;
    }

    String getArch() {
        return arch;
    }

    boolean isWindows() {
        return "windows".equals(os);
    }

    private static String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private static CliDistributionException unsupported() {
        return new CliDistributionException(
                "The Locker CLI is not distributed for this Java platform"
        );
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PlatformIdentity)) {
            return false;
        }
        PlatformIdentity identity = (PlatformIdentity) other;
        return os.equals(identity.os)
                && arch.equals(identity.arch);
    }

    @Override
    public int hashCode() {
        return Objects.hash(os, arch);
    }
}
