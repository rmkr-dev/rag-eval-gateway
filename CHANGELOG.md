# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.0.0] - 2026-11-07

First release. One API in front of OpenAI-compatible providers and native
Anthropic, Gemini and AWS Bedrock adapters, with routing, fallback and
streaming. Cost and latency are stored per call, with reports and a dashboard,
Entra ID authentication, per-client budgets and rate limits, PII redaction,
eval sampling for llm-eval-harness, and Kustomize manifests for AKS with an
ingress-nginx Ingress and an ACR publish workflow.

### Added

- ingress-nginx Ingress in the Kustomize base, class `nginx`, path `/` to Service port
  `http`. Overlays set the host (`gateway-dev.REPLACE_ME` in dev, `gateway.REPLACE_ME` in
  prod) and the TLS secret name (`REPLACE_ME`). Proxy buffering is off so streamed chats
  pass through. The preflight checks the Ingress and still flags leftover placeholders.
  See [docs/deployment-aks.md](docs/deployment-aks.md).
- `Publish image to ACR` workflow. A push to `main` publishes tags `main` and the short
  SHA; a `v*` tag publishes that version as well; `workflow_dispatch` can add a tag. Login
  is Azure OIDC against the `acr` environment, and the identity needs AcrPush. No registry
  password is stored. The overlays already point at `REPLACE_ME.azurecr.io/rag-eval-gateway`,
  which is what `IMAGE_REPOSITORY` should be once the registry name is filled in.

- Diagram-first docs: [docs/getting-started.md](docs/getting-started.md) for the GitHub
  Packages Maven server, the `local` profile, a first chat call and where config lives;
  [docs/architecture.md](docs/architecture.md) for the path from auth through budgets, PII,
  routing and adapters to tracking and eval; and [docs/README.md](docs/README.md) as the
  index. The README points at them.

- Kustomize manifests for AKS under `deploy/k8s`, no Helm: a base with Deployment, Service,
  ServiceAccount for Azure workload identity, `SecretProviderClass` for the Key Vault CSI
  driver, HPA, PodDisruptionBudget and NetworkPolicies (default deny; ingress from the ingress
  controller's namespace; egress to DNS, Postgres and HTTPS), plus `dev` and `prod` overlays
  for replicas, HPA bounds, resources, image and config.
- Key Vault secrets are mounted as files and read as a Spring config tree, so the database
  password and provider keys never land in a Kubernetes Secret. Non-secret settings come from
  generated ConfigMaps (`gateway.env`, `gateway.yml`) that roll the pods when they change.
- Startup, liveness and readiness probes on the actuator health groups, with the database in
  readiness; non-root, read-only root filesystem, all capabilities dropped; memory request
  equal to the limit with the heap at 75% of it.
- `scripts/aks-preflight.sh`: tools, context, namespace, workload identity webhook, Key Vault
  CSI driver and CRD, render, leftover placeholders, and an optional server dry run.
- `Deploy to AKS` workflow, manual only, with OIDC login per GitHub environment, preflight,
  `kubectl apply -k` and a rollout wait. CI renders both overlays, validates them with
  kubeconform and runs shellcheck. See [docs/deployment-aks.md](docs/deployment-aks.md).

- Eval hooks under `gateway.eval`, off by default: a sample rate by default, per model alias
  and per client (most specific wins), and tags. Sampled plain and streamed calls are captured
  after they complete through a bounded queue that drops and counts when full, with the prompt
  as the provider saw it after PII redaction, the answer, alias, provider, client and call id.
- `eval_context` on chat requests for the retrieved passages. It is never sent to providers
  and is dropped unless the call is sampled.
- `EvalSink` SPI with a `database` sink (`eval_sample` table, Flyway `V3`) and a `file` sink
  writing JSONL or llm-eval-harness fixture directories.
- `GET /v1/eval/samples` as JSON, JSONL or a harness fixtures zip, `GET /v1/eval/samples/{id}`,
  `POST /v1/eval/samples/{id}/scores`, `POST /v1/eval/scores` for a harness JSON report, and
  `GET /v1/eval/summary` with average scores per alias or client, all behind
  `Gateway.Reports`. Retention purge (30 days) and optional Micrometer counters. See
  [docs/eval-hooks.md](docs/eval-hooks.md).

- PII redaction under `gateway.pii` for every prompt message, plain and streamed, before any
  provider is asked. Modes `off`, `mask`, `redact` (`[EMAIL_1]`, same value same placeholder)
  and `block` (`422` problem, `code: pii_detected`, counts by detector), set by default, per
  model alias and per client; the stricter entry wins. Off until configured.
- Built-in detectors for email, phone, credit card (Luhn), IBAN (mod 97), US SSN, IPv4 and
  IPv6, Indian PAN and Aadhaar (Verhoeff), plus `custom` regex detectors from config and a
  `PiiDetector` SPI for beans. Patterns are compiled once; bad config fails startup.
- Optional `restore` puts this call's originals back into answers, streamed ones included.
- Redaction counts per call in the new `pii_redactions` column and `X-Gateway-Pii` header.
  Provider error details are scrubbed before logging (`scrub-logs`, on by default). See
  [docs/pii-redaction.md](docs/pii-redaction.md).

- Per-client budgets under `gateway.budgets`: `daily-tokens`, `monthly-tokens`, `daily-cost`
  and `monthly-cost`, with a `default` and per-client entries (`unlimited: true` to exempt a
  client). Spend is read from the stored calls every `refresh-interval` (15s) plus the calls
  the replica finished since, in the configured `zone` (UTC). Over budget, calls are refused
  before any provider with a `429` problem, `code: budget_exceeded`, `resets_at` and
  `Retry-After`. From `warn-at` (80%) answers carry `X-Gateway-Budget-Warning`.
- Per-client rate limits under `gateway.rate-limits`: `requests-per-minute` and
  `tokens-per-minute` as in-memory token buckets, refused with a `429` problem,
  `code: rate_limited` and `Retry-After`. Streamed usage counts when the stream ends. A
  `RateLimitStore` bean can replace the in-memory buckets with a shared store.
- Nothing is limited until a limit is configured. Budgets need tracking on; startup fails
  otherwise. See [docs/budgets.md](docs/budgets.md).

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

- [docs/reports.md](docs/reports.md) now shows `gateway.reports.enabled` and
  `gateway.reports.dashboard.enabled` defaulting to `true`, matching the gateway. Both stay
  behind `Gateway.Reports`.
- [docs/deployment-aks.md](docs/deployment-aks.md) names the Key Vault file aliases: the
  secret `gateway-db-password` is mounted as `spring.datasource.password`, and the provider
  keys as `OPENROUTER_API_KEY` and `ANTHROPIC_API_KEY`.
- [docs/auth.md](docs/auth.md) lists the eval endpoints on `Gateway.Reports`.

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
