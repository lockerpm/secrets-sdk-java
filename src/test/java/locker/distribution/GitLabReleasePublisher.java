package locker.distribution;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Creates or exactly reconciles the GitLab tag and release after Central is
 * publicly verified.
 */
public final class GitLabReleasePublisher {
    private static final Pattern VERSION = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                    + "\\.(0|[1-9][0-9]*)$"
    );
    private static final Pattern COMMIT = Pattern.compile(
            "^(?:[0-9a-f]{40}|[0-9a-f]{64})$"
    );
    private static final Pattern PROJECT_ID = Pattern.compile(
            "^[1-9][0-9]*$"
    );
    private static final int MAX_TITLE_CHARACTERS = 200;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT =
            Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofSeconds(30);

    private final Transport transport;

    private GitLabReleasePublisher() {
        throw new AssertionError("No instances without a transport");
    }

    GitLabReleasePublisher(Transport transport) {
        this.transport = java.util.Objects.requireNonNull(
                transport,
                "transport"
        );
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments == null || arguments.length != 0) {
            throw new IllegalArgumentException(
                    "GitLab release publisher accepts no arguments"
            );
        }
        ReleaseInput input = ReleaseInput.fromEnvironment();
        try (HttpTransport transport = new HttpTransport(
                input.apiBase
        )) {
            new GitLabReleasePublisher(transport).publish(input);
        }
    }

    void publish(ReleaseInput input) throws Exception {
        input.validate();
        URI endpoint = input.apiBase.resolve(
                "projects/" + input.projectId + "/releases"
        );
        String releaseName =
                "Locker Secrets Java SDK " + input.tag;
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("name", releaseName);
        payload.put("tag_name", input.tag);
        payload.put("tag_message", releaseName);
        payload.put("ref", input.commit);
        payload.put("released_at", input.releasedAt);
        payload.put(
                "description",
                description(input)
        );
        byte[] requestBody = new Gson().toJson(payload).getBytes(
                StandardCharsets.UTF_8
        );
        Response response;
        try {
            response = transport.send(
                    "POST",
                    endpoint,
                    requestBody,
                    input.jobToken
            );
        } finally {
            Arrays.fill(requestBody, (byte) 0);
        }

        if (response.statusCode == 400
                || response.statusCode == 409) {
            response.erase();
            response = transport.send(
                    "GET",
                    URI.create(
                            endpoint.toASCIIString() + "/" + input.tag
                    ),
                    new byte[0],
                    input.jobToken
            );
        }
        try {
            if (response.statusCode < 200
                    || response.statusCode >= 300) {
                throw new IOException(
                        "GitLab release request failed with HTTP "
                                + response.statusCode
                );
            }
            verifyResponse(
                    response.body,
                    input,
                    releaseName
            );
        } finally {
            response.erase();
        }
        System.out.println(
                "GitLab release " + input.tag
                        + " points to " + input.commit
        );
    }

    private static String description(ReleaseInput input) {
        StringBuilder result = new StringBuilder();
        result.append("Locker Secrets Java SDK `")
                .append(input.version)
                .append("`.\n\n### Changes\n\n")
                .append(input.title)
                .append("\n\n- [Maven Central](https://repo1.maven.org/")
                .append("maven2/io/locker/lockersm/")
                .append(input.version)
                .append("/)\n");
        int patch = Integer.parseInt(
                input.version.substring(
                        input.version.lastIndexOf('.') + 1
                )
        );
        if (patch > 0) {
            String previous = input.version.substring(
                    0,
                    input.version.lastIndexOf('.') + 1
            ) + (patch - 1);
            result.append("- [Changes since v")
                    .append(previous)
                    .append("](")
                    .append(input.projectUrl)
                    .append("/-/compare/v")
                    .append(previous)
                    .append("...v")
                    .append(input.version)
                    .append(")\n");
        }
        result.append("- [Source commit](")
                .append(input.projectUrl)
                .append("/-/commit/")
                .append(input.commit)
                .append(")");
        return result.toString();
    }

    private static void verifyResponse(
            byte[] response,
            ReleaseInput input,
            String expectedName
    ) throws IOException {
        JsonElement parsed;
        try {
            parsed = StrictJson.parse(response, 16);
        } catch (CliDistributionException exception) {
            throw new IOException(
                    "GitLab returned invalid release JSON",
                    exception
            );
        }
        if (!parsed.isJsonObject()) {
            throw new IOException(
                    "GitLab release response must be an object"
            );
        }
        JsonObject object = parsed.getAsJsonObject();
        String tag = requireString(object, "tag_name");
        String name = requireString(object, "name");
        JsonElement commitElement = object.get("commit");
        if (commitElement == null
                || !commitElement.isJsonObject()) {
            throw new IOException(
                    "GitLab release response is missing its commit"
            );
        }
        String commit = requireString(
                commitElement.getAsJsonObject(),
                "id"
        );
        if (!input.tag.equals(tag)
                || !input.commit.equals(commit)
                || !expectedName.equals(name)) {
            throw new IOException(
                    "Existing GitLab release does not exactly match "
                            + "this source release"
            );
        }
    }

    private static String requireString(
            JsonObject object,
            String name
    ) throws IOException {
        JsonElement element = object.get(name);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IOException(
                    "GitLab release " + name + " must be a string"
            );
        }
        return element.getAsString();
    }

    interface Transport {
        Response send(
                String method,
                URI uri,
                byte[] body,
                String jobToken
        ) throws IOException, InterruptedException;
    }

    static final class Response {
        private final int statusCode;
        private final byte[] body;

        Response(int statusCode, byte[] body) {
            this.statusCode = statusCode;
            this.body = Arrays.copyOf(body, body.length);
        }

        private void erase() {
            Arrays.fill(body, (byte) 0);
        }
    }

    static final class ReleaseInput {
        private final URI apiBase;
        private final String projectId;
        private final String projectUrl;
        private final String jobToken;
        private final String version;
        private final String tag;
        private final String commit;
        private final String releasedAt;
        private final String title;

        ReleaseInput(
                URI apiBase,
                String projectId,
                String projectUrl,
                String jobToken,
                String version,
                String tag,
                String commit,
                String releasedAt,
                String title
        ) {
            this.apiBase = apiBase;
            this.projectId = projectId;
            this.projectUrl = projectUrl;
            this.jobToken = jobToken;
            this.version = version;
            this.tag = tag;
            this.commit = commit;
            this.releasedAt = releasedAt;
            this.title = title;
        }

        private static ReleaseInput fromEnvironment() {
            return new ReleaseInput(
                    URI.create(
                            trimTrailingSlashes(
                                    requireEnvironment(
                                            "CI_API_V4_URL"
                                    )
                            ) + "/"
                    ),
                    requireEnvironment("CI_PROJECT_ID"),
                    requireEnvironment("CI_PROJECT_URL"),
                    requireEnvironment("CI_JOB_TOKEN"),
                    requireEnvironment("LOCKER_SDK_VERSION"),
                    requireEnvironment("LOCKER_RELEASE_TAG"),
                    requireEnvironment("CI_COMMIT_SHA"),
                    requireEnvironment("CI_COMMIT_TIMESTAMP"),
                    requireEnvironment("CI_COMMIT_TITLE")
            );
        }

        private static String trimTrailingSlashes(String value) {
            int end = value.length();
            while (end > 0 && value.charAt(end - 1) == '/') {
                end--;
            }
            return value.substring(0, end);
        }

        private void validate() {
            String normalizedProjectUrl = projectUrl.endsWith("/")
                    ? projectUrl.substring(
                    0,
                    projectUrl.length() - 1
            )
                    : projectUrl;
            URI projectUri = URI.create(normalizedProjectUrl);
            if (!"https".equals(apiBase.getScheme())
                    || !"https".equals(projectUri.getScheme())
                    || apiBase.getHost() == null
                    || !apiBase.getHost().equals(projectUri.getHost())
                    || apiBase.getPort() != projectUri.getPort()
                    || apiBase.getUserInfo() != null
                    || projectUri.getUserInfo() != null
                    || apiBase.getFragment() != null
                    || projectUri.getFragment() != null
                    || !apiBase.getPath().endsWith("/api/v4/")
                    || !PROJECT_ID.matcher(projectId).matches()
                    || !VERSION.matcher(version).matches()
                    || !tag.equals("v" + version)
                    || !COMMIT.matcher(commit).matches()
                    || jobToken == null
                    || jobToken.isBlank()
                    || jobToken.length() > 4096
                    || !normalizedProjectUrl.equals(projectUrl)
                    || !validTitle(title)) {
                throw new IllegalArgumentException(
                        "GitLab release inputs are invalid"
                );
            }
            try {
                OffsetDateTime.parse(releasedAt);
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException(
                        "GitLab release timestamp is invalid",
                        exception
                );
            }
        }

        private static String requireEnvironment(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(
                        name + " is required for GitLab release"
                );
            }
            return value;
        }
    }

    private static boolean validTitle(String value) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.length() > MAX_TITLE_CHARACTERS) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                return false;
            }
        }
        return true;
    }

    private static final class HttpTransport
            implements Transport, AutoCloseable {
        private final URI trustedApiBase;
        private final ExecutorService executor;
        private final HttpClient client;

        private HttpTransport(URI trustedApiBase) {
            this.trustedApiBase = trustedApiBase;
            executor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "locker-gitlab-release-http"
                );
                thread.setDaemon(true);
                return thread;
            });
            client = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .executor(executor)
                    .build();
        }

        @Override
        public Response send(
                String method,
                URI uri,
                byte[] body,
                String jobToken
        ) throws IOException, InterruptedException {
            if (!trustedApiBase.getScheme().equals(uri.getScheme())
                    || !trustedApiBase.getHost().equals(uri.getHost())
                    || trustedApiBase.getPort() != uri.getPort()
                    || !uri.getPath().startsWith(
                    trustedApiBase.getPath()
            )
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null) {
                throw new IOException(
                        "GitLab release request left the trusted API"
                );
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("JOB-TOKEN", jobToken)
                    .header(
                            "User-Agent",
                            "LockerSM-Java-Release/1"
                    );
            if (body.length > 0) {
                builder.header(
                        "Content-Type",
                        "application/json"
                );
            }
            HttpResponse<InputStream> response = client.send(
                    builder.method(
                            method,
                            body.length == 0
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers
                                    .ofByteArray(body)
                    ).build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            byte[] responseBody;
            try (InputStream input = response.body()) {
                responseBody = readBounded(input);
            }
            return new Response(
                    response.statusCode(),
                    responseBody
            );
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static byte[] readBounded(InputStream input)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total = Math.addExact(total, read);
            if (total > MAX_RESPONSE_BYTES) {
                throw new IOException(
                        "GitLab release response exceeds its size limit"
                );
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
