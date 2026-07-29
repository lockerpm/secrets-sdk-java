package locker.net;

import java.util.ArrayList;
import java.util.Map;


public abstract class CliRequestParams {
    /**
     * Param key for an `extraParams` map. Any param/sub-param specifying a field intended to support
     * extra params from users should have the annotation
     * {@code @SerializedName(ApiRequestParams.EXTRA_PARAMS_KEY)}. Logic to handle this is in {@link
     * CliRequestParamsConverter}.
     */
    public static final String EXTRA_PARAMS_KEY = "_locker_java_extra_param_key";

    /**
     * Converter mapping typed API request parameters into an untyped map.
     */
    private static final CliRequestParamsConverter PARAMS_CONVERTER = new CliRequestParamsConverter();

    /**
     * Interface implemented by enum parameters to expose their serialized
     * value. The request converter maps an empty enum value to {@code null}.
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

    /**
     * Legacy human-CLI option rendering.
     *
     * SDK transports serialize parameters into the protocol request body, so
     * this compatibility method always returns an empty list.
     */
    public ArrayList<String> buildCliOptions() {
        return new ArrayList<>();
    }
}
