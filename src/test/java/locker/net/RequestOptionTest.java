package locker.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class RequestOptionTest {
    private static final String PLACEHOLDER_SECRET =
            String.join("-", "test", "credential", "placeholder");

    @Test
    public void testPersistentValuesInToBuilder() {
        RequestOptions options = RequestOptions.builder()
                .setAccessKeyId("option-access-key-id")
                .setSecretAccessKey(PLACEHOLDER_SECRET)
                .build();

        assertEquals(
                "option-access-key-id",
                options.getAccessKeyId()
        );
        assertEquals(
                PLACEHOLDER_SECRET,
                options.getSecretAccessKey()
        );
    }

    @Test
    public void testMergeClientOptions() {
        LockerResponseGetterOptions clientOptions =
                new TestLockerResponseGetterOptions(
                        "client-access-key-id",
                        PLACEHOLDER_SECRET,
                        "https://example.test/locker",
                        null
                );
        RequestOptions requestOptions = RequestOptions.builder()
                .setAccessKeyId("option-access-key-id")
                .setApiBase("https://region.example.test/locker")
                .build();

        RequestOptions merged = RequestOptions.merge(
                clientOptions,
                requestOptions
        );

        assertEquals(
                "option-access-key-id",
                merged.getAccessKeyId()
        );
        assertEquals(
                PLACEHOLDER_SECRET,
                merged.getSecretAccessKey()
        );
        assertEquals(
                "https://region.example.test/locker",
                merged.getApiBase()
        );
    }

    @Test
    public void testMergeWithoutClientOptions() {
        RequestOptions merged = RequestOptions.merge(null, null);

        assertNull(merged.getAccessKeyId());
        assertNull(merged.getSecretAccessKey());
        assertNull(merged.getApiBase());
        assertNull(merged.getHeaders());
    }
}
