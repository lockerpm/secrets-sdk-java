# Locker Secrets Java SDK

[![Maven Central](https://img.shields.io/maven-central/v/io.locker/lockersm.svg)](https://central.sonatype.com/artifact/io.locker/lockersm)

The official Java SDK for Locker Passwords & Secrets Management.

The SDK uses the Locker CLI's versioned JSON-RPC protocol. Application code
works with typed Java services while credentials, custom headers, secret
values, and mutation data are sent to `locker sdk` through stdin. Sensitive
data is never added to process arguments or SDK logs.

## Requirements

- Java 11 or later
- Maven 3.9 or later for building the SDK
- A Locker CLI binary that supports SDK protocol v1

## Installation

Maven:

```xml
<dependency>
    <groupId>io.locker</groupId>
    <artifactId>lockersm</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle:

```groovy
implementation("io.locker:lockersm:1.0.0")
```

## Configure the client

CLI resolution order is:

1. an explicit `setCliPath` value;
2. `LOCKER_CLI_PATH`;
3. the canonical managed binary resolved through the signed Locker CLI
   update channel.

Java-owned managed state lives under `~/.locker/sdk-cli/java`. Each release
is an immutable directory below `generations/`; `locker.current.json`
atomically selects the active `locker` (or `locker.exe`) generation.

An explicit path or `LOCKER_CLI_PATH` is caller-owned and bypasses the managed
updater completely. It must be an absolute path to an executable regular,
non-symlink file; bare command names and relative paths are rejected, and the
SDK never searches the ambient `PATH`.
Without either override, the SDK verifies or refreshes its managed binary when
the first operation runs. Importing the package and constructing a client or
installer never performs filesystem or network I/O.

The production trust root is embedded in the SDK. Runtime lookup never accepts
a same-name resource from another classpath entry.

Use the Locker-prefixed environment variables for credentials:

```shell
export LOCKER_CLI_PATH=/usr/local/bin/locker
export LOCKER_ACCESS_KEY_ID=<access-key-id>
export LOCKER_SECRET_ACCESS_KEY=<secret-access-key>
```

PowerShell:

```powershell
$Env:LOCKER_CLI_PATH = 'C:\Program Files\Locker\locker.exe'
$Env:LOCKER_ACCESS_KEY_ID = '<access-key-id>'
$Env:LOCKER_SECRET_ACCESS_KEY = '<secret-access-key>'
```

For migration only, the SDK also accepts `ACCESS_KEY_ID` and the legacy
secret aliases in this precedence order:
`SECRET_ACCESS_KEY`, `LOCKER_ACCESS_KEY_SECRET`, then `ACCESS_KEY_SECRET`.
Explicit client configuration takes precedence over every environment
variable. New deployments should use only the canonical `LOCKER_*` names.

| Environment variable | Purpose |
| --- | --- |
| `LOCKER_ACCESS_KEY_ID` | Project access key ID |
| `LOCKER_SECRET_ACCESS_KEY` | Project secret access key |
| `LOCKER_CLI_PATH` | Absolute caller-owned CLI path |

Configure a cloud or self-hosted API base with `setApiBase`; Java does not
implicitly read an API-base environment variable.

The default client reads those values when it executes an operation:

```java
import locker.LockerClient;

LockerClient client = LockerClient.builder().build();
```

You can configure all values explicitly when your application's secret
provider supplies them:

```java
import java.time.Duration;
import locker.LockerClient;

LockerClient client = LockerClient.builder()
        .setCliPath("/opt/locker/bin/locker")
        .setAccessKeyId(accessKeyId)
        .setSecretAccessKey(secretAccessKey)
        .setCliTimeout(Duration.ofSeconds(30))
        .build();
```

An optional API base and custom transport headers can be configured on the
builder:

```java
import java.util.Map;

LockerClient client = LockerClient.builder()
        .setApiBase("https://api.locker.io/locker_secrets")
        .setHeaders(Map.of("CF-Access-Client-Id", clientId))
        .build();
```

## Signed managed CLI updates

The default client resolves the managed CLI lazily. Applications that want to
prepare it during startup or deployment can call the same lifecycle
explicitly:

```java
import java.nio.file.Path;
import locker.distribution.LockerCliInstaller;

// This is the only call here that may perform network I/O.
Path installedCli = new LockerCliInstaller().install();
```

Managed downloads are accepted only after the signed release metadata,
manifest, binary digest, detached signature, and executable platform header
all verify. Verified versions are stored privately and activated atomically.
The SDK revalidates the selected managed executable before use and rejects
rollback, tampering, unsafe paths, and insecure cache ownership.

A temporary release-channel outage may reuse a previously verified compatible
binary. TLS, signature, schema, hash, platform, rollback, and local integrity
failures always fail closed. Explicit absolute CLI paths remain caller-owned
and are not treated as managed, signed artifacts.

## Retrieve a secret

Use `String.class` when the application only needs the value:

```java
String databasePassword = client.secrets().retrieve(
        "DATABASE_PASSWORD",
        String.class,
        "production"
);
```

Use `Secret.class` when metadata is required:

```java
import locker.model.Secret;

Secret secret = client.secrets().retrieve(
        "DATABASE_PASSWORD",
        Secret.class
);
```

`Secret.toString()` always redacts the value. Avoid logging
`secret.getValue()` or the retrieved string.

## List secrets

```java
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import locker.model.LockerCollection;
import locker.model.Secret;

Type secretListType = new TypeToken<LockerCollection<Secret>>() {
}.getType();

LockerCollection<Secret> secrets = client.secrets().list(secretListType);
```

Filter by environment:

```java
LockerCollection<Secret> productionSecrets = client.secrets().list(
        locker.param.secret.SecretListParams.builder()
                .setEnvironmentName("production")
                .build(),
        null,
        secretListType
);
```

For large vaults, use the bounded page API. Cursors are opaque; return them
unchanged on the next request:

```java
import locker.model.SecretPage;
import locker.param.secret.SecretListPageParams;

SecretPage page = client.secrets().listPage(
        SecretListPageParams.builder()
                .setEnvironmentName("production")
                .setPageSize(100)
                .build()
);

while (page.getNextCursor() != null) {
    page = client.secrets().listPage(
            SecretListPageParams.builder()
                    .setEnvironmentName("production")
                    .setPageSize(100)
                    .setCursor(page.getNextCursor())
                    .build()
    );
}
```

`client.environments().listPage(...)` returns the corresponding typed
`EnvironmentPage`.

If a legacy unpaginated list cannot fit the negotiated response bound, it
throws `ApiError` with protocol code `-32000`, error code
`response_too_large`, and `retryable == false`; switch to `listPage` rather
than retrying the same list call.

## Create and update secrets

```java
import locker.model.Secret;
import locker.param.secret.SecretCreateParams;
import locker.param.secret.SecretUpdateParams;

Secret created = client.secrets().create(
        SecretCreateParams.builder()
                .setKey("DATABASE_PASSWORD")
                .setValue(databasePassword)
                .setEnvironmentName("production")
                .setDescription("Application database password")
                .build(),
        Secret.class
);

Secret updated = client.secrets().modify(
        "DATABASE_PASSWORD",
        SecretUpdateParams.builder()
                .setValue(rotatedPassword)
                .build(),
        Secret.class,
        "production"
);
```

Clear a secret's environment association explicitly:

```java
SecretUpdateParams changes = SecretUpdateParams.builder()
        .clearEnvironment()
        .build();
```

Secret values and descriptions are serialized into the JSON request body, not
the CLI command line.

## Environments

```java
import locker.model.Environment;
import locker.param.environment.EnvironmentCreateParams;

Environment environment = client.environments().create(
        EnvironmentCreateParams.builder()
                .setName("production")
                .setExternalUrl("https://example.com")
                .setDescription("Production environment")
                .build(),
        Environment.class
);
```

The environment service also provides `retrieve`, `list`, and `modify`.

## Per-request options

Every service method accepts a `RequestOptions` overload. Per-request values
override client values and are defensively copied:

```java
import locker.net.RequestOptions;

RequestOptions requestOptions = RequestOptions.builder()
        .setApiBase(regionalApiBase)
        .setHeaders(regionalHeaders)
        .build();

String value = client.secrets().retrieve(
        "DATABASE_PASSWORD",
        requestOptions,
        String.class
);
```

### Timeout, interruption, retry, and cache

`setCliTimeout` bounds each capability or vault protocol process and its
immediate managed-binary rebind. Initial managed installation has separate,
bounded connect, response, download, and interprocess-lock deadlines.
Interrupting the calling thread terminates the known CLI process tree and
restores the interrupt flag.

The SDK never automatically retries a vault RPC. Create and update are issued
once because a lost response can leave the remote commit outcome unknown.
Applications may inspect `LockerError.getRetryable()` and apply bounded retry
only to read-only operations.

The Java SDK does not keep plaintext secret values. Vault caching is delegated
to the CLI's encrypted, revision-aware cache using protocol defaults; this
version does not expose Java cache overrides. A transient outage can reuse
only a still-fresh cache last validated successfully by the server.
Authentication, authorization, TLS, integrity, malformed-response, and local
storage failures fail closed.

## Error handling

All SDK exceptions extend `LockerError`. Operation errors are mapped from the
protocol's numeric error code and retain the safe structured details:

```java
import locker.exception.AlreadyExistsError;
import locker.exception.ConflictError;
import locker.exception.LockerError;
import locker.exception.RateLimitError;
import locker.model.Secret;
import locker.param.secret.SecretCreateParams;

try {
    client.secrets().create(
            SecretCreateParams.builder()
                    .setKey("PAYMENT_API_KEY")
                    .setValue(paymentApiKey)
                    .build(),
            Secret.class
    );
} catch (AlreadyExistsError error) {
    // PAYMENT_API_KEY already exists.
    // AlreadyExistsError is also a ConflictError.
} catch (RateLimitError error) {
    Integer retryAfter = error.getRetryAfterSeconds(); // Optional 0..86400 hint.
} catch (LockerError error) {
    Integer protocolCode = error.getProtocolCode();
    String requestId = error.getRequestId();
    Boolean retryable = error.getRetryable();
}
```

| Protocol code | Java exception | Canonical kind |
| ---: | --- | --- |
| `-32700` | `ProtocolError` | `parse_error` |
| `-32600` | `ProtocolError` | `invalid_request` |
| `-32601` | `ProtocolError` | `method_not_found` |
| `-32602` | `ProtocolError` | `invalid_params` |
| `-32603` | `ProtocolError` | `internal_protocol_error` |
| `-32000` | `ApiError` and legacy subtypes | `operation_error`, `request_rejected`, `response_too_large`, `cancelled` |
| `-32001` | `AuthenticationError` | `missing_credentials`, `invalid_access_key_id`, `malformed_secret_access_key`, `invalid_secret_access_key`, `unauthorized` |
| `-32003` | `PermissionDeniedError` | `forbidden`; legacy `permission_denied` |
| `-32004` | `ResourceNotFoundError` | `secret_not_found`, `environment_not_found`; legacy `not_found_error` |
| `-32009` | `ConflictError` / `AlreadyExistsError` | `conflict`, `secret_already_exists`, `environment_already_exists` |
| `-32022` | `ValidationError` | `validation_error` |
| `-32029` | `RateLimitError` | `rate_limited` |
| `-32050` | `ApiConnectionError` | `network_error`, `network_timeout`; legacy `http_error` |
| `-32051` | `ApiServerError` | `service_unavailable`, `internal_error`; legacy `server_error` |
| `-32060` | `StorageError` | `database_error`, `file_error`, `path_error` |
| `-32070` | `IntegrityError` | `integrity_error`, `transport_integrity_error`, `data_integrity_error`; legacy `data_error` |

Classification is numeric-first. Distinctive kinds from older CLI releases
(`duplicate_hash`, `*_already_exists`, `conflict`, `validation_error`, and
the integrity aliases) are also mapped when their legacy code is `-32000`.
`request_rejected`, `response_too_large`, and `cancelled` have explicit
`ApiError` subtypes but are never guessed to be conflicts. Known
authentication, permission, not-found, conflict, validation, storage,
integrity, protocol, cancellation, and internal-server errors force
`retryable == false`; only rate-limit, network, service-unavailable, or an
unknown server-range code can preserve a true hint. `RateLimitError` exposes
an optional validated `getRetryAfterSeconds()` value from 0 through 86400.
The SDK never automatically retries a vault RPC.

Credentials are normalized and validated before the SDK resolves, downloads,
or launches a CLI binary. Access key IDs must be UUIDv4 values and secret
access keys must be non-empty canonical standard Base64. A structurally valid
pair that does not match is reported as `invalid_secret_access_key`; a backend
HTTP 401 remains the deliberately generic `unauthorized`. Authentication
exceptions never expose either credential or raw backend response text.

Typed errors are negotiated additively. The SDK sends
`context.error_contract = "typed-v1"` only when the exact contract appears in
the capability root's `error_contracts` list. Absence and unknown valid
contracts remain compatible and do not opt in. The SDK never places raw
request bodies, responses, or CLI stderr in exception messages.
`getServerRequestId()` exposes a separately validated upstream correlation ID
when one exists; it never replaces the local JSON-RPC `getRequestId()` and is
not included in the default exception text.

## Protocol and process safety

Do not hard-code credentials or secret values in source code. The SDK starts
the child CLI with a small operating-system/proxy/certificate environment
allowlist, so application secrets are not inherited accidentally. On timeout
or interruption it performs bounded process-tree cleanup. Run any
caller-supplied executable under an appropriate OS or container supervisor.

Before the first vault operation, the SDK negotiates `system.capabilities` and
requires Locker SDK protocol v1, the eight base vault methods plus
`system.capabilities`, and positive request/response byte limits. Paginated
list methods are additive capabilities: an older compatible CLI can still run
base operations, while a `listPage` call fails locally when its method was not
advertised. Each request:

- starts the binary with the single argument `sdk`;
- writes one UTF-8 JSON-RPC request to stdin and closes it;
- reads stdout and stderr separately with bounded buffers and applies the
  smaller of the local output limit and the CLI-advertised response limit;
- applies a bounded timeout and terminates the process tree on failure;
- validates the response envelope, request ID, required types, and protocol
  version.

## Versioning

The SDK follows Semantic Versioning. Maven Central artifacts use
`MAJOR.MINOR.PATCH`; matching source releases use
`vMAJOR.MINOR.PATCH`. See the release notes when upgrading across a major
version.

## Migration, troubleshooting, and support

Version 1 is the stable protocol-v1 boundary. Replace direct REST calls,
human-output parsing, relative CLI names, and legacy credential variables
with typed services, an explicit absolute CLI path or managed mode, and
canonical `LOCKER_*` credentials.

- Authentication/permission errors: verify the complete credential pair and
  its project/environment scope.
- `CliDistributionException`: check system time, HTTPS access to
  `files.locker.io`, and private ownership below `~/.locker/sdk-cli/java`.
- `CliRunError`: check the absolute CLI path and configured timeout; never log
  application arguments that may contain secret values.
- Protocol errors: upgrade the SDK and CLI together or remove an incompatible
  explicit `LOCKER_CLI_PATH`.

Product help is available at [support.locker.io](https://support.locker.io).
Report vulnerabilities privately to <contact@locker.io>.

## License

Apache License 2.0.
