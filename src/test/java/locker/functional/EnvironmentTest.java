package locker.functional;

import locker.exception.LockerError;
import locker.model.Environment;
import locker.param.environment.EnvironmentCreateParams;
import locker.param.environment.EnvironmentUpdateParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class EnvironmentTest extends BaseFunctionalTest {
  String envNameTest = "env_java_2";
  String externalUrl = "env_external_url_test";

  @Test
  public void testCreate() throws LockerError {
    EnvironmentCreateParams params = EnvironmentCreateParams.builder()
      .setName(envNameTest)
      .setDescription("")
      .setExternalUrl(externalUrl)
      .build();
    Environment env = client.environments().create(params, Environment.class);
    assertEquals(env.getName(), envNameTest);
  }

  @Test
  public void testRetrieve() throws LockerError {
    Environment env = client.environments().retrieve(envNameTest, Environment.class);
    assertEquals(env.getName(), envNameTest);
  }

  @Test
  public void testModify() throws LockerError {
    EnvironmentUpdateParams params = EnvironmentUpdateParams.builder()
      .setExternalUrl("new external url")
      .setDescription("new des").build();
    Environment env = client.environments().modify(envNameTest, params, Environment.class);
    assertEquals(env.getName(), envNameTest);
    assertEquals(env.getDescription(), "new des");
  }

  @Test
  public void testList() throws LockerError {
    String envList = client.environments().list(String.class);
    assertNotNull(envList);
  }

}
