package locker.net;

import com.google.gson.*;
import locker.model.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public abstract class CliResource extends LockerObject implements LockerActiveObject {
    public static final Charset CHARSET = StandardCharsets.UTF_8;
    private static LockerResponseGetter globalResponseGetter = new LiveLockerResponseGetter();
    private transient LockerResponseGetter responseGetter;

    public static final Gson INTERNAL_GSON = createGson(false);
    public static final Gson GSON = createGson(true);

    public static void setLockerResponseGetter(LockerResponseGetter lrg) {
        CliResource.globalResponseGetter = lrg;
    }

    protected static LockerResponseGetter getGlobalResponseGetter() {
        return CliResource.globalResponseGetter;
    }

    public void setResponseGetter(LockerResponseGetter lrg) {
        responseGetter = lrg;
    }
  protected LockerResponseGetter getResponseGetter() {
    if (this.responseGetter == null) {
      return getGlobalResponseGetter();
    }
    return this.responseGetter;
  }

    private static Gson createGson(boolean shouldSetResponseGetter) {
        GsonBuilder builder = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .registerTypeAdapter(ExpandableField.class, new ExpandableFieldDeserializer())
                .registerTypeAdapter(LockerRawJsonObject.class, new LockerRawJsonObjectDeserializer())
                .registerTypeAdapterFactory(new LockerCollectionItemTypeSettingFactory())
                .addReflectionAccessFilter(
                        new ReflectionAccessFilter() {
                            @Override
                            public ReflectionAccessFilter.FilterResult check(Class<?> rawClass) {
                                if (rawClass.getTypeName().startsWith("locker")) {
                                    return ReflectionAccessFilter.FilterResult.ALLOW;
                                }else if(rawClass.getTypeName().contains("List")){
                                    return ReflectionAccessFilter.FilterResult.ALLOW;
                                }
                                return ReflectionAccessFilter.FilterResult.BLOCK_ALL;
                            }
                        }
                );
        if (shouldSetResponseGetter) {
            builder.registerTypeAdapterFactory(new LockerResponseGetterSettingTypeAdapterFactory());
        }
        for (TypeAdapterFactory factory : CliResourceTypeAdapterFactoryProvider.getAll()) {
            builder.registerTypeAdapterFactory(factory);
        }
        return builder.create();

    }

    public enum RequestMethod {
        GET,
        POST,
        DELETE,
        UPDATE
    }


    public static <T extends HasId> ExpandableField<T> setExpandableFieldId(
            String newId, ExpandableField<T> currentObject) {
        if (currentObject == null
                || (currentObject.isExpanded() && !Objects.equals(currentObject.getId(), newId))) {
            return new ExpandableField<>(newId, null);
        }

        return new ExpandableField<>(newId, currentObject.getExpanded());
    }
}
