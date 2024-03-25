package locker.net;


import java.util.Map;


public class TestLockerResponseGetterOptions extends LockerResponseGetterOptions {

  private String accessKeyId;

  private String secretAccessKey;

  private String apiBase;

  private Map<String, String> headers;

  public TestLockerResponseGetterOptions(String accessKeyId, String secretAccessKey, String apiBase, Map<String, String> headers) {
    this.accessKeyId = accessKeyId;
    this.secretAccessKey = secretAccessKey;
    this.apiBase = apiBase;
    this.headers = headers;
  }

  @Override
  public String getAccessKeyId() {
    return this.accessKeyId;
  }

  @Override
  public String getSecretAccessKey() {
    return this.secretAccessKey;
  }

  @Override
  public String getApiBase() {
    return this.apiBase;
  }

  @Override
  public Map<String, String> getHeaders() {
    return this.headers;
  }
}
