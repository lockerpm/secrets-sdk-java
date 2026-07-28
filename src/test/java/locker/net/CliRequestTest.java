package locker.net;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CliRequestTest {
    @Test
    public void snapshotsMutableInputsAndUsage() {
        List<String> cli = new ArrayList<>(List.of("secret", "list"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("environment_name", "production");

        CliRequest request = new CliRequest(
                CliResource.RequestMethod.GET,
                cli,
                params,
                null
        );
        cli.add("--unsafe");
        params.put("cursor", "changed");

        assertEquals(List.of("secret", "list"), request.getCli());
        assertEquals(
                Map.of("environment_name", "production"),
                request.getParams()
        );
        assertEquals(List.of(), request.getUsage());
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.getCli().add("mutate")
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> request.getParams().put("mutate", "value")
        );

        CliRequest withUsage = request.addUsage("read");
        assertEquals(List.of(), request.getUsage());
        assertEquals(List.of("read"), withUsage.getUsage());
        assertThrows(
                UnsupportedOperationException.class,
                () -> withUsage.getUsage().add("mutate")
        );
    }
}
