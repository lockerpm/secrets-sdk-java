package locker.net;

import com.google.gson.annotations.SerializedName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CliRequestParamsConvertTest {
  private CliRequestParamsConverter converter = new CliRequestParamsConverter();

  // The fields are implicitly used in testing serialization
  @SuppressWarnings("UnusedVariable")
  private static class ModelHasExtraParams extends CliRequestParams {
    private String stringValue;


    @SerializedName(CliRequestParams.EXTRA_PARAMS_KEY)
    private Map<String, Object> extraParams;

    public ModelHasExtraParams() {
      this.stringValue = "foo";
      this.extraParams = new HashMap<>();
      this.extraParams.put("hello", "world");
    }
  }

  private static class HasMetadataParams extends CliRequestParams {
    @SerializedName("metadata")
    Map<String, String> metadata;

    @SerializedName("feature_map")
    Map<String, Long> featureMap;

    @SerializedName("object_map")
    Map<String, ModelHasExtraParams> objectMap;
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testObjectMaps() {
    HasMetadataParams params = new HasMetadataParams();
    params.objectMap = new HashMap<>();
    params.objectMap.put("foo", new ModelHasExtraParams());
    params.objectMap.put("bar", new ModelHasExtraParams());

    Map<String, Object> untypedParams = toMap(params);
    Map<String, Object> metadata = (Map<String, Object>) untypedParams.get("object_map");
    assertEquals(metadata.size(), 2);
    Map<String, Object> objFoo = (Map<String, Object>) metadata.get("foo");
    Map<String, Object> objBar = (Map<String, Object>) metadata.get("bar");
    assertEquals(objFoo.size(), 2);
    assertEquals(objBar.size(), 2);


    assertEquals(objFoo.get("string_value"), "foo");
    assertEquals(objFoo.get("hello"), "world");

    assertEquals(objBar.get("string_value"), "foo");
    assertEquals(objBar.get("hello"), "world");
  }

  private Map<String, Object> toMap(CliRequestParams params) {
    return converter.convert(params);
  }
}
