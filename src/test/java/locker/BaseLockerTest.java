package locker;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class BaseLockerTest {
  protected static String getFixture(String path) throws Exception {
    InputStream inputStream = BaseLockerTest.class.getClassLoader().getResourceAsStream(path);

    // Read the content of the JSON file
    byte[] bytes = inputStream.readAllBytes();
    String jsonContent = new String(bytes, StandardCharsets.UTF_8);

    return jsonContent;
  }

}
