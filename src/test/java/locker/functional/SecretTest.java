package locker.functional;

import locker.exception.LockerError;
import locker.model.Secret;
import locker.param.secret.SecretCreateParams;
import locker.param.secret.SecretUpdateParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


public class SecretTest extends BaseFunctionalTest {
  String testKeyName = "key_"+System.currentTimeMillis();
  String testValue = "test value";
  @Test
  public void testCreate() throws LockerError {

    SecretCreateParams createParams = SecretCreateParams.builder()
      .setKey(testKeyName)
      .setValue(testValue)
      .build();
    Secret newSecret = client.secrets().create(createParams, Secret.class);
    assertNotNull(newSecret);
    assertEquals(newSecret.getKey(), testKeyName);
  }

  @Test
  public void testModify() throws LockerError {
    String currentName = "test_2";
    String newName = "updated_name";
    SecretUpdateParams updateParams = SecretUpdateParams.builder()
      .setKey(newName)
      .setValue("newValue")
      .build();
    Secret updatedSecret = client.secrets().modify(currentName, updateParams, Secret.class);
    assertEquals(updatedSecret.getKey(), newName);
  }

  @Test
  public void testRetrieve() throws LockerError {
    String name = "test_1";
    String envName = "env_java_1";
    Secret secret = client.secrets().retrieve(name, Secret.class, envName);
    assertNotNull(secret);
    assertEquals(name, secret.getKey());
  }
  @Test
  public void testList()throws LockerError{
    String secretList = client.secrets().list(String.class);
    assertNotNull(secretList);
  }
}
