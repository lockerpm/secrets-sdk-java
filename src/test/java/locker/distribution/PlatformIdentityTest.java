package locker.distribution;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlatformIdentityTest {
    @Test
    void mapsOnlyCanonicalV2Targets() throws Exception {
        assertPlatform("Mac OS X", "aarch64", "darwin", "arm64");
        assertPlatform("Darwin", "x86_64", "darwin", "amd64");
        assertPlatform("Windows 11", "amd64", "windows", "amd64");
        assertPlatform("Linux", "x86_64", "linux", "amd64");
        assertPlatform("Linux", "arm64", "linux", "arm64");
    }

    @Test
    void rejectsUnknownFallbackPlatforms() {
        assertThrows(
                CliDistributionException.class,
                () -> PlatformIdentity.from("FreeBSD", "amd64")
        );
        assertThrows(
                CliDistributionException.class,
                () -> PlatformIdentity.from("Windows 11", "arm64")
        );
    }

    private static void assertPlatform(
            String os,
            String arch,
            String expectedOs,
            String expectedArch
    ) throws Exception {
        PlatformIdentity identity = PlatformIdentity.from(os, arch);
        assertEquals(expectedOs, identity.getOs());
        assertEquals(expectedArch, identity.getArch());
    }
}
