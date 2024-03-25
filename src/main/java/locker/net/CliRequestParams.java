package locker.net;

import java.util.Map;


public abstract class CliRequestParams {
  /**
   * Param key for an `extraParams` map. Any param/sub-param specifying a field intended to support
   * extra params from users should have the annotation
   * {@code @SerializedName(ApiRequestParams.EXTRA_PARAMS_KEY)}. Logic to handle this is in {@link
   * CliRequestParamsConverter}.
   */
  public static final String EXTRA_PARAMS_KEY = "_stripe_java_extra_param_key";

  /** Converter mapping typed API request parameters into an untyped map. */
  private static final CliRequestParamsConverter PARAMS_CONVERTER = new CliRequestParamsConverter();

  /**
   * Interface implemented by all enum parameter to get the actual string value that Stripe API
   * expects. Internally, it used in custom serialization {@link CliRequestParamsConverter}
   * converting empty string enum to null.
   */
  public interface EnumParam {
    String getValue();
  }

  /**
   * Convert `this` api request params to an untyped map. The conversion is specific to api request
   * params object. Please see documentation in {@link
   * CliRequestParamsConverter#convert(CliRequestParams)}.
   */
  public Map<String, Object> toMap() {
    return PARAMS_CONVERTER.convert(this);
  }

  /**
   * Convert `params` api request params to an untyped map. The conversion is specific to api
   * request params object. Please see documentation in {@link
   * CliRequestParamsConverter#convert(CliRequestParams)}.
   */
  public static Map<String, Object> paramsToMap(CliRequestParams params) {
    if (params == null) {
      return null;
    }
    return params.toMap();
  }
}
