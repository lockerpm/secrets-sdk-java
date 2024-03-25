package locker.model;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

public class LockerRawJsonObjectDeserializer implements JsonDeserializer<LockerRawJsonObject> {
    /**
     * Deserializes a JSON payload into a {@link LockerRawJsonObject} object.
     */
    @Override
    public LockerRawJsonObject deserialize(
            JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
        LockerRawJsonObject object = new LockerRawJsonObject();
        object.json = json.getAsJsonObject();
        return object;
    }
}
