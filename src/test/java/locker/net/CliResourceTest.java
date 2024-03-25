package locker.net;

import com.google.gson.JsonIOException;
import locker.model.Secret;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.*;

public class CliResourceTest {

  static class MyClass extends CliResource {
    public Proxy proxy;
  }

  @Test
  public void testReflectionFilter() {
    JsonIOException e =
      assertThrows(JsonIOException.class, () -> CliResource.GSON.fromJson("{}", MyClass.class));

    // Assert that the error message involves a ReflectionAccessFilter.
    assertTrue(e.getMessage().contains("ReflectionAccessFilter"));
  }

  @Test
  public void testExternalDeserializeSetsResponseGetter() throws Exception {
    String json = "{\"id\": \"ch_123\", \"object\": \"secret\"}";
    Secret secret = CliResource.GSON.fromJson(json, Secret.class);
    assertEquals(secret.getResponseGetter(), CliResource.getGlobalResponseGetter());

  }

}
