package locker.net;

import locker.BaseLockerTest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RequestOptionTest extends BaseLockerTest {
  @Test
  public void testPersistentValuesInToBuilder() {
    RequestOptions opts =
      RequestOptions.builder()
        .setAccessKeyId("opt_access_key_id")
        .setSecretAccessKey("opt_secret_access_key")
        .build();
    assertEquals("opt_access_key_id", opts.getAccessKeyId());
    assertEquals("opt_secret_access_key", opts.getSecretAccessKey());

  }

  @Test
  public void testMergeClientOptions() {
    LockerResponseGetterOptions clientOptions =
      new TestLockerResponseGetterOptions("client_access_key_id", "secret_access_key", "client_api_base", null);

    RequestOptions requestOptions = RequestOptions.builder()
      .setAccessKeyId("opt_access_key_id")
      .setApiBase("opt_api_base")
      .build();

    RequestOptions merged = RequestOptions.merge(clientOptions, requestOptions);
    assertEquals("opt_access_key_id", merged.getAccessKeyId());
    assertEquals("secret_access_key", merged.getSecretAccessKey());
    assertEquals("opt_api_base", merged.getApiBase());
  }
}

