package locker.distribution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackagingMetadataTest {
    @Test
    void runtimeArtifactContainsLicenseAndDistributionMetadata()
            throws Exception {
        assertResourceContains(
                "/META-INF/LICENSE",
                "Apache License"
        );
        try (InputStream stream = PackagingMetadataTest.class
                .getResourceAsStream(
                        "/locker-cli-ed25519-public-key.txt"
                )) {
            assertNotNull(
                    stream,
                    "locker-cli-ed25519-public-key.txt"
            );
            String value = new String(
                    stream.readAllBytes(),
                    StandardCharsets.US_ASCII
            );
            assertTrue(!value.contains("\r"));
            assertTrue(!value.isBlank());
            assertEquals(value.trim() + "\n", value);
            assertEquals(
                    SignedUpdateContract.PUBLIC_KEY_BYTES,
                    SignedUpdateContract.decodePublicKey(value.trim()).length
            );
            assertEquals(
                    value.trim(),
                    LockerCliInstaller.compiledReleasePublicKey()
            );
        }
    }

    private static void assertResourceContains(
            String name,
            String expected
    ) throws IOException {
        try (InputStream stream =
                     PackagingMetadataTest.class.getResourceAsStream(name)) {
            assertNotNull(stream, name);
            String value = new String(
                    stream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
            assertTrue(value.contains(expected), name);
        }
    }
}
