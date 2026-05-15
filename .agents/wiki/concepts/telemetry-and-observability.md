# Telemetry and observability

Kast's standalone backend includes local telemetry so you can inspect what the
daemon is doing without attaching a full external observability stack.

## Summary

The important design choice is that telemetry is self-contained. The backend can
emit JSONL spans to a predictable local path, gated by environment variables and
scoped instrumentation, which makes it useful during development, debugging, and
performance investigation.

## What the wiki currently believes

- Telemetry is opt-in and primarily driven by environment variables.
- Span scopes and detail levels let Kast expose debug information without making
  the common path noisy.
- Local file export reflects Kast's bias toward inspectable plain files and
  standalone operation.

## Evidence and sources

These pages support the current observability picture.

- [[sources/telemetry-and-observability]] - Defines configuration, scope,
  detail, span lifecycle, and JSONL export.
- [[sources/backend-standalone-analysis-engine]] - Places telemetry inside the
  backend subsystem map.
- [[sources/testing-infrastructure]] - Shows that telemetry itself is tested.
- [[sources/ci-cd-pipelines-and-smoke-testing]] - Shows how automated validation
  complements runtime telemetry.

## Related pages

These pages explain where telemetry becomes useful.

- [[entities/backend-standalone]]
- [[concepts/indexing-and-caching]]
- [[concepts/testing-and-verification]]
- [[analyses/safety-and-correctness-story]]

## Open questions

The current notes say more about plumbing than long-term practice.

- Which telemetry scopes are most useful for diagnosing slow or surprising
  refactors?
- How much telemetry data is safe to collect by default in large repositories?
