# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `POST /v1/chat/completions`: one chat endpoint routed by model alias, with an
  `X-Gateway-Calls` header and `application/problem+json` errors (404 unknown model,
  502 with the list of failed attempts, provider rejections passed back, 501 for `stream=true`).
- Provider SPI (`ChatProvider`, `ChatProviderFactory`, `ProviderRegistry`) so a new wire
  protocol is one Spring bean.
- Generic OpenAI-compatible adapter: base URL, key header, key prefix, `chat-path`, extra
  `headers` and model name all from configuration, with connect and read timeouts.
- Routing with fallback chains: transient failures (timeouts, 408, 425, 429, 5xx) retry on the
  same target with exponential backoff, jitter and `Retry-After`; provider failures (401, 403,
  404) fall back at once; request failures (400, 413, 422) go straight back to the caller.
- `gateway.retry` settings: `max-attempts`, `initial-backoff`, `max-backoff`, `multiplier`,
  `jitter`.
- WireMock tests for the adapter and the endpoint.

- Spring Boot 4.1 / Java 21 project skeleton with virtual threads and graceful shutdown.
- Unified chat request and response contract (`ChatRequest`, `ChatResponse`, `Usage`).
- `gateway.providers` and `gateway.models` configuration with startup validation of
  alias-to-provider references.
- `GET /v1/models` listing aliases and their fallback order, without secrets or URLs.
- Actuator health with liveness and readiness probes.
- Multi-stage Docker image running as a non-root user.
- CI on Java 21 with coverage, image build and smoke test, CodeQL and Dependabot.
