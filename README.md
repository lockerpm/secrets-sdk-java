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

Only DNS/connect/timeout transport failures and HTTP 408, 425, 429, or
500..599 may fall back to a completely verified cache. TLS/certificate,
signature, schema, executable-header, hash, size, rollback, state, and other
integrity failures never use an offline fallback. A process crash at any
publication boundary leaves either the old pointed generation or the complete
new one recoverable offline.

## Release safety

The release pipeline accepts only a protected canonical `v<SDK_VERSION>`
SemVer tag. Its automatic `release-readiness` job requires:

- a protected, independent `LOCKER_CLI_PUBLIC_KEY_FILE` file variable
  containing exactly one canonical base64url-encoded raw 32-byte Ed25519
  public key and one final LF, byte-equal to the committed trust root;
- a protected `MAVEN_GPG_KEY_FILE` file variable and masked
  `MAVEN_GPG_PASSPHRASE`;
- masked `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD` variables for
  the later publish job.

Release validation fails closed when `LICENSE` is absent or empty, the tag
does not equal `v` plus the POM/SDK version, the public key is malformed, or
the key embedded in the built SDK differs byte-for-byte from the protected
release key. The verifier reads the committed source resource and compiled
main resource by filesystem path, never through the test classpath, and the
Central bundle builder independently verifies the entry in the exact JAR
bytes it packages.

The `release` Maven profile signs one artifact set, after which the
release-readiness job builds and verifies one Central deployment bundle
without network publication. CI records that bundle's SHA-256 and
source/tag/profile attestation. A protected manual staging job revalidates and
uploads those exact bytes as a `USER_MANAGED` Central deployment, waits for
`VALIDATED`, and persists the bound deployment ID. A separate protected manual
publish job promotes only that ID and waits for `PUBLISHED`; neither job
rebuilds or re-uploads the release bundle.

The staging and publish jobs use a test-scope Java release client rather than
putting an authorization header in a shell command. It reads Central
credentials only from the protected environment, rejects delimiters and
control characters, never logs them, refuses redirects, bounds every response,
and parses status JSON strictly. Duplicate or escaped-duplicate fields,
trailing data, non-string identifiers/states, unknown states, and a response
whose deployment ID does not exactly match the persisted ID all fail closed.

The Central upload endpoint has no caller-supplied idempotency key. Do not
retry a staging job blindly after its upload starts: first inspect the
persisted `central-deployment-id` artifact and Central Portal, otherwise a
failed runner can leave an orphaned user-managed deployment. Protect both
Maven Central environments, the SemVer tag pattern, and every listed variable
in GitLab project settings.

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

## Error handling

All SDK exceptions extend `LockerError`. Operation errors are mapped from the
protocol's numeric error code and retain the safe structured details:

```java
import locker.exception.LockerError;
import locker.exception.ResourceNotFoundError;

try {
    String value = client.secrets().retrieve("DATABASE_PASSWORD", String.class);
} catch (ResourceNotFoundError error) {
    // Handle a missing secret explicitly.
} catch (LockerError error) {
    Integer protocolCode = error.getProtocolCode();
    String requestId = error.getRequestId();
    Boolean retryable = error.getRetryable();
}
```

Authentication, permission, protocol, storage, network, and server failures
fail closed. The SDK never places raw request bodies, responses, or CLI stderr
in exception messages.

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

## License

Apache License 2.0.
