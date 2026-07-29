package example;

import locker.LockerClient;
import locker.exception.LockerError;

import java.time.Duration;

/**
 * Minimal example. Credentials and the CLI path are read from the standard
 * Locker environment variables.
 */
public class LockerExample {
    public static void main(String[] args) {
        LockerClient client = LockerClient.builder()
                .setCliTimeout(Duration.ofSeconds(30))
                .build();

        try {
            String value = client.secrets().retrieve(
                    "DATABASE_PASSWORD",
                    String.class,
                    "production"
            );
            // Use the value without logging or embedding it in an exception.
            System.out.println("Secret loaded: " + !value.isEmpty());
        } catch (LockerError error) {
            System.err.println(
                    "Locker request failed"
                            + (error.getRequestId() == null
                            ? ""
                            : " (request " + error.getRequestId() + ")")
            );
        }
    }
}
