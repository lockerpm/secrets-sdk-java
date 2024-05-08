# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[0.0.4]: https://git.cystack.org/locker/secrets-sdk-java