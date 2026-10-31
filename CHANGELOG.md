# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Entra ID authentication through `spring-security-jwt-starter` 1.0.0 (from GitHub Packages):
  every endpoint except health and info needs a valid access token, with `401` and `403`
  problem responses and `Server-Timing: auth;dur=...`. See [docs/auth.md](docs/auth.md).
- Two roles checked with `@Secured`: `GATEWAY_CALLER` (app role `Gateway.Call`) for chat,
  models and the caller's own calls, and `GATEWAY_REPORTS` (app role `Gateway.Reports`) for
  reports, the dashboard and every call.
- The client recorded for each call comes from the token: `azp`/`appid`, or the claim in
  `gateway.auth.client-claim`, with optional readable names in `gateway.auth.client-names`.
- `local` profile that turns auth off for local development, with a startup warning.

- Native AWS Bedrock adapter (`type: bedrock`) on the Converse API: `/model/{modelId}/converse`
  for plain calls and `/converse-stream` for streaming, with the binary event stream frames
  (checksums verified) turned into the same SSE chunks as every other provider. System
  messages go to `system`, `max_tokens` and `temperature` to `inferenceConfig`, usage
  including cache reads and writes feeds cost tracking, and stop reasons map to the OpenAI
  vocabulary.
- Hand-written AWS SigV4 signing with no AWS SDK dependency, tested against the official AWS
  SigV4 test suite and a Bedrock request checked with botocore.
- `aws.region`, `aws.access-key-id`, `aws.secret-access-key` and `aws.session-token` provider
  settings; blank keys fall back to the standard `AWS_*` environment variables, and
  `base-url` defaults to the region's `bedrock-runtime` endpoint. A Bedrock `api-key` is sent
  as a bearer token instead. Missing region or keys fail startup; keys are never logged.
- Bedrock errors follow the existing routing rules, including `fallback-on-bad-request` for
  400, 413 and 422 and stream exceptions before the first token.

- `fallback-on-bad-request` now also covers 413 and 422: with the switch on, a 400, 413 or
  422 from one target moves to the next, and the last target's status and message reach the
  caller instead of a 502. Off by default, so nothing changes for aliases without it. See
  [docs/routing.md](docs/routing.md#falling-back-on-400-413-and-422).

- `fallback-on-bad-request` per model alias, off by default: a 400 from one target moves to
  the next instead of going back to the caller, before the first token when streaming. A 400
  from the last target still reaches the caller. See
  [docs/routing.md](docs/routing.md#falling-back-on-400-413-and-422).

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

- `gateway.reports.enabled` and `gateway.reports.dashboard.enabled` default to `true` again,
  now that auth protects them. The dashboard still defaults to UTC.
- `X-Gateway-Client` is ignored while auth is on; it names the caller only with auth off.
- `GET /v1/calls/{id}` returns `404` for another client's call unless the caller has
  `GATEWAY_REPORTS`.
- CI, CodeQL and the Docker build read GitHub Packages with the workflow token; the Dockerfile
  takes Maven settings and the token as build secrets.

- `bedrock` providers are no longer skipped by fallback; they call Bedrock.
- Provider `toString` no longer shows API keys or AWS credentials.

- `gateway.reports.enabled` and `gateway.reports.dashboard.enabled` default to `false` until
  Entra auth protects them; set both to `true` to serve `/v1/reports/*` and `/dashboard`.

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
