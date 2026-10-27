# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Reports API: `GET /v1/reports/summary` groups calls by client, model or provider and
  `GET /v1/reports/series` buckets them by hour or day, with call and error counts, prompt,
  completion and cache tokens, cost, unpriced calls and exact p50/p95/p99 of total time and
  time to first token.
- `from`, `to`, `range`, `zone` and `client`/`model`/`provider` filters with defaults, a
  maximum range and a row limit; bad input returns a 400 problem.
- Built-in dashboard at `/dashboard`: one Thymeleaf page with inline CSS and SVG, no scripts.
- `gateway.reports` settings: `enabled`, `dashboard.enabled`, `default-range`, `max-range`,
  `max-rows`.
- Testcontainers test of the report queries against real Postgres.
- [docs/reports.md](docs/reports.md).

- Built-in cost and latency tracking: every call is priced from `gateway.pricing` and stored
  in the `gateway_call` table (client, alias, provider, model, status, tokens, cost, currency,
  price version, time to first token, total time, failed attempts). No prompt or answer text
  is stored.
- Config price table per million tokens with separate cache read and write rates, `*` model
  entries per provider and `effective-from` dates so price changes never rewrite old costs.
- Postgres storage with Flyway migrations, in-memory H2 by default for local runs.
- Background batched writer with a bounded queue: the database is never on the request path,
  and records are dropped and counted rather than slowing callers down.
- Retention purge (`gateway.tracking.retention`, 90 days by default).
- `GET /v1/calls/{id}` returns the stored record for one call.
- `X-Gateway-Client` header to attribute calls until Entra authentication arrives.
- Startup check that every price names a configured provider.
- Testcontainers test against real Postgres.
- [docs/tracking.md](docs/tracking.md).

- Native Anthropic Messages adapter (`type: anthropic`): `x-api-key` and `anthropic-version`
  headers, system messages moved to `system`, `max_tokens` always sent, stop reasons and usage
  mapped, including prompt cache reads and writes.
- Native Gemini adapter (`type: gemini`): `x-goog-api-key` header, `user`/`model` roles,
  `systemInstruction`, `generationConfig`, `usageMetadata` with thinking tokens counted as
  output, blocked prompts reported as `content_filter`.
- Streaming: `"stream": true` returns `text/event-stream` in the OpenAI
  `chat.completion.chunk` format with a final usage chunk and `data: [DONE]`, for all three
  adapters. Retries and fallback apply until the first token; later upstream failures end the
  stream with an error event.
- `CallOutcome` and the `CallObserver` hook: usage, finish reason, failed attempts, time to
  first token and total time for every call, ready for cost and latency storage.
- `default-max-tokens` provider setting.
- `cache_read_tokens` and `cache_write_tokens` in `usage` when the provider reports them.
- [docs/providers.md](docs/providers.md) with example configuration and the native mappings.

### Changed

- `stream=true` no longer returns 501.
- `api-key-header`, `api-key-prefix` and `chat-path` default per provider type, so native
  providers need only `type`, `base-url` and `api-key`.
- For streamed calls `read-timeout` limits the wait for headers and the gap between events,
  not the whole answer.
- AWS Bedrock moved to a later milestone; `bedrock` providers are still skipped by fallback.

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
