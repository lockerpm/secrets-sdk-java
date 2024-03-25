package locker.param;

import locker.param.secret.SecretUpdateParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SecretUpdateParamsTest {
  @Test
  public void testGetter() {
    SecretUpdateParams secretUpdateParams = SecretUpdateParams.builder().setDescription("test").build();
    assertEquals("test", secretUpdateParams.getDescription());
  }
}
