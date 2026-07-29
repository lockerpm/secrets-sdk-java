# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-07-26

### Changed

- Use Locker SDK protocol v1 over JSON-RPC stdio instead of human CLI
  arguments.
- Require `max_response_bytes`, the eight base vault methods, and
  `system.capabilities` during capability negotiation, then enforce the
  advertised response bound. Treat paginated list methods as additive and
  reject an unsupported page call locally.
- Require explicit or trusted CLI resolution and remove implicit binary
  downloads.
- Harden process execution, error mapping, response validation, packaging,
  and hermetic tests.
- Continuously record CLI descendants, repeat graceful/forced process-tree
  termination on timeout, interruption, and root exit, and verify that every
  recorded process stopped.
- Enforce warning-free Java compilation and complete serialization metadata
  for public exception and collection types.
- Make CLI request metadata and parameter snapshots immutable.
- Align legacy credential aliases with the shared SDK precedence and accept
  `ACCESS_KEY_SECRET` for migration compatibility.
- Refresh Gson, the Java 11-compatible JUnit 5 line, and the stable Maven
  Central publishing plugin.
- Resolve the managed CLI through signed update-channel v2 metadata while
  preserving explicit-path and `LOCKER_CLI_PATH` overrides.
- Namespace Java-managed state under `~/.locker/sdk-cli/java`, publish
  immutable release generations behind an atomic current pointer, and rotate
  long-lived protocol clients when that pointer changes.
- Require explicit CLI overrides to identify executable files instead of
  searching the ambient `PATH`.

### Removed

- Remove the deprecated mutable request-usage setter.

### Added

- Add typed `SecretPage` and `EnvironmentPage` models plus
  `listPage` service APIs for `secret.list_page` and
  `environment.list_page`.
- Add the declared Apache License 2.0 text to the repository and packaged
  artifact metadata.
- Add protected SemVer-tag release readiness plus separate manual Maven
  Central staging and publish gates that promote one checksummed, signed
  bundle without rebuilding or re-uploading it.

### Security

- Keep credentials, headers, and secret mutation values out of process
  arguments, logs, and the child process environment.
- Redact secret values from model string representations.
- Verify canonical signed latest and manifest envelopes, detached binary
  Ed25519 signatures, SHA-256, size, exact five-platform coverage, rollback,
  and same-version consistency before executing a managed binary.
- Stream bounded CLI artifacts into private same-directory temporary files,
  validate strict ELF/Mach-O/PE target headers, serialize updates with an
  interprocess lock, and atomically publish complete verified generations.
- Persist a six-hour update interval and permit transient-network fallback
  only to a fully reverified cache with a separate 60-second retry marker;
  all TLS, integrity, and schema failures remain fail-closed. Persist the exact
  signed-latest `(version, source_commit, manifest SHA-256, manifest size)`
  tuple before manifest retrieval as an independent rollback/equivocation
  floor, and recover offline across every publication boundary.
- Fail releases closed unless the protected raw Ed25519 public key, committed
  source resource, compiled main resource, exact packaged JAR entry, canonical
  tag, SDK version, and LICENSE agree; verification never resolves through the
  test classpath and CI never materializes package trust from its variable.
- Keep Maven Central authorization out of command arguments and strictly
  parse and bind every deployment status response to the persisted ID.

## [0.0.4] - 2024-05-08

### Changed

- Change flag `--versbose` to `--json`

### Removed

- Remove flag `--data`

### Added

- Add new flag  `--key`, `--value`, `--description`, `--environment` for create new secret
- Add new flag  `--new-key`, `--new-value`, `--new-description`, `--new-environment` for update a secret
- Add new flag  `--name`, `--url`, `--description` for create new environment
- Add new flag  `--new-name`, `--new-url`, `--new-description` for update an environment

[1.0.0]: https://github.com/lockerpm/secrets-sdk-java/releases/tag/v1.0.0
[0.0.4]: https://git.cystack.org/locker/secrets-sdk-java
