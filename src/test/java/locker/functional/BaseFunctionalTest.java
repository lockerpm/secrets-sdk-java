package locker.functional;

import locker.LockerClient;
import org.junit.jupiter.api.BeforeEach;

abstract class BaseFunctionalTest {
  protected LockerClient client;

  @BeforeEach
  void setUpClient() {
    String accessKeyId = requireEnvironment("LOCKER_TEST_ACCESS_KEY_ID");
    String secretAccessKey = requireEnvironment("LOCKER_TEST_SECRET_ACCESS_KEY");
    String apiBase = System.getenv("LOCKER_TEST_API_BASE");

    this.client = new LockerClient(accessKeyId, secretAccessKey, apiBase);
  }

  private static String requireEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
        "Missing " + name + " environment variable required by functional tests"
      );
    }
    return value;
  }
}
