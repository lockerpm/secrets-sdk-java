package locker.distribution;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class StrictJson {
    private StrictJson() {
    }

    static JsonElement parse(byte[] bytes, int maxDepth)
            throws CliDistributionException {
        if (bytes == null || maxDepth < 1) {
            throw new CliDistributionException(
                    "The Locker CLI distribution JSON is invalid"
            );
        }
        try {
            String decoded = decodeUtf8(bytes);
            requireValidEscapedUnicode(decoded);
            try (JsonReader reader = new JsonReader(
                    new StringReader(decoded)
            )) {
                reader.setStrictness(Strictness.STRICT);
                JsonElement value = readElement(reader, 0, maxDepth);
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IOException("Trailing JSON data");
                }
                return value;
            }
        } catch (IOException
                 | IllegalStateException
                 | NumberFormatException exception) {
            throw new CliDistributionException(
                    "The Locker CLI distribution JSON is invalid",
                    exception
            );
        }
    }

    private static JsonElement readElement(
            JsonReader reader,
            int depth,
            int maxDepth
    ) throws IOException {
        if (depth > maxDepth) {
            throw new IOException("JSON nesting limit exceeded");
        }
        JsonToken token = reader.peek();
        switch (token) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    requireWellFormedUnicode(name);
                    if (object.has(name)) {
                        throw new IOException("Duplicate JSON field");
                    }
                    object.add(
                            name,
                            readElement(reader, depth + 1, maxDepth)
                    );
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(readElement(reader, depth + 1, maxDepth));
                }
                reader.endArray();
                return array;
            case STRING:
                String value = reader.nextString();
                requireWellFormedUnicode(value);
                return new JsonPrimitive(value);
            case NUMBER:
                return new JsonPrimitive(
                        new BigDecimal(reader.nextString())
                );
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new IOException("Invalid JSON token");
        }
    }

    private static String decodeUtf8(byte[] bytes)
            throws CharacterCodingException {
        CharBuffer characters = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return characters.toString();
    }

    private static void requireValidEscapedUnicode(
            String json
    ) throws IOException {
        boolean inString = false;
        for (int index = 0; index < json.length(); index++) {
            char current = json.charAt(index);
            if (!inString) {
                if (current == '"') {
                    inString = true;
                }
                continue;
            }
            if (current == '"') {
                inString = false;
                continue;
            }
            if (current != '\\') {
                continue;
            }
            index++;
            if (index >= json.length() || json.charAt(index) != 'u') {
                continue;
            }
            int codeUnit = readEscapedCodeUnit(json, index);
            index += 4;
            if (Character.isLowSurrogate((char) codeUnit)) {
                throw new IOException("Unpaired Unicode surrogate");
            }
            if (!Character.isHighSurrogate((char) codeUnit)) {
                continue;
            }
            if (index + 6 >= json.length()
                    || json.charAt(index + 1) != '\\'
                    || json.charAt(index + 2) != 'u') {
                throw new IOException("Unpaired Unicode surrogate");
            }
            int low = readEscapedCodeUnit(json, index + 2);
            if (!Character.isLowSurrogate((char) low)) {
                throw new IOException("Unpaired Unicode surrogate");
            }
            index += 6;
        }
    }

    private static int readEscapedCodeUnit(
            String json,
            int uIndex
    ) throws IOException {
        if (uIndex + 4 >= json.length()) {
            throw new IOException("Incomplete Unicode escape");
        }
        int value = 0;
        for (int offset = 1; offset <= 4; offset++) {
            int digit = Character.digit(
                    json.charAt(uIndex + offset),
                    16
            );
            if (digit < 0) {
                throw new IOException("Invalid Unicode escape");
            }
            value = (value << 4) + digit;
        }
        return value;
    }

    private static void requireWellFormedUnicode(
            String value
    ) throws IOException {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(
                        value.charAt(index + 1)
                )) {
                    throw new IOException("Invalid Unicode string");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IOException("Invalid Unicode string");
            }
        }
    }
}
