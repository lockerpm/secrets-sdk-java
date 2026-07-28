package locker.net;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class RealCliConformanceTest {
    @Test
    void negotiatesProtocolV1WithConfiguredRealCli()
            throws Exception {
        String configured = System.getProperty(
                "locker.integration.cli",
                System.getenv("LOCKER_INTEGRATION_CLI")
        );
        Assumptions.assumeTrue(
                configured != null && !configured.isBlank(),
                "Set LOCKER_INTEGRATION_CLI to run real CLI conformance"
        );
        Path binary = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(
                Files.isRegularFile(binary),
                "Configured integration CLI is not a regular file"
        );

        SdkProtocolClient client = new SdkProtocolClient(
                new CliProcessRunner(
                        binary.toString(),
                        Duration.ofSeconds(10)
                )
        );
        Method negotiate = SdkProtocolClient.class
                .getDeclaredMethod("negotiateCapabilities");
        negotiate.setAccessible(true);

        assertDoesNotThrow(() -> negotiate.invoke(client));
    }
}
