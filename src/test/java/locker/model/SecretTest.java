package locker.model;

import locker.BaseLockerTest;
import locker.net.CliResource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class SecretTest extends BaseLockerTest {
  @Test
  public void testDeserializer() throws Exception {
    final String data = getFixture("model_fixture/secret.json");
    final Secret resource = CliResource.GSON.fromJson(data, Secret.class);
    assertNotNull(resource);
    assertNotNull(resource.getId());

  }
}
