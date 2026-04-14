# Safety and correctness story

This page answers the trust question: why is a daemonized analysis and mutation
tool safe enough to use repeatedly?

## Short answer

Kast spreads trust across multiple layers instead of betting on a single guard:
stable contracts, explicit capabilities, edit validation, shared fixtures,
integration tests, smoke tests, CI workflows, and optional telemetry all
contribute to confidence.

## Analysis

The first layer is contract clarity. `analysis-api` defines explicit request and
response models as well as capability flags, which reduces ambiguity between the
CLI and the backend.

The second layer is controlled mutation. Rename and apply-edits work through
planned edits and validation logic rather than ad hoc file rewrites. That design
raises the bar for semantic changes and creates reviewable artifacts.

The third layer is test alignment. Shared contract fixtures force multiple
backend implementations to behave consistently. Integration tests then exercise
real compiler and cache behavior, which matters because many risks only appear
under realistic workspace state.

The fourth layer is release validation. Smoke scripts, GitHub Actions, and build
logic exist to catch packaging or installation regressions before a release
lands.

The fifth layer is runtime observability. When something still goes wrong,
telemetry and debug scopes create a path to inspection without requiring opaque
external infrastructure.

## Evidence used

The pages below support this synthesis.

- [[entities/analysis-api]]
- [[concepts/semantic-analysis-operations]]
- [[concepts/testing-and-verification]]
- [[concepts/telemetry-and-observability]]
- [[sources/ci-cd-pipelines-and-smoke-testing]]
- [[sources/backend-standalone-integration-tests]]

## Follow-ups

These additions would tighten the trust story further.

- Ingest release notes or issue history to capture real failure classes.
- Add a dedicated page for rename safety if mutation workflows become the main
  reason to consult this wiki.
