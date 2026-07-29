package locker.net;


import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * Converter to map an api request object to an untyped map. It is not called a *Serializer because
 * the outcome is not a JSON data. It is not called *UntypedMapDeserializer because it is not
 * converting from JSON.
 */
class CliRequestParamsConverter {
    private static final Gson GSON =
            new GsonBuilder()
                    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                    .registerTypeAdapterFactory(new HasEmptyEnumTypeAdapterFactory())
                    .registerTypeAdapterFactory(new NullValuesInMapsTypeAdapterFactory())
                    .create();

    private static final UntypedMapDeserializer FLATTENING_EXTRA_PARAMS_DESERIALIZER =
            new UntypedMapDeserializer(new ExtraParamsFlatteningStrategy());

    /**
     * Strategy to flatten extra params in the API request parameters.
     */
    private static class ExtraParamsFlatteningStrategy implements UntypedMapDeserializer.Strategy {
        @Override
        public void deserializeAndTransform(
                Map<String, Object> outerMap,
                Map.Entry<String, JsonElement> jsonEntry,
                UntypedMapDeserializer untypedMapDeserializer) {
            String key = jsonEntry.getKey();
            JsonElement jsonValue = jsonEntry.getValue();
            if (CliRequestParams.EXTRA_PARAMS_KEY.equals(key)) {
                if (!jsonValue.isJsonObject()) {
                    throw new IllegalStateException(
                            String.format(
                                    "Expected an object at reserved parameter key `%s`",
                                    CliRequestParams.EXTRA_PARAMS_KEY
                            )
                    );
                }
                // JSON value now corresponds to the extra params map, and is also deserialized as a map.
                // Instead of putting this result map under the original key, flatten the map
                // by adding all its key/value pairs to the outer map instead.
                Map<String, Object> extraParamsMap =
                        untypedMapDeserializer.deserialize(jsonValue.getAsJsonObject());
                for (Map.Entry<String, Object> entry : extraParamsMap.entrySet()) {
                    validateDuplicateKey(outerMap, entry.getKey());
                    outerMap.put(entry.getKey(), entry.getValue());
                }
            } else {
                Object value = untypedMapDeserializer.deserializeJsonElement(jsonValue);
                validateDuplicateKey(outerMap, key);

                // Normal deserialization where output map has the same structure as the given JSON content.
                // The deserialized content is an untyped `Object` and added to the outer map at the
                // original key.
                outerMap.put(key, value);
            }
        }
    }

    private static void validateDuplicateKey(
            Map<String, Object> outerMap,
            String paramKey
    ) {
        if (outerMap.containsKey(paramKey)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Duplicate request parameter key `%s`",
                            paramKey
                    )
            );
        }
    }

    private static class NullValuesInMapsTypeAdapterFactory implements TypeAdapterFactory {
        TypeAdapter<?> getValueAdapter(Gson gson, TypeToken<?> type) {
            Type valueType;
            if (type.getType() instanceof ParameterizedType) {
                ParameterizedType mapParameterizedType = (ParameterizedType) type.getType();
                valueType = mapParameterizedType.getActualTypeArguments()[1];
            } else {
                valueType = Object.class;
            }

            return gson.getAdapter(TypeToken.get(valueType));
        }

        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (!Map.class.isAssignableFrom(type.getRawType())) {
                return null;
            }

            final TypeAdapter<?> valueAdapter = getValueAdapter(gson, type);
            final TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
            @SuppressWarnings({"unchecked", "rawtypes"}) final TypeAdapter<T> typeAdapter = new MapAdapter(valueAdapter, delegate);

            return typeAdapter.nullSafe();
        }
    }

    private static class MapAdapter<V> extends TypeAdapter<Map<String, V>> {
        private TypeAdapter<V> valueTypeAdapter;
        private TypeAdapter<Map<String, V>> mapTypeAdapter;

        public MapAdapter(TypeAdapter<V> valueTypeAdapter, TypeAdapter<Map<String, V>> mapTypeAdapter) {
            this.valueTypeAdapter = valueTypeAdapter;
            this.mapTypeAdapter = mapTypeAdapter;
        }

        @Override
        public void write(JsonWriter out, Map<String, V> value) throws IOException {
            if (value == null) {
                out.nullValue();
                return;
            }

            out.beginObject();
            for (Map.Entry<String, V> entry : value.entrySet()) {
                out.name(entry.getKey());
                V entryValue = entry.getValue();
                if (entryValue == null) {
                    boolean oldSerializeNullsValue = out.getSerializeNulls();
                    try {
                        out.setSerializeNulls(true);
                        out.nullValue();
                    } finally {
                        out.setSerializeNulls(oldSerializeNullsValue);
                    }
                } else {
                    valueTypeAdapter.write(out, entryValue);
                }
            }
            out.endObject();
        }

        @Override
        public Map<String, V> read(JsonReader in) throws IOException {
            return mapTypeAdapter.read(in);
        }
    }

    /**
     * Type adapter to convert an empty enum to null value to comply with the lower-lever encoding
     * logic for the API request parameters.
     */
    private static class HasEmptyEnumTypeAdapterFactory implements TypeAdapterFactory {
        @SuppressWarnings("unchecked")
        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (!CliRequestParams.EnumParam.class.isAssignableFrom(type.getRawType())) {
                return null;
            }

            TypeAdapter<CliRequestParams.EnumParam> paramEnum =
                    new TypeAdapter<CliRequestParams.EnumParam>() {
                        @Override
                        public void write(JsonWriter out, CliRequestParams.EnumParam value) throws IOException {
                            if (value.getValue().isEmpty()) {
                                // need to restore serialize null setting
                                // not to affect other fields
                                boolean previousSetting = out.getSerializeNulls();
                                try {
                                    out.setSerializeNulls(true);
                                    out.nullValue();
                                } finally {
                                    out.setSerializeNulls(previousSetting);
                                }
                            } else {
                                out.value(value.getValue());
                            }
                        }

                        @Override
                        public CliRequestParams.EnumParam read(JsonReader in) {
                            throw new UnsupportedOperationException(
                                    "No deserialization is expected from this private type adapter for enum param.");
                        }
                    };
            return (TypeAdapter<T>) paramEnum.nullSafe();
        }
    }

    /**
     * Convert the given request params into an untyped map. This map is composed of {@code
     * Map<String, Object>}, {@code List<Object>}, and basic Java data types. This allows you to test
     * building the request params and verify compatibility with your prior integrations using the
     * untyped params map.
     *
     * <p>There are two peculiarities in this conversion:
     *
     * <p>1) {@link EmptyParam#EMPTY}, containing a raw empty string value, is converted to null. This
     * represents an explicit null request value. Because of the translation
     * from {@code EMPTY} to null, deserializing this map back to a request
     * instance is lossy.
     *
     * <p>2) Parameter with serialized name {@link CliRequestParams#EXTRA_PARAMS_KEY} will be
     * flattened. This is to support passing new params that the current library has not yet
     * supported.
     */
    Map<String, Object> convert(CliRequestParams cliRequestParams) {
        JsonObject jsonParams = GSON.toJsonTree(cliRequestParams).getAsJsonObject();
        return FLATTENING_EXTRA_PARAMS_DESERIALIZER.deserialize(jsonParams);
    }
}
