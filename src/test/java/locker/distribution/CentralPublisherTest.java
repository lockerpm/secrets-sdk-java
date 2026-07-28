package locker.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CentralPublisherTest {
    private static final String DEPLOYMENT_ID =
            "28570f16-da32-4c14-bd2e-c1acc0782365";

    @TempDir
    Path temporaryDirectory;

    @Test
    public void stagesExactBundleAndPersistsBoundDeployment()
            throws Exception {
        FakeTransport transport = new FakeTransport(
                response(201, DEPLOYMENT_ID),
                response(200, statusJson("VALIDATING")),
                response(200, statusJson("VALIDATED"))
        );
        int[] sleeps = {0};
        CentralPublisher publisher = new CentralPublisher(
                transport,
                milliseconds -> sleeps[0]++,
                "token-user",
                "token-password"
        );
        Path bundle = temporaryDirectory.resolve(
                "central-bundle.zip"
        );
        Files.writeString(
                bundle,
                "verified-bundle",
                StandardCharsets.US_ASCII
        );
        Path identifier = temporaryDirectory.resolve(
                "central-deployment-id"
        );

        publisher.stage(bundle, identifier, "v1.2.3");

        assertEquals(
                DEPLOYMENT_ID + "\n",
                Files.readString(identifier, StandardCharsets.US_ASCII)
        );
        assertEquals(3, transport.requests.size());
        assertEquals(1, sleeps[0]);
        RecordedRequest upload = transport.requests.get(0);
        assertEquals("POST", upload.request.method);
        assertEquals(
                "/api/v1/publisher/upload",
                upload.request.uri.getPath()
        );
        assertTrue(
                upload.request.uri.getQuery()
                        .contains("publishingType=USER_MANAGED")
        );
        assertTrue(
                upload.request.uri.getQuery()
                        .contains("name=lockersm-java-v1.2.3")
        );
        assertTrue(
                upload.request.contentType.startsWith(
                        "multipart/form-data; boundary="
                )
        );
        assertTrue(upload.request.body.contentLength() > 0);
        assertEquals(
                "Bearer " + Base64.getEncoder().encodeToString(
                        "token-user:token-password".getBytes(
                                StandardCharsets.UTF_8
                        )
                ),
                upload.authorization
        );
    }

    @Test
    public void publishesOnlyValidatedDeploymentAndWaits()
            throws Exception {
        FakeTransport transport = new FakeTransport(
                response(200, statusJson("VALIDATED")),
                response(204, ""),
                response(200, statusJson("PUBLISHING")),
                response(200, statusJson("PUBLISHED"))
        );
        CentralPublisher publisher = publisher(transport);
        Path identifier = writeIdentifier();

        publisher.publish(identifier);

        assertEquals(4, transport.requests.size());
        assertEquals(
                "/api/v1/publisher/deployment/" + DEPLOYMENT_ID,
                transport.requests.get(1).request.uri.getPath()
        );
    }

    @Test
    public void publishedRetryDoesNotPromoteAgain() throws Exception {
        FakeTransport transport = new FakeTransport(
                response(200, statusJson("PUBLISHED"))
        );

        publisher(transport).publish(writeIdentifier());

        assertEquals(1, transport.requests.size());
        assertTrue(transport.responses.isEmpty());
    }

    @Test
    public void persistsDeploymentIdBeforeValidationFailure()
            throws Exception {
        FakeTransport transport = new FakeTransport(
                response(201, DEPLOYMENT_ID),
                response(200, statusJson("FAILED"))
        );
        Path bundle = temporaryDirectory.resolve(
                "central-bundle.zip"
        );
        Files.writeString(bundle, "bundle", StandardCharsets.US_ASCII);
        Path identifier = temporaryDirectory.resolve(
                "central-deployment-id"
        );

        assertThrows(
                IOException.class,
                () -> publisher(transport).stage(
                        bundle,
                        identifier,
                        "v1.0.0"
                )
        );

        assertEquals(
                DEPLOYMENT_ID + "\n",
                Files.readString(identifier, StandardCharsets.US_ASCII)
        );
    }

    @Test
    public void strictStatusParserRejectsAmbiguousOrUnboundJson() {
        List<String> invalidResponses = List.of(
                "{"
                        + "\"deploymentId\":\"" + DEPLOYMENT_ID + "\","
                        + "\"deploymentId\":\"" + DEPLOYMENT_ID + "\","
                        + "\"deploymentState\":\"VALIDATED\""
                        + "}",
                "{"
                        + "\"deploymentId\":\"" + DEPLOYMENT_ID + "\","
                        + "\"deployment\\u0049d\":\"" + DEPLOYMENT_ID
                        + "\","
                        + "\"deploymentState\":\"VALIDATED\""
                        + "}",
                statusJson("VALIDATED") + " null",
                statusJson("validated"),
                "{"
                        + "\"deploymentId\":1,"
                        + "\"deploymentState\":\"VALIDATED\""
                        + "}",
                "{"
                        + "\"deploymentId\":\""
                        + "11111111-1111-1111-1111-111111111111"
                        + "\","
                        + "\"deploymentState\":\"VALIDATED\""
                        + "}",
                "{"
                        + "\"deploymentId\":\"" + DEPLOYMENT_ID + "\","
                        + "\"deploymentState\":\"UNKNOWN\""
                        + "}"
        );

        for (String response : invalidResponses) {
            assertThrows(
                    IOException.class,
                    () -> CentralPublisher.parseStatus(
                            response.getBytes(StandardCharsets.UTF_8),
                            DEPLOYMENT_ID
                    )
            );
        }
    }

    @Test
    public void strictStatusParserAcceptsTypedOfficialShape() {
        String response = "{"
                + "\"deploymentId\":\"" + DEPLOYMENT_ID + "\","
                + "\"deploymentName\":\"central-bundle.zip\","
                + "\"deploymentState\":\"PUBLISHED\","
                + "\"purls\":[\"pkg:maven/io.locker/lockersm@1.0.0\"]"
                + "}";

        assertDoesNotThrow(() -> assertEquals(
                CentralPublisher.DeploymentState.PUBLISHED,
                CentralPublisher.parseStatus(
                        response.getBytes(StandardCharsets.UTF_8),
                        DEPLOYMENT_ID
                )
        ));
    }

    @Test
    public void rejectsCredentialDelimitersAndControlsBeforeNetwork() {
        FakeTransport transport = new FakeTransport();

        for (String invalid : List.of(
                "bad:user",
                "bad\nuser",
                "bad\ruser",
                "bad\0user"
        )) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CentralPublisher(
                            transport,
                            milliseconds -> {
                            },
                            invalid,
                            "password"
                    )
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new CentralPublisher(
                            transport,
                            milliseconds -> {
                            },
                            "username",
                            invalid
                    )
            );
        }
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void refusesToOverwriteDeploymentIdentifier()
            throws Exception {
        FakeTransport transport = new FakeTransport(
                response(201, DEPLOYMENT_ID)
        );
        Path bundle = temporaryDirectory.resolve(
                "central-bundle.zip"
        );
        Files.writeString(bundle, "bundle", StandardCharsets.US_ASCII);
        Path identifier = temporaryDirectory.resolve(
                "central-deployment-id"
        );
        Files.writeString(
                identifier,
                "existing",
                StandardCharsets.US_ASCII
        );

        assertThrows(
                IOException.class,
                () -> publisher(transport).stage(
                        bundle,
                        identifier,
                        "v1.0.0"
                )
        );
        assertEquals(
                "existing",
                Files.readString(
                        identifier,
                        StandardCharsets.US_ASCII
                )
        );
    }

    private CentralPublisher publisher(FakeTransport transport) {
        return new CentralPublisher(
                transport,
                milliseconds -> {
                },
                "username",
                "password"
        );
    }

    private Path writeIdentifier() throws Exception {
        Path identifier = temporaryDirectory.resolve(
                "central-deployment-id"
        );
        Files.writeString(
                identifier,
                DEPLOYMENT_ID + "\n",
                StandardCharsets.US_ASCII
        );
        return identifier;
    }

    private static CentralPublisher.Response response(
            int status,
            String body
    ) {
        return new CentralPublisher.Response(
                status,
                body.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String statusJson(String state) {
        return "{"
                + "\"deploymentId\":\"" + DEPLOYMENT_ID + "\","
                + "\"deploymentState\":\"" + state + "\""
                + "}";
    }

    private static final class FakeTransport
            implements CentralPublisher.Transport {
        private final Deque<CentralPublisher.Response> responses =
                new ArrayDeque<>();
        private final List<RecordedRequest> requests =
                new ArrayList<>();

        private FakeTransport(
                CentralPublisher.Response... queuedResponses
        ) {
            responses.addAll(List.of(queuedResponses));
        }

        @Override
        public CentralPublisher.Response send(
                CentralPublisher.Request request,
                String authorization
        ) throws IOException {
            requests.add(new RecordedRequest(
                    request,
                    authorization
            ));
            CentralPublisher.Response response = responses.pollFirst();
            if (response == null) {
                throw new IOException(
                        "No fake Maven Central response is available"
                );
            }
            return response;
        }
    }

    private static final class RecordedRequest {
        private final CentralPublisher.Request request;
        private final String authorization;

        private RecordedRequest(
                CentralPublisher.Request request,
                String authorization
        ) {
            this.request = request;
            this.authorization = authorization;
        }
    }
}
