package locker.model;

import locker.BaseLockerTest;
import locker.net.CliResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class EnvironmentTest extends BaseLockerTest {
  @Test
  public void testDeserialize() throws Exception {
    final String data = getFixture("model_fixture/environment.json");
    final Environment resource = CliResource.GSON.fromJson(data, Environment.class);
    assertNotNull(resource);
    assertNotNull(resource.getId());
  }
}
