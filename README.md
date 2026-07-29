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

The stable, reviewed production trust root is compiled into
`LockerCliInstaller` and mirrored in the committed public-key resource for
release attestation. Runtime lookup never accepts a same-name resource from
another classpath entry. CI never rewrites either copy.

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

Update-channel v2 has one trust root: the raw 32-byte Ed25519 public key
compiled into the installer as canonical base64url. The matching
`locker-cli-ed25519-public-key.txt` resource lets release verification attest
the source, compiled classes, and packaged JAR. The updater fetches the exact
`https://files.locker.io/cli/releases/latest.json` endpoint without redirects, verifies
its signed canonical JSON envelope, then verifies the referenced signed
version manifest. It requires the exact five supported platform targets and
binds the selected artifact's version, path, size, SHA-256, and detached
Ed25519 signature to those documents.

Downloaded binaries are streamed through a bounded whole-response deadline
into a private same-filesystem temporary file. Before publication, the updater
verifies the declared 1..256 MiB size, digest, detached signature, and strict
ELF/Mach-O/PE target header. It flushes every file, atomically publishes one
complete immutable generation, fully reverifies that generation, and replaces
the small current pointer last. Metadata, state, and every cached binary are
revalidated under a bounded interprocess lock. Symlinks/reparse points,
permissive ownership, partial files, unknown schema fields, duplicate JSON
keys, non-canonical encodings, rollback, and same-version equivocation fail
closed.

A successful check is persisted for six hours. Each later SDK operation
resolves the managed path again; a long-running client atomically rebuilds its
protocol/capability client when the selected generation changes. A due check
always revalidates the signed latest pointer and manifest, without downloading
an unchanged artifact. The accepted `(version, source_commit, manifest
SHA-256, manifest size)` high-water tuple is persisted before any manifest
request, independently from the successful-check timestamp, so a partial
update cannot later permit rollback or same-version equivocation. A transient
fallback gets a 60-second retry marker without advancing the six-hour success
interval.

Immediately before every CLI subprocess spawn, the SDK cryptographically
rebinds the selected managed executable to its signed manifest, streamed
size/SHA-256, and executable header within the operation timeout. The detached
Ed25519 signature is verified when a generation is installed or first loaded
into the process; subsequent same-generation rebinds use a bounded 8 KiB
buffer instead of retaining the binary in memory. File identity metadata is
only a capability-cache optimization and is never a managed-binary trust
decision. A same-size in-place modification is rejected even if its mtime is
restored; the active generation is not silently repaired in that execution
path. Explicit absolute paths remain caller-owned and receive only the
documented regular-file/identity checks, not managed-channel validation.

Only DNS/connect/timeout transport failures and HTTP 408, 425, 429, or
500..599 may fall back to a completely verified cache. TLS/certificate,
signature, schema, executable-header, hash, size, rollback, state, and other
integrity failures never use an offline fallback. A process crash at any
publication boundary leaves either the old pointed generation or the complete
new one recoverable offline.

## Release safety

Every merge commit pushed to the protected `main` branch is released
automatically. Tag pipelines are ignored, so the tag created at the end of a
successful release cannot recursively start another pipeline. Direct,
fast-forward, squash, and rebased updates to the release line fail closed:
each first-parent commit after the reviewed baseline must have exactly two
parents.

Concurrent main pipelines share the `lockersm-maven-central` resource group.
Configure that group once with GitLab process mode `oldest_first`; for example,
a Maintainer can call `PUT /projects/:id/resource_groups/lockersm-maven-central`
with `process_mode=oldest_first` through the GitLab API after the group first
appears. Keep the predecessor-tag gate enabled as the authoritative release
order invariant: every release after the first polls for its exact predecessor
tag and requires it to resolve to the immediately preceding first-parent
merge. Because a tag is created only after the predecessor is byte-for-byte
public on Maven Central, a newer pipeline cannot publish out of order even if
external GitLab scheduling is misconfigured.

The version is derived deterministically from first-parent merge distance.
The first eligible merge is `1.0.0`, the next is `1.0.1`, and so on. Maven's
CI-friendly `revision` property is the single source of the base version; the
published flattened POM and JAR manifest contain the resolved release version,
and runtime protocol metadata reads that manifest rather than a separately
maintained constant or file.

The protected `auto-release` job requires:

- protected-main governance that rejects `[ci skip]` and `[skip ci]` commit
  messages and rejects the `ci.skip` and `ci.no_pipeline` Git push options
  (use a project push rule or a self-managed GitLab pre-receive hook), so no
  release-line merge can bypass its mandatory pipeline;
- a protected, masked `LOCKER_CLI_RELEASE_PUBLIC_KEY` variable containing
  exactly one canonical base64url-encoded raw 32-byte Ed25519 public key,
  without spaces or a trailing newline, byte-equal to the committed trust
  root;
- a protected `MAVEN_GPG_KEY_FILE` file variable and masked
  `MAVEN_GPG_PASSPHRASE`;
- masked `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` variables
  containing the Central Portal-generated user-token pair, not the account
  password. The release client uses the documented Central Portal form
  (`Authorization: Bearer <base64(user:token)>`).

Release validation fails closed when `LICENSE` is absent or empty, the tag
does not equal `v` plus the POM/SDK version, the public key is malformed, or
the key embedded in the built SDK differs byte-for-byte from the protected
release key. The verifier reads the committed source resource and compiled
main resource by filesystem path, never through the test classpath, and the
Central bundle builder independently verifies the entry in the exact JAR
bytes it packages.

The `release` Maven profile signs one artifact set. CI builds and independently
verifies the resulting Central deployment bundle, records its SHA-256,
and checks the exact public Maven coordinates before any upload. If that
version is absent, CI makes one upload attempt, waits for `VALIDATED`, promotes
that deployment, and then waits until every expected public payload is
byte-identical. It requires Central's MD5/SHA-1 public payload sidecars and
verifies SHA-256/SHA-512 sidecars whenever Central exposes them; the
authenticated pre-publication deployment must preserve all four submitted
payload sidecars byte-for-byte. Detached OpenPGP signatures include a creation
timestamp, so
their armor is not compared byte-for-byte: each local and public signature
must instead verify the exact payload with SHA-512 and the precise fingerprint
derived from `MAVEN_GPG_KEY_FILE`. This lets a rerun safely skip an already
public release without accepting a different signer. A partial or conflicting
public version never triggers another upload.

The release job uses a test-scope Java release client rather than
putting an authorization header in a shell command. It reads Central
credentials only from the protected environment, rejects delimiters and
control characters, never logs them, refuses redirects, bounds every response,
and parses status JSON strictly. Duplicate or escaped-duplicate fields,
trailing data, non-string identifiers/states, unknown states, and a response
whose deployment ID does not exactly match the persisted ID all fail closed.

The Central upload endpoint has no caller-supplied idempotency key. Before
uploading, CI therefore searches the authenticated deployment list for the
deterministic release name. After an ambiguous upload response it polls that
same name, accepts exactly one matching deployment, and fails closed if more
than one exists. A recovered deployment is not trusted by name alone: after
validation, CI downloads every payload and detached signature by deployment
ID, requires byte-identical payloads and checksum sidecars, and
cryptographically verifies the signatures against the protected signing key.
Central explicitly does not require checksum sidecars for `.asc` signature
files, so the bundle omits that unnecessary file-count overhead. This makes
the bounded GitLab retry safe when a runner or network connection is lost.
Once Central is
byte-for-byte public, CI creates or exactly reconciles the GitLab tag and
Release as its final mutation using the built-in `CI_JOB_TOKEN`. Protect the
`main` branch, `v*` tags, the `maven-central` environment, and every listed variable in
GitLab project settings.

After the first pipeline creates the resource group, a Maintainer must run:

```shell
curl --request PUT \
  --header "PRIVATE-TOKEN: <maintainer-token>" \
  --data "process_mode=oldest_first" \
  "https://git.cystack.org/api/v4/projects/<project-id>/resource_groups/lockersm-maven-central"
```

Do not hard-code credentials or secret values in source code. The SDK starts
the child CLI with a small operating-system/proxy/certificate environment
allowlist, so application secrets are not inherited accidentally.
It continuously records descendant handles and, on timeout, interruption, or
even a successful root exit, performs bounded graceful/forced cleanup and
verifies that every recorded process stopped. Java 11 exposes neither portable
POSIX process groups nor Windows Job Objects, so a process created and
re-parented entirely between samples remains a standard-library limitation.
The supported trust boundary is therefore an explicit caller-owned CLI or the
cryptographically verified managed binary; run any untrusted executable under
an OS/container supervisor.

An optional API base and custom transport headers can be configured on the
builder:

```java
import java.util.Map;

LockerClient client = LockerClient.builder()
        .setApiBase("https://api.locker.io/locker_secrets")
        .setHeaders(Map.of("CF-Access-Client-Id", clientId))
        .build();
```

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
| `-32001` | `AuthenticationError` | `unauthorized`; legacy `invalid_secret_access_key` |
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

Typed errors are negotiated additively. The SDK sends
`context.error_contract = "typed-v1"` only when the exact contract appears in
the capability root's `error_contracts` list. Absence and unknown valid
contracts remain compatible and do not opt in. The SDK never places raw
request bodies, responses, or CLI stderr in exception messages.
`getServerRequestId()` exposes a separately validated upstream correlation ID
when one exists; it never replaces the local JSON-RPC `getRequestId()` and is
not included in the default exception text.

## Protocol and process safety

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

## Development

Run the hermetic unit and protocol tests:

```shell
mvn --strict-checksums test
```

Build the binary, source, and Javadoc artifacts:

```shell
mvn --strict-checksums package
```

GitLab uses an immutable official Maven/Temurin 11 image digest. Maven strict
checksum mode and the Enforcer rule reject corrupted repository responses and
snapshot dependencies; the content-keyed `.m2` cache is an optimization, not
a trust source. CI does not install build tools through `apt`, `apk`, `curl`,
or `wget`.

Run the optional protocol negotiation check against a real local CLI:

```shell
LOCKER_INTEGRATION_CLI=/path/to/locker \
  mvn -Dtest=locker.net.RealCliConformanceTest test
```

Live functional tests require `LOCKER_TEST_ACCESS_KEY_ID` and
`LOCKER_TEST_SECRET_ACCESS_KEY`. They are intentionally separate from the
default unit-test suite; never commit real credentials to test sources.

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
