package locker;

import locker.net.LockerResponseGetter;
import org.junit.jupiter.api.BeforeEach;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class BaseLockerTest {
  public static LockerResponseGetter mockResponseGetter;
  public static LockerClient mockClient;

  @BeforeEach
  public void setUpLockerTestUsage() {

    mockClient = new LockerClient(
      "95443edf-eb33-4fae-a66d-c264d14e7217",
      "C+NCA5fwzJeCh3R4O3Dw4YLAbJrrvgJt1bJSe1BEhrY="
    );
  }

  protected static String getFixture(String path) throws Exception {
    InputStream inputStream = BaseLockerTest.class.getClassLoader().getResourceAsStream(path);

    // Read the content of the JSON file
    byte[] bytes = inputStream.readAllBytes();
    String jsonContent = new String(bytes, StandardCharsets.UTF_8);

    return jsonContent;
  }

}
