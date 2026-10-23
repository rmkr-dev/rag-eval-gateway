# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Spring Boot 4.1 / Java 21 project skeleton with virtual threads and graceful shutdown.
- Unified chat request and response contract (`ChatRequest`, `ChatResponse`, `Usage`).
- `gateway.providers` and `gateway.models` configuration with startup validation of
  alias-to-provider references.
- `GET /v1/models` listing aliases and their fallback order, without secrets or URLs.
- Actuator health with liveness and readiness probes.
- Multi-stage Docker image running as a non-root user.
- CI on Java 21 with coverage, image build and smoke test, CodeQL and Dependabot.
