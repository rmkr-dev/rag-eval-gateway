# rag-eval-gateway

A Spring Boot 4 (Java 21) gateway that applications call instead of calling AI models directly.

One API in front of any model provider, with routing and fallback, built-in cost and latency
tracking, per-client budgets, PII redaction and light eval hooks. Deploys to AKS with plain
Kustomize manifests.

> Status: early development. See the roadmap once the project skeleton lands.

## License

Apache License 2.0. See [LICENSE](LICENSE).
