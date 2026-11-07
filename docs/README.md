# Docs

One page per topic. The [repository README](../README.md) is the short front door.
[Getting started](getting-started.md) is the path from a checkout to a local call, and
[architecture](architecture.md) is how that call moves through the gateway.

| Page | Purpose |
|---|---|
| [getting-started.md](getting-started.md) | Maven settings for GitHub Packages, the local profile, a first chat call, and where config lives. |
| [architecture.md](architecture.md) | Request path from auth through budgets, PII, routing and adapters to tracking and eval. |
| [auth.md](auth.md) | Entra ID, the `Gateway.Call` and `Gateway.Reports` roles, client names, and the local profile. |
| [providers.md](providers.md) | OpenAI-compatible, Anthropic, Gemini and Bedrock adapters, and streaming. |
| [routing.md](routing.md) | Alias routing, retries and fallback, including 400, 413 and 422. |
| [tracking.md](tracking.md) | Per-call cost and latency, the price table, Postgres and H2, and call lookup. |
| [reports.md](reports.md) | Summary and series APIs and the dashboard, on by default behind `Gateway.Reports`. |
| [budgets.md](budgets.md) | Per-client token and cost budgets, and request and token rate limits. |
| [pii-redaction.md](pii-redaction.md) | Mask, redact or block personal data in prompts before a provider sees them. |
| [eval-hooks.md](eval-hooks.md) | Sample real calls and score them with llm-eval-harness. Off by default. |
| [deployment-aks.md](deployment-aks.md) | Kustomize on AKS: ingress-nginx, ACR, workload identity and Key Vault. No Helm chart. |
| [roadmap.md](roadmap.md) | Milestones 1 to 12 are done. v1.0.0 is released. |

The picture on the reports page is [images/dashboard.png](images/dashboard.png).

Contributing and how to report a vulnerability are in
[CONTRIBUTING.md](../CONTRIBUTING.md) and [SECURITY.md](../SECURITY.md).
