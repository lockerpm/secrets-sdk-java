package locker.distribution;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GitLabReleasePublisherTest {
    private static final String COMMIT = "a".repeat(40);

    @Test
    public void createsTagAndReleaseFromTheExactCommit()
            throws Exception {
        FakeTransport transport = new FakeTransport(
                response(201, releaseJson("v1.2.3", COMMIT))
        );

        new GitLabReleasePublisher(transport).publish(input());

        assertEquals(1, transport.requests.size());
        Request request = transport.requests.get(0);
        assertEquals("POST", request.method);
        JsonObject body = StrictJson.parse(
                request.body,
                16
        ).getAsJsonObject();
        assertEquals("v1.2.3", body.get("tag_name").getAsString());
        assertEquals(COMMIT, body.get("ref").getAsString());
        assertEquals("job-token", request.jobToken);
    }

    @Test
    public void reconcilesAnExistingReleaseIdempotently()
            throws Exception {
        FakeTransport transport = new FakeTransport(
                response(409, "{}"),
                response(200, releaseJson("v1.2.3", COMMIT))
        );

        new GitLabReleasePublisher(transport).publish(input());

        assertEquals(2, transport.requests.size());
        assertEquals("POST", transport.requests.get(0).method);
        assertEquals("GET", transport.requests.get(1).method);
    }

    @Test
    public void rejectsAnExistingReleaseForAnotherCommit() {
        FakeTransport transport = new FakeTransport(
                response(
                        409,
                        "{}"
                ),
                response(
                        200,
                        releaseJson("v1.2.3", "b".repeat(40))
                )
        );

        assertThrows(
                java.io.IOException.class,
                () -> new GitLabReleasePublisher(transport)
                        .publish(input())
        );
    }

    @Test
    public void rejectsCrossHostApiConfigurationBeforeNetwork() {
        GitLabReleasePublisher.ReleaseInput invalid =
                new GitLabReleasePublisher.ReleaseInput(
                        URI.create("https://api.example/api/v4/"),
                        "42",
                        "https://git.example/project",
                        "job-token",
                        "1.2.3",
                        "v1.2.3",
                        COMMIT,
                        "2026-07-29T00:00:00Z",
                        "Merge release"
                );
        FakeTransport transport = new FakeTransport();

        assertThrows(
                IllegalArgumentException.class,
                () -> new GitLabReleasePublisher(transport)
                        .publish(invalid)
        );
        assertEquals(0, transport.requests.size());
    }

    private static GitLabReleasePublisher.ReleaseInput input() {
        return new GitLabReleasePublisher.ReleaseInput(
                URI.create("https://git.example/api/v4/"),
                "42",
                "https://git.example/locker/secrets-sdk/java",
                "job-token",
                "1.2.3",
                "v1.2.3",
                COMMIT,
                "2026-07-29T00:00:00Z",
                "Merge release automation"
        );
    }

    private static GitLabReleasePublisher.Response response(
            int status,
            String body
    ) {
        return new GitLabReleasePublisher.Response(
                status,
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String releaseJson(
            String tag,
            String commit
    ) {
        return "{"
                + "\"tag_name\":\"" + tag + "\","
                + "\"name\":\"Locker Secrets Java SDK " + tag + "\","
                + "\"commit\":{\"id\":\"" + commit + "\"}"
                + "}";
    }

    private static final class FakeTransport
            implements GitLabReleasePublisher.Transport {
        private final Deque<GitLabReleasePublisher.Response> responses =
                new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();

        private FakeTransport(
                GitLabReleasePublisher.Response... responses
        ) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public GitLabReleasePublisher.Response send(
                String method,
                URI uri,
                byte[] body,
                String jobToken
        ) {
            requests.add(new Request(
                    method,
                    uri,
                    body.clone(),
                    jobToken
            ));
            return responses.removeFirst();
        }
    }

    private static final class Request {
        private final String method;
        private final URI uri;
        private final byte[] body;
        private final String jobToken;

        private Request(
                String method,
                URI uri,
                byte[] body,
                String jobToken
        ) {
            this.method = method;
            this.uri = uri;
            this.body = body;
            this.jobToken = jobToken;
        }
    }
}
