package locker.net;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

public class LiveLockerResponseGetterRotationTest {
    @Test
    public void replacesProtocolClientWhenManagedGenerationChanges()
            throws Exception {
        AtomicReference<String> path =
                new AtomicReference<>("generation-a/locker");
        LiveLockerResponseGetter getter =
                new LiveLockerResponseGetter(
                        null,
                        explicit -> path.get()
                );

        SdkProtocolClient first = getter.client();
        assertSame(first, getter.client());

        path.set("generation-b/locker");
        SdkProtocolClient second = getter.client();

        assertNotSame(first, second);
        assertSame(second, getter.client());
    }
}
